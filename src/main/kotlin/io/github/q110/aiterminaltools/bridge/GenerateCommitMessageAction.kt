// 提交信息生成动作 — 根据 Commit 面板已勾选文件调用 OpenCode / Claude Code 生成提交文案
package io.github.q110.aiterminaltools.bridge

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.vcs.commit.CommitMessageUi
import com.intellij.vcs.commit.CommitWorkflowUi
import io.github.q110.aiterminaltools.settings.AiTerminalToolsSettings
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.UUID

class GenerateCommitMessageAction : AnAction(AllIcons.Debugger.Console) {
    /** Commit 面板工具栏动作需要在 EDT 根据勾选文件实时启用/禁用 */
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }

    override fun update(event: AnActionEvent) {
        val workflowUi = event.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)
        val hasIncludedFiles = workflowUi != null &&
            (workflowUi.getIncludedChanges().isNotEmpty() || workflowUi.getIncludedUnversionedFiles().isNotEmpty())
        event.presentation.isEnabled = event.project != null && hasIncludedFiles
        event.presentation.isVisible = event.project != null && workflowUi != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val workflowUi = event.getData(VcsDataKeys.COMMIT_WORKFLOW_UI) ?: return

        val includedChanges = workflowUi.getIncludedChanges()
        val includedUnversionedFiles = workflowUi.getIncludedUnversionedFiles()
        if (includedChanges.isEmpty() && includedUnversionedFiles.isEmpty()) return

        val commitMessageUi = workflowUi.commitMessageUi
        val currentMessage = commitMessageUi.text.trim()
        if (currentMessage.isNotEmpty() && !confirmReplaceCommitMessage(project)) {
            return
        }

        val settings = AiTerminalToolsSettings.getInstance().getState()
        val commitMessageAiTool = normalizedCommitMessageAiTool(settings.commitMessageAiTool)
        commitMessageUi.startLoading()
        // AI CLI 调用和 git diff 收集都可能较慢，放到后台任务避免阻塞 Commit UI。
        GenerateCommitMessageTask(
            project,
            workflowUi,
            commitMessageUi,
            includedChanges,
            includedUnversionedFiles,
            commitMessageAiTool
        ).queue()
    }

    private fun confirmReplaceCommitMessage(project: Project): Boolean {
        return Messages.showYesNoDialog(
            project,
            "当前区域已有内容，是否替换？",
            "生成提交信息",
            "替换",
            "取消",
            Messages.getQuestionIcon()
        ) == Messages.YES
    }

    /** 后台任务负责收集变更、调用 AI CLI，并在 EDT 写回 Commit Message 输入框 */
    private class GenerateCommitMessageTask(
        project: Project,
        private val workflowUi: CommitWorkflowUi,
        private val commitMessageUi: CommitMessageUi,
        private val includedChanges: List<Change>,
        private val includedUnversionedFiles: List<FilePath>,
        private val commitMessageAiTool: String
    ) : Task.Backgroundable(project, commitMessageTaskTitle(commitMessageAiTool), true) {
        override fun run(indicator: ProgressIndicator) {
            try {
                indicator.text = "Collecting selected changes"
                val changeSummary = CommitChangeSummary(project, includedChanges, includedUnversionedFiles).build()
                indicator.checkCanceled()

                indicator.text = "Running ${commitMessageCommandName(commitMessageAiTool)}"
                val generatedMessage = commitMessageGenerator(project, changeSummary, commitMessageAiTool).generate(indicator)
                indicator.checkCanceled()

                invokeOnEdt {
                    commitMessageUi.setText(generatedMessage)
                    commitMessageUi.focus()
                }
            } catch (exception: ProcessCanceledException) {
                throw exception
            } catch (exception: CommitMessageGenerationException) {
                invokeOnEdt {
                    AiTerminalBridgeService.notify(project, exception.message ?: "生成提交信息失败", NotificationType.WARNING)
                }
            } catch (exception: Throwable) {
                invokeOnEdt {
                    AiTerminalBridgeService.notify(project, "生成提交信息失败：${exception.message}", NotificationType.WARNING)
                }
            } finally {
                invokeOnEdt {
                    commitMessageUi.stopLoading()
                }
            }
        }

        private fun invokeOnEdt(action: () -> Unit) {
            ApplicationManager.getApplication().invokeLater(
                {
                    if (!project.isDisposed && !Disposer.isDisposed(workflowUi)) {
                        action()
                    }
                },
ModalityState.any()
            )
        }
    }

    private interface CommitMessageGenerator {
        fun generate(indicator: ProgressIndicator): String
    }

    /** 将 Commit 面板中真正勾选的文件整理成提示词上下文 */
    private class CommitChangeSummary(
        private val project: Project,
        private val includedChanges: List<Change>,
        private val includedUnversionedFiles: List<FilePath>
    ) {
        fun build(): String {
            val changedFiles = includedChanges.mapNotNull { change -> change.toIncludedFile(project) }
            val unversionedFiles = includedUnversionedFiles.map { filePath -> filePath.toIncludedFile(project, "新增文件") }
            val allFiles = changedFiles + unversionedFiles
            if (allFiles.isEmpty()) {
                throw CommitMessageGenerationException("没有可用于生成提交文案的文件。")
            }

            val diffs = computeDiffs(allFiles)
            val sb = StringBuilder()
            sb.appendLine("已勾选文件：")
            for (file in allFiles) {
                sb.appendLine("- ${file.status}: ${file.relativePath}")
            }
            sb.appendLine()
            sb.appendLine("各文件变更内容（只分析以下文件的变更来生成提交信息）：")
            for (file in allFiles) {
                val diff = diffs[file.relativePath] ?: ""
                if (diff.isNotEmpty()) {
                    sb.appendLine("=== ${file.relativePath} ===")
                    sb.appendLine(diff)
                    sb.appendLine()
                }
            }
            return sb.toString()
        }

        private fun computeDiffs(files: List<IncludedFile>): Map<String, String> {
            val diffs = linkedMapOf<String, String>()
            for (file in files) {
                // 新增文件没有 git diff，直接读取文件内容；其他状态优先使用 git diff。
                val content = when (file.status) {
                    "新增" -> readFileContent(file.absolutePath)
                    "删除" -> gitDiff(file.gitRoot, file.relativePath)
                    else -> gitDiff(file.gitRoot, file.relativePath)
                }
                if (!content.isNullOrBlank()) {
                    diffs[file.relativePath] = content
                }
            }
            return diffs
        }

        private fun gitDiff(gitRoot: Path, relativePath: String): String? {
            return try {
                val process = ProcessBuilder(
                    listOf("git", "diff", "--no-color", "--no-ext-diff", "--", relativePath)
                )
                    .directory(gitRoot.toFile())
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
                process.waitFor(30, TimeUnit.SECONDS)
                output.takeIf { it.isNotBlank() }
            } catch (_: Throwable) {
                null
            }
        }

        private fun readFileContent(path: Path): String? {
            return try {
                Files.readString(path, StandardCharsets.UTF_8)
            } catch (_: Throwable) {
                null
            }
        }
    }

    private class OpenCodeCommitMessageGenerator(
        private val project: Project,
        private val changeSummary: String
    ) : CommitMessageGenerator {
        override fun generate(indicator: ProgressIndicator): String {
            val basePath = project.basePath
                ?: throw CommitMessageGenerationException("项目没有可用的工作目录。")
            val settings = AiTerminalToolsSettings.getInstance().getState()
            val fullPrompt = buildPrompt(changeSummary)
            // OpenCode 的 agent 配置写入临时 XDG_CONFIG_HOME，避免污染用户全局配置。
            val configHome = createOpenCodeConfigHome(fullPrompt)
            val basePathPath = Path.of(basePath).toAbsolutePath().normalize()
            val sessionTitle = "Generate commit message ${UUID.randomUUID()}"
            try {
                val command = mutableListOf(
                    opencodeCommand(),
                    "run",
                    "--pure",
                    "--agent",
                    COMMIT_MESSAGE_AGENT,
                    "--dir",
                    basePath,
                    "--title",
                    sessionTitle
                )
                val model = settings.commitMessageModel.trim()
                if (model.isNotEmpty()) {
                    command += listOf("-m", model)
                }
                command += "请根据系统提示生成提交信息"

                val result = runProcess(
                    command,
                    basePathPath,
                    OPENCODE_TIMEOUT_SECONDS,
                    indicator,
                    "opencode 生成提交信息超时",
                    environment = mapOf("XDG_CONFIG_HOME" to configHome.toString())
                )
                if (result.exitCode != 0) {
                    throw CommitMessageGenerationException("opencode 执行失败：${result.output.take(ERROR_OUTPUT_LIMIT)}")
                }

                val message = cleanupOutput(result.output)
                if (message.isBlank()) {
                    throw CommitMessageGenerationException("opencode 没有返回可用的提交信息")
                }
                return message
            } finally {
                // opencode run 会创建临时 session，按标题和目录匹配后尽量清理。
                deleteSessionQuietly(sessionTitle, basePathPath)
                configHome.toFile().deleteRecursively()
            }
        }

        private fun deleteSessionQuietly(sessionTitle: String, basePath: Path) {
            try {
                val listResult = runProcess(
                    listOf(opencodeCommand(), "session", "list", "--format", "json", "--max-count", "20"),
                    basePath,
                    OPENCODE_SESSION_CLEANUP_TIMEOUT_SECONDS
                )
                if (listResult.exitCode != 0) return

                val sessionId = parseOpenCodeSessions(listResult.output)
                    .firstOrNull { session ->
                        session.title == sessionTitle && pathsEqual(session.directory, basePath)
                    }
                    ?.id
                    ?: return

                runProcess(
                    listOf(opencodeCommand(), "session", "delete", sessionId),
                    basePath,
                    OPENCODE_SESSION_CLEANUP_TIMEOUT_SECONDS
                )
            } catch (_: Throwable) {
            }
        }
}

    private class ClaudeCodeCommitMessageGenerator(
        private val project: Project,
        private val changeSummary: String
    ) : CommitMessageGenerator {
        override fun generate(indicator: ProgressIndicator): String {
            val basePath = project.basePath
                ?: throw CommitMessageGenerationException("项目没有可用的工作目录。")
            val settings = AiTerminalToolsSettings.getInstance().getState()
            val prompt = buildPrompt(changeSummary)
            val basePathPath = Path.of(basePath).toAbsolutePath().normalize()
            val command = mutableListOf(
                claudeCommand(),
                "-p",
                "请根据以上变更内容生成中文提交信息",
                "--output-format",
                "text",
                "--no-session-persistence"
            )
            val model = settings.claudeCommitMessageModel.trim()
            if (model.isNotEmpty()) {
                command += listOf("--model", model)
            }

            val result = runProcessWithStdin(
                command,
                basePathPath,
                prompt,
                CLAUDE_TIMEOUT_SECONDS,
                indicator,
                "claude 生成提交信息超时"
            )
            if (result.exitCode != 0) {
                throw CommitMessageGenerationException("claude 执行失败：${result.output.take(ERROR_OUTPUT_LIMIT)}")
            }

            val message = cleanupOutput(result.output)
            if (message.isBlank()) {
                throw CommitMessageGenerationException("claude 没有返回可用的提交信息")
            }
            return message
        }
    }

    private data class IncludedFile(
        val gitRoot: Path,
        val absolutePath: Path,
        val relativePath: String,
        val status: String
    )

    private data class ProcessResult(val exitCode: Int, val output: String)

    private data class OpenCodeSession(val id: String, val title: String, val directory: String)

    private class CommitMessageGenerationException(message: String) : RuntimeException(message)

    companion object {
        private const val COMMIT_MESSAGE_AGENT = "commit-message"
        private const val ERROR_OUTPUT_LIMIT = 600
        private const val OPENCODE_TIMEOUT_SECONDS = 120L
        private const val CLAUDE_TIMEOUT_SECONDS = 120L
        private const val OPENCODE_SESSION_CLEANUP_TIMEOUT_SECONDS = 15L
        private const val PROCESS_POLL_INTERVAL_MS = 200L
        private const val COMMIT_MESSAGE_AI_TOOL_OPENCODE = "opencode"
        private const val COMMIT_MESSAGE_AI_TOOL_CLAUDE = "claude"
        private val ANSI_PATTERN = Regex("\\u001B\\[[;?0-9]*[ -/]*[@-~]")

        private fun normalizedCommitMessageAiTool(aiTool: String): String {
            return if (aiTool == COMMIT_MESSAGE_AI_TOOL_CLAUDE) {
                COMMIT_MESSAGE_AI_TOOL_CLAUDE
            } else {
                COMMIT_MESSAGE_AI_TOOL_OPENCODE
            }
        }

        private fun commitMessageTaskTitle(aiTool: String): String {
            return "Generating ${commitMessageToolDisplayName(aiTool)} Commit Message"
        }

        private fun commitMessageCommandName(aiTool: String): String {
            return if (aiTool == COMMIT_MESSAGE_AI_TOOL_CLAUDE) "claude" else "opencode"
        }

        private fun commitMessageToolDisplayName(aiTool: String): String {
            return if (aiTool == COMMIT_MESSAGE_AI_TOOL_CLAUDE) "Claude Code" else "OpenCode"
        }

        private fun commitMessageGenerator(
            project: Project,
            changeSummary: String,
            aiTool: String
        ): CommitMessageGenerator {
            return if (aiTool == COMMIT_MESSAGE_AI_TOOL_CLAUDE) {
ClaudeCodeCommitMessageGenerator(project, changeSummary)
            } else {
                OpenCodeCommitMessageGenerator(project, changeSummary)
            }
        }

        private fun buildPrompt(summary: String): String {
            val settings = AiTerminalToolsSettings.getInstance().getState()
            val basePrompt = AiTerminalToolsSettings.StateData.DEFAULT_COMMIT_MESSAGE_BASE_PROMPT
            val additionalPrompt = settings.resolvedCommitMessageAdditionalPrompt()
            return """
                根据以下变更内容生成提交信息。

                只输出提交文案，不要解释，不要分析过程，不要 Markdown 代码块。
                使用 "- " 分条。
                不要编造变更中不存在的内容。

                基础要求：
                $basePrompt

                附加要求：
                $additionalPrompt

                变更内容：
                $summary
            """.trimIndent()
        }

        private fun cleanupOutput(output: String): String {
            // 兼容 CLI 输出中的 ANSI 控制码、Markdown fence 和 Claude 引用前缀。
            val lines = output
                .replace(ANSI_PATTERN, "")
                .lineSequence()
                .map { it.trimEnd() }
                .filterNot { it.trim().equals("```", ignoreCase = true) }
                .filterNot { it.trimStart().startsWith("> ") }
                .toList()
            val trailingBulletLines = lines
                .asReversed()
                .dropWhile { it.isBlank() }
                .takeWhile { it.isBlank() || it.trimStart().startsWith("- ") }
                .asReversed()
                .filter { it.trimStart().startsWith("- ") }
            return (trailingBulletLines.ifEmpty { lines })
                .joinToString("\n")
                .trim()
        }

        private fun Change.toIncludedFile(project: Project): IncludedFile? {
            val filePath = afterRevision?.file ?: beforeRevision?.file ?: return null
            val status = when {
                beforeRevision == null -> "新增"
                afterRevision == null -> "删除"
                isMoved || isRenamed -> "重命名/移动"
                else -> "修改"
            }
            return filePath.toIncludedFile(project, status)
        }

        private fun FilePath.toIncludedFile(project: Project, status: String): IncludedFile {
            val absolutePath = ioFile.toPath().toAbsolutePath().normalize()
            val gitRoot = findGitRoot(absolutePath, project)
            val relativePath = try {
                gitRoot.relativize(absolutePath).toString().replace(File.separatorChar, '/')
            } catch (_: IllegalArgumentException) {
                absolutePath.toString().replace(File.separatorChar, '/')
            }
            return IncludedFile(gitRoot, absolutePath, relativePath, status)
        }

        private fun findGitRoot(path: Path, project: Project): Path {
            val start = if (Files.isDirectory(path)) path else path.parent
            var current = start
            while (current != null) {
                if (Files.exists(current.resolve(".git"))) {
                    return current
                }
                current = current.parent
            }
            return Path.of(project.basePath ?: ".").toAbsolutePath().normalize()
        }

        private fun runProcess(
            command: List<String>,
            workingDirectory: Path,
            timeoutSeconds: Long,
            indicator: ProgressIndicator? = null,
            timeoutMessage: String? = null,
            environment: Map<String, String> = emptyMap()
        ): ProcessResult {
            val process = try {
                ProcessBuilder(command).apply {
                    environment().putAll(environment)
                }
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(true)
                    .start()
            } catch (exception: Throwable) {
                throw CommitMessageGenerationException("无法启动命令 ${command.firstOrNull().orEmpty()}：${exception.message}")
            }
            try {
                // 无 stdin 的命令要主动关闭输入流，避免子进程等待输入。
                process.outputStream.close()
            } catch (_: Throwable) {
            }

            val output = StringBuilder()
            val readerThread = Thread {
                process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        output.appendLine(line)
                    }
                }
            }
            readerThread.isDaemon = true
            readerThread.start()

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            while (true) {
                if (indicator?.isCanceled == true) {
                    destroyProcessTree(process)
                    throw CommitMessageGenerationException("已取消")
                }
                if (process.waitFor(PROCESS_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
                    break
                }
                if (System.nanoTime() >= deadline) {
                    destroyProcessTree(process)
                    throw CommitMessageGenerationException(timeoutMessage ?: "命令执行超时：${command.firstOrNull().orEmpty()}")
                }
            }
            readerThread.join(TimeUnit.SECONDS.toMillis(2))
            return ProcessResult(process.exitValue(), output.toString())
        }

        private fun destroyProcessTree(process: Process) {
            process.descendants().forEach { descendant ->
                try {
                    descendant.destroyForcibly()
                } catch (_: Throwable) {
                }
            }
            process.destroyForcibly()
        }

        private fun runProcessWithStdin(
            command: List<String>,
            workingDirectory: Path,
            stdinContent: String,
            timeoutSeconds: Long,
            indicator: ProgressIndicator? = null,
            timeoutMessage: String? = null
        ): ProcessResult {
            val process = try {
                ProcessBuilder(command)
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(true)
                    .start()
            } catch (exception: Throwable) {
                throw CommitMessageGenerationException("无法启动命令 ${command.firstOrNull().orEmpty()}：${exception.message}")
            }
            try {
                process.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                    writer.write(stdinContent)
                    writer.flush()
                }
            } catch (_: Throwable) {
            }

            val output = StringBuilder()
            val readerThread = Thread {
                process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        output.appendLine(line)
                    }
                }
            }
            readerThread.isDaemon = true
            readerThread.start()

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            while (true) {
                if (indicator?.isCanceled == true) {
                    destroyProcessTree(process)
                    throw CommitMessageGenerationException("已取消")
                }
                if (process.waitFor(PROCESS_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
                    break
                }
                if (System.nanoTime() >= deadline) {
                    destroyProcessTree(process)
                    throw CommitMessageGenerationException(timeoutMessage ?: "命令执行超时：${command.firstOrNull().orEmpty()}")
                }
            }
            readerThread.join(TimeUnit.SECONDS.toMillis(2))
            return ProcessResult(process.exitValue(), output.toString())
        }

        private fun parseOpenCodeSessions(json: String): List<OpenCodeSession> {
            // 不引入 JSON 库，按 OpenCode session list 的扁平对象输出做轻量解析。
            return Regex("""\{[^{}]*\}""")
                .findAll(json)
                .mapNotNull { match ->
                    val item = match.value
                    val id = extractJsonStringField(item, "id") ?: return@mapNotNull null
                    val title = extractJsonStringField(item, "title") ?: return@mapNotNull null
                    val directory = extractJsonStringField(item, "directory") ?: return@mapNotNull null
                    OpenCodeSession(id, title, directory)
                }
                .toList()
        }

        private fun extractJsonStringField(jsonObject: String, field: String): String? {
            val pattern = Regex("\"${Regex.escape(field)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            return pattern.find(jsonObject)?.groupValues?.getOrNull(1)?.let(::unescapeJsonString)
        }

        private fun unescapeJsonString(value: String): String {
            val result = StringBuilder(value.length)
            var index = 0
            while (index < value.length) {
                val char = value[index]
                if (char != '\\' || index + 1 >= value.length) {
                    result.append(char)
                    index++
                    continue
                }

                when (val escaped = value[index + 1]) {
                    '"', '\\', '/' -> result.append(escaped)
                    'b' -> result.append('\b')
                    'f' -> result.append('\u000C')
                    'n' -> result.append('\n')
                    'r' -> result.append('\r')
                    't' -> result.append('\t')
                    'u' -> {
                        val hexStart = index + 2
                        val hexEnd = hexStart + 4
                        if (hexEnd <= value.length) {
                            val codePoint = value.substring(hexStart, hexEnd).toIntOrNull(16)
                            if (codePoint != null) {
                                result.append(codePoint.toChar())
                                index += 6
                                continue
                            }
                        }
                        result.append("\\u")
                    }
                    else -> result.append(escaped)
                }
                index += 2
            }
            return result.toString()
        }

        private fun pathsEqual(directory: String, basePath: Path): Boolean {
            return try {
                val sessionPath = Path.of(directory).toAbsolutePath().normalize().toString()
                val expectedPath = basePath.toAbsolutePath().normalize().toString()
                sessionPath.equals(expectedPath, ignoreCase = SystemInfo.isWindows)
            } catch (_: Throwable) {
                directory.equals(basePath.toString(), ignoreCase = SystemInfo.isWindows)
            }
        }

        private fun createOpenCodeConfigHome(fullPrompt: String): Path {
            // 只开启模型生成所需能力，禁止读写/命令工具参与提交文案生成。
            val configHome = Files.createTempDirectory("opencode-commit-message-config")
            val configDir = configHome.resolve("opencode")
            Files.createDirectories(configDir)
            Files.writeString(
                configDir.resolve("opencode.json"),
                commitMessageAgentConfig(fullPrompt),
                StandardCharsets.UTF_8
            )
            return configHome
        }

        private fun commitMessageAgentConfig(fullPrompt: String): String {
            return """
                {
                  "agent": {
                    "$COMMIT_MESSAGE_AGENT": {
                      "description": "Commit message generator",
                      "mode": "primary",
                      "prompt": ${jsonString(fullPrompt)},
                      "tools": {
                        "invalid": false,
                        "skill": false,
                        "question": false,
                        "bash": false,
                        "read": false,
                        "glob": false,
                        "grep": false,
                        "edit": false,
                        "write": false,
                        "task": false,
                        "webfetch": false,
                        "websearch": false,
                        "todowrite": false
                      }
                    }
                  }
                }
            """.trimIndent()
        }

        private fun jsonString(value: String): String {
            return buildString {
                append('"')
                value.forEach { char ->
                    when (char) {
                        '"' -> append("\\\"")
                        '\\' -> append("\\\\")
                        '\b' -> append("\\b")
                        '\u000C' -> append("\\f")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> append(char)
                    }
                }
                append('"')
            }
        }

        private fun opencodeCommand(): String {
            return if (SystemInfo.isWindows) "opencode.cmd" else "opencode"
        }

        private fun claudeCommand(): String {
            if (!SystemInfo.isWindows) return "claude"
            val pathValue = System.getenv("PATH").orEmpty()
            val commandNames = listOf("claude.cmd", "claude.exe", "claude.bat", "claude")
            pathValue.split(File.pathSeparatorChar)
                .map { it.trim().trim('"') }
                .filter { it.isNotEmpty() }
                .forEach { pathEntry ->
                    commandNames.forEach { commandName ->
                        val commandPath = Path.of(pathEntry, commandName)
                        if (Files.isRegularFile(commandPath)) {
                            return commandPath.toAbsolutePath().normalize().toString()
                        }
                    }
                }
            return "claude"
        }
    }
}
