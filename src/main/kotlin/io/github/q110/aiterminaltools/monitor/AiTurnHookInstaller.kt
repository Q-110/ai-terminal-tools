// Hook 安装器 — 生成 Claude Hook 配置、Hook 脚本和 Launcher 脚本
package io.github.q110.aiterminaltools.monitor

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * 负责为 Claude Code 生成：
 * 1. `.claude/settings.local.json` — hook 配置
 * 2. `.idea/ai-terminal-tools/claude-hook.cmd` — Windows cmd wrapper
 * 3. `.idea/ai-terminal-tools/claude-hook.ps1` — PowerShell hook 实现
 * 4. `.idea/ai-terminal-tools/claude-hook.sh` — macOS/Linux hook 实现
 * 5. `.idea/ai-terminal-tools/run-claude-<tabId>.cmd` — Windows launcher
 * 6. `.idea/ai-terminal-tools/run-claude-<tabId>.sh` — macOS/Linux launcher
 */
class AiTurnHookInstaller(
    private val project: Project
) {
    private val log = Logger.getInstance(AiTurnHookInstaller::class.java)

    /**
     * 安装 Claude Code hooks 并生成 launcher 脚本。
     * 返回 launcher 脚本的文件名（不含路径，相对于 .idea/ai-terminal-tools/）。
     */
    fun installClaudeHooks(tabId: String, token: String, port: Int): LauncherPaths {
        val projectBasePath = project.basePath
            ?: throw IllegalStateException("Project base path is null")
        val basePath = Path.of(projectBasePath)

        // 1. 确保 .idea/ai-terminal-tools/ 目录存在
        val toolsDir = basePath.resolve(".idea").resolve("ai-terminal-tools")
        Files.createDirectories(toolsDir)

        // 2. 生成 hook 脚本
        writeHookScripts(toolsDir)

        // 3. 生成/合并 .claude/settings.local.json
        writeClaudeSettings(basePath, toolsDir)

        // 4. 生成 launcher 脚本
        return writeLauncherScripts(toolsDir, tabId, token, port)
    }

    private fun writeHookScripts(toolsDir: Path) {
        // claude-hook.cmd — Windows cmd wrapper，委托给 PowerShell
        val hookCmd = toolsDir.resolve("claude-hook.cmd")
        Files.writeString(hookCmd, buildString {
            appendLine("@echo off")
            appendLine("set EVENT_TYPE=%1")
            appendLine("powershell -NoProfile -ExecutionPolicy Bypass -File \"%~dp0claude-hook.ps1\" \"%EVENT_TYPE%\"")
        })

        // claude-hook.ps1 — PowerShell 实现
        val hookPs1 = toolsDir.resolve("claude-hook.ps1")
        Files.writeString(hookPs1, buildString {
            appendLine("param(")
            appendLine("  [string]\$EventType")
            appendLine(")")
            appendLine()
            appendLine("\$raw = [Console]::In.ReadToEnd()")
            appendLine()
            appendLine("# 从原始 JSON 中提取文件路径")
            appendLine("\$paths = @()")
            appendLine("if (\$raw -match '\"file_path\"\\s*:\\s*\"([^\"]+)\"') {")
            appendLine("  \$paths += \$Matches[1]")
            appendLine("}")
            appendLine("if (\$raw -match '\"path\"\\s*:\\s*\"([^\"]+)\"') {")
            appendLine("  \$paths += \$Matches[1]")
            appendLine("}")
            appendLine("if (\$raw -match '\"file\"\\s*:\\s*\"([^\"]+)\"') {")
            appendLine("  \$paths += \$Matches[1]")
            appendLine("}")
            appendLine()
            appendLine("\$pathsJson = '[]'")
            appendLine("if (\$paths.Count -gt 0) {")
            appendLine("  \$escapedPaths = \$paths | ForEach-Object { '\"' + \$_.Replace('\\', '\\\\').Replace('\"', '\\\"') + '\"' }")
            appendLine("  \$pathsJson = '[' + (\$escapedPaths -join ',') + ']'")
            appendLine("}")
            appendLine()
            appendLine("\$body = '{' +")
            appendLine("  '\"source\":\"claude\",' +")
            appendLine("  '\"type\":\"' + \$EventType + '\",' +")
            appendLine("  '\"tabId\":\"' + \$env:AITT_TAB_ID + '\",' +")
            appendLine("  '\"paths\":' + \$pathsJson +")
            appendLine("  '}'")
            appendLine()
            appendLine("try {")
            appendLine("  Invoke-RestMethod ``")
            appendLine("    -Uri \"http://127.0.0.1:\$env:AITT_PORT/event\" ``")
            appendLine("    -Method POST ``")
            appendLine("    -Headers @{ 'X-AITT-Token' = \$env:AITT_TOKEN } ``")
            appendLine("    -Body \$body ``")
            appendLine("    -ContentType 'application/json' | Out-Null")
            appendLine("} catch {")
            appendLine("  # Silently ignore errors to avoid interrupting Claude Code")
            appendLine("}")
        })

        // claude-hook.sh — macOS/Linux bash 实现
        val hookSh = toolsDir.resolve("claude-hook.sh")
        Files.writeString(hookSh, buildString {
            appendLine("#!/usr/bin/env bash")
            appendLine("EVENT_TYPE=\"\$1\"")
            appendLine("RAW=\"\$(cat)\"")
            appendLine()
            appendLine("# Extract file paths from raw JSON (portable sed, works on macOS and Linux)")
            appendLine("PATHS=\"[]\"")
            appendLine("FILE_PATH=\$(echo \"\$RAW\" | sed -n 's/.*\"file_path\"[[:space:]]*:[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p' | head -1)")
            appendLine("if [ -z \"\$FILE_PATH\" ]; then")
            appendLine("  FILE_PATH=\$(echo \"\$RAW\" | sed -n 's/.*\"path\"[[:space:]]*:[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p' | head -1)")
            appendLine("fi")
            appendLine("if [ -z \"\$FILE_PATH\" ]; then")
            appendLine("  FILE_PATH=\$(echo \"\$RAW\" | sed -n 's/.*\"file\"[[:space:]]*:[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p' | head -1)")
            appendLine("fi")
            appendLine("if [ -n \"\$FILE_PATH\" ]; then")
            appendLine("  PATHS=\"[\\\"\$FILE_PATH\\\"]\"")
            appendLine("fi")
            appendLine()
            appendLine("curl -sS \\")
            appendLine("  -X POST \"http://127.0.0.1:\${AITT_PORT}/event\" \\")
            appendLine("  -H \"content-type: application/json\" \\")
            appendLine("  -H \"x-aitt-token: \${AITT_TOKEN}\" \\")
            appendLine("  --data \"{\\\"source\\\":\\\"claude\\\",\\\"type\\\":\\\"\${EVENT_TYPE}\\\",\\\"tabId\\\":\\\"\${AITT_TAB_ID}\\\",\\\"paths\\\":\${PATHS}}\" \\")
            appendLine("  2>/dev/null || true")
        })

        // 设置 sh 可执行权限（非 Windows）
        setExecutableIfPosix(hookSh)
    }

    private fun writeClaudeSettings(projectBasePath: Path, toolsDir: Path) {
        val claudeDir = projectBasePath.resolve(".claude")
        Files.createDirectories(claudeDir)

        val settingsFile = claudeDir.resolve("settings.local.json")

        // 使用 ${CLAUDE_PROJECT_DIR} + 正斜杠路径，与官方文档示例一致。
        //    - ${CLAUDE_PROJECT_DIR} 由 Claude Code 在执行 hook 时展开，始终指向项目根目录
        //    - 正斜杠在 Git Bash / WSL / Linux / macOS 中通用
        //    - 始终使用 .sh 脚本（Windows 上 Claude Code 使用 Git Bash 的 /usr/bin/bash）
        val hookCommand = "\${CLAUDE_PROJECT_DIR}/.idea/ai-terminal-tools/claude-hook.sh"

        // 读取已有配置并合并
        val existingContent = if (Files.exists(settingsFile)) {
            try {
                Files.readString(settingsFile)
            } catch (exception: Throwable) {
                throw IllegalStateException("无法读取现有 Claude settings.local.json，已停止写入。", exception)
            }
        } else {
            null
        }

        val newSettings = generateClaudeSettingsJson(hookCommand, existingContent)
        Files.writeString(settingsFile, newSettings)
        log.info("Written Claude settings to $settingsFile")
    }

    /**
     * 生成 Claude settings.local.json 内容。
     * 如果已有配置存在，完整保留原字段和 hooks，仅更新本插件自己的 hook。
     */
    private fun generateClaudeSettingsJson(hookCommand: String, existingContent: String?): String {
        // 结构化解析已有配置，解析失败时中止安装，避免覆盖用户原文件。
        val root = if (existingContent == null) {
            JsonObject()
        } else {
            val parsed = try {
                JsonParser.parseString(existingContent)
            } catch (exception: Throwable) {
                throw IllegalStateException("现有 Claude settings.local.json 不是合法 JSON，已停止写入。", exception)
            }
            if (!parsed.isJsonObject) {
                throw IllegalStateException("现有 Claude settings.local.json 顶层必须是 JSON 对象，已停止写入。")
            }
            parsed.asJsonObject
        }

        // 保留已有 hooks 对象中的所有事件；结构不符合 Claude 配置格式时不做破坏性替换。
        val existingHooks = root.get("hooks")
        val hooks = when {
            existingHooks == null -> JsonObject().also { root.add("hooks", it) }
            existingHooks.isJsonObject -> existingHooks.asJsonObject
            else -> throw IllegalStateException("现有 Claude settings.local.json 的 hooks 必须是 JSON 对象，已停止写入。")
        }

        val hookSpecs = listOf(
            Triple("UserPromptSubmit", null, "turn_start"),
            Triple("PreToolUse", "Edit|Write|MultiEdit|NotebookEdit", "before_write"),
            Triple("PostToolUse", "Edit|Write|MultiEdit|NotebookEdit", "file_changed"),
            Triple("Stop", null, "turn_end"),
            Triple("StopFailure", null, "turn_end_failed")
        )

        for ((eventName, matcher, eventType) in hookSpecs) {
            val eventElement = hooks.get(eventName)
            val eventGroups = when {
                eventElement == null -> JsonArray()
                eventElement.isJsonArray -> eventElement.asJsonArray
                else -> throw IllegalStateException(
                    "现有 Claude settings.local.json 的 hooks.$eventName 必须是数组，已停止写入。"
                )
            }
            val pluginCommand = "$hookCommand $eventType"
            val mergedGroups = JsonArray()

            // 仅移除旧的同名插件命令，原有 hook 组、matcher 和其他 hook 条目全部保留。
            for (groupElement in eventGroups) {
                if (!groupElement.isJsonObject) {
                    mergedGroups.add(groupElement)
                    continue
                }
                val group = groupElement.asJsonObject.deepCopy()
                val groupHooksElement = group.get("hooks")
                if (groupHooksElement == null || !groupHooksElement.isJsonArray) {
                    mergedGroups.add(group)
                    continue
                }
                val retainedHooks = JsonArray()
                var removedCurrentPluginHook = false
                for (hookElement in groupHooksElement.asJsonArray) {
                    val commandElement = if (hookElement.isJsonObject) {
                        hookElement.asJsonObject.get("command")
                    } else {
                        null
                    }
                    val isCurrentPluginHook = commandElement != null &&
                        commandElement.isJsonPrimitive &&
                        commandElement.asString == pluginCommand
                    if (isCurrentPluginHook) {
                        removedCurrentPluginHook = true
                    } else {
                        retainedHooks.add(hookElement)
                    }
                }
                if (!removedCurrentPluginHook) {
                    mergedGroups.add(group)
                } else if (retainedHooks.size() > 0) {
                    group.add("hooks", retainedHooks)
                    mergedGroups.add(group)
                }
            }

            // 在保留内容之后追加当前插件 hook，确保重复启动不会重复注册。
            val pluginGroup = JsonObject()
            if (matcher != null) {
                pluginGroup.addProperty("matcher", matcher)
            }
            val pluginHooks = JsonArray()
            pluginHooks.add(JsonObject().apply {
                addProperty("type", "command")
                addProperty("command", pluginCommand)
            })
            pluginGroup.add("hooks", pluginHooks)
            mergedGroups.add(pluginGroup)
            hooks.add(eventName, mergedGroups)
        }

        return GsonBuilder().setPrettyPrinting().create().toJson(root) + System.lineSeparator()
    }

    private fun writeLauncherScripts(
        toolsDir: Path,
        tabId: String,
        token: String,
        port: Int
    ): LauncherPaths {
        val cmdFile = toolsDir.resolve("run-claude-$tabId.cmd")
        val shFile = toolsDir.resolve("run-claude-$tabId.sh")

        // Windows cmd launcher
        Files.writeString(cmdFile, buildString {
            appendLine("@echo off")
            appendLine("set AITT_PORT=$port")
            appendLine("set AITT_TOKEN=$token")
            appendLine("set AITT_TAB_ID=$tabId")
            appendLine("set AITT_TOOL=claude")
            appendLine("claude")
        })

        // macOS/Linux bash launcher
        Files.writeString(shFile, buildString {
            appendLine("#!/usr/bin/env bash")
            appendLine("export AITT_PORT=\"$port\"")
            appendLine("export AITT_TOKEN=\"$token\"")
            appendLine("export AITT_TAB_ID=\"$tabId\"")
            appendLine("export AITT_TOOL=\"claude\"")
            appendLine("exec claude")
        })

        setExecutableIfPosix(shFile)

        return LauncherPaths(
            cmdPath = cmdFile,
            shPath = shFile
        )
    }

    /** 清理指定 tabId 的 launcher 脚本 */
    fun cleanupLauncherScripts(tabId: String) {
        val projectBasePath = project.basePath ?: return
        val toolsDir = Path.of(projectBasePath).resolve(".idea").resolve("ai-terminal-tools")
        try {
            Files.deleteIfExists(toolsDir.resolve("run-claude-$tabId.cmd"))
            Files.deleteIfExists(toolsDir.resolve("run-claude-$tabId.sh"))
        } catch (exception: Throwable) {
            log.warn("Failed to cleanup launcher scripts for tab $tabId", exception)
        }
    }

    private fun setExecutableIfPosix(path: Path) {
        try {
            val perms = Files.getPosixFilePermissions(path).toMutableSet()
            perms.add(PosixFilePermission.OWNER_EXECUTE)
            perms.add(PosixFilePermission.GROUP_EXECUTE)
            Files.setPosixFilePermissions(path, perms)
        } catch (_: UnsupportedOperationException) {
            // Windows 不支持 POSIX 权限
        } catch (exception: Throwable) {
            log.warn("Failed to set executable permission on $path", exception)
        }
    }

    private fun isWindows(): Boolean {
        return System.getProperty("os.name", "").lowercase().contains("win")
    }

    data class LauncherPaths(
        val cmdPath: Path,
        val shPath: Path
    )
}
