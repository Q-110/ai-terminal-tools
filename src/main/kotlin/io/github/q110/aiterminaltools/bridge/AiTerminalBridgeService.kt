// AI Terminal 桥接核心服务 — 直接向当前激活的终端输入区写入内容
package io.github.q110.aiterminaltools.bridge

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.content.Content
import com.intellij.terminal.JBTerminalWidget
import com.intellij.terminal.ui.TerminalWidget
import io.github.q110.aiterminaltools.filter.displayPath
import io.github.q110.aiterminaltools.monitor.AiTerminalTabContext
import io.github.q110.aiterminaltools.monitor.AiTool
import io.github.q110.aiterminaltools.monitor.AiTurnEventServer
import io.github.q110.aiterminaltools.monitor.AiTurnHookInstaller
import io.github.q110.aiterminaltools.monitor.AiTurnOpenCodeInstaller
import io.github.q110.aiterminaltools.monitor.AiTurnMonitorService
import org.jetbrains.plugins.terminal.ShellStartupOptions
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.io.IOException
import java.nio.file.Path
import java.security.SecureRandom
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Timer

@Service(Service.Level.PROJECT)
class AiTerminalBridgeService(
    private val project: Project
) : Disposable {
    /** 新版终端辅助类，仅在 2025.3+ IDE 中可加载，低版本为 null */
    private val frontendHelper: FrontendTerminalHelper? = try {
        FrontendTerminalHelper(project)
    } catch (_: Throwable) {
        null
    }

    private val legacyReworkedTerminalHelper = LegacyReworkedTerminalHelper(project)
    private val log = Logger.getInstance(AiTerminalBridgeService::class.java)
    private val openCodeTerminalStartInProgress = AtomicBoolean(false)
    private val claudeCodeTerminalStartInProgress = AtomicBoolean(false)
    private val activeAiTerminalContexts = ConcurrentHashMap<String, AiTerminalTabContext>()
    private val aiFrontendTerminals = IdentityHashMap<Any, AiTerminalRegistration>()
    private val aiLegacyReworkedTerminals = IdentityHashMap<TerminalWidget, AiTerminalRegistration>()
    private val aiClassicTerminals = IdentityHashMap<TerminalWidget, AiTerminalRegistration>()

    /** 直接写入当前激活的 AI 终端输入区 */
    fun sendDirectInput(payload: String, dataContext: DataContext, settleAtLineEnd: Boolean = false): BridgeResult {
        val terminal = resolveTargetTerminal(dataContext)
            ?: return BridgeResult.Error(NO_ACTIVE_TERMINAL_MESSAGE)

        return injectDirectInput(terminal, payload, settleAtLineEnd)
    }

    /** 使用 bracketed paste 直接写入多行内容，避免换行被终端当作提交处理 */
    fun sendDirectPaste(payload: String, dataContext: DataContext): BridgeResult {
        val terminal = resolveTargetTerminal(dataContext)
            ?: return BridgeResult.Error(NO_ACTIVE_TERMINAL_MESSAGE)

        return injectDirectInput(terminal, bracketedPaste(payload), settleAtLineEnd = false)
    }

    /** 拖拽路径合并为一次输入，避免多次触发造成卡顿 */
    fun sendDroppedPaths(files: List<VirtualFile>): BridgeResult {
        val payload = files.filter { it.isValid }
            .joinToString(separator = " ") { pathPayload(it) }
        if (payload.isBlank()) {
            return BridgeResult.Error("没有找到要发送的文件或文件夹。")
        }

        val terminal = selectedTerminal()
            ?: return BridgeResult.Error(NO_ACTIVE_TERMINAL_MESSAGE)
        return injectDirectInput(terminal, payload, settleAtLineEnd = true)
    }

    fun isSelectedTerminalRecordedAiTerminal(): Boolean {
        val terminal = selectedTerminal()?.takeIf { isUsable(it) } ?: return false
        return isRecordedAiTerminal(terminal)
    }

    fun isRecordedAiTerminalContent(content: Content): Boolean {
        val frontendHelper = frontendHelper
        if (frontendHelper != null && aiFrontendTerminals.keys.any { isFrontendContentOf(frontendHelper, it, content) }) {
            return true
        }

        val widget = TerminalToolWindowManager.findWidgetByContent(content)
        return widget != null && (aiLegacyReworkedTerminals.containsKey(widget) || aiClassicTerminals.containsKey(widget))
    }

    /** 终端 Content 被真正移除时，释放其监控上下文、鉴权 token 和专用 launcher。 */
    internal fun unregisterAiTerminalContent(content: Content) {
        val contexts = linkedMapOf<String, AiTerminalTabContext>()
        frontendHelper?.let { helper ->
            aiFrontendTerminals
                .filterKeys { tab -> isFrontendContentOf(helper, tab, content) }
                .values
                .forEach { registration -> contexts[registration.context.tabId] = registration.context }
        }

        val widget = TerminalToolWindowManager.findWidgetByContent(content)
        if (widget != null) {
            aiLegacyReworkedTerminals[widget]?.let { contexts[it.context.tabId] = it.context }
            aiClassicTerminals[widget]?.let { contexts[it.context.tabId] = it.context }
        }

        // Content 与 tabId 的关联只在桥接服务维护，关闭时统一执行一次完整释放。
        contexts.values.forEach { context -> cleanupAiTerminalLifecycle(context, showDiff = true) }
    }

    /** 创建新的 OpenCode terminal，并启动 opencode */
    fun startOpenCodeTerminal(): BridgeResult {
        if (!openCodeTerminalStartInProgress.compareAndSet(false, true)) {
            return BridgeResult.Scheduled
        }
        scheduleOpenCodeTerminalStart()
        return BridgeResult.Scheduled
    }

    /** 创建新的 Claude Code terminal，并启动 claude */
    fun startClaudeCodeTerminal(): BridgeResult {
        if (!claudeCodeTerminalStartInProgress.compareAndSet(false, true)) {
            return BridgeResult.Scheduled
        }
        scheduleClaudeCodeTerminalStart()
        return BridgeResult.Scheduled
    }

    private fun scheduleOpenCodeTerminalStart() {
        // OpenCode：注入监控上下文，使用 launcher 脚本启动
        val tabId = UUID.randomUUID().toString()
        val token = generateSecureToken()

        val port = try {
            project.service<AiTurnEventServer>().ensureStarted()
        } catch (exception: Throwable) {
            log.error("Failed to start AiTurnEventServer", exception)
            openCodeTerminalStartInProgress.set(false)
            notify(project, "启动 AI Turn Event Server 失败：${exception.message}", NotificationType.WARNING)
            return
        }

        val launcherCommand = try {
            val installer = AiTurnOpenCodeInstaller(project)
            val launcherPaths = installer.installOpenCodePlugin(tabId, token, port)
            if (isWindows()) {
                launcherPaths.cmdPath.toString()
            } else {
                launcherPaths.shPath.toString()
            }
        } catch (exception: Throwable) {
            log.error("Failed to install OpenCode plugin", exception)
            AiTurnOpenCodeInstaller(project).cleanupLauncherScripts(tabId)
            openCodeTerminalStartInProgress.set(false)
            notify(project, "安装 OpenCode Plugin 失败：${exception.message}", NotificationType.WARNING)
            return
        }

        val workingDirectory = terminalWorkingDirectory()
        val tabContext = AiTerminalTabContext(
            tabId = tabId,
            token = token,
            tool = AiTool.OPENCODE,
            workingDirectory = Path.of(workingDirectory),
            createdAtMillis = System.currentTimeMillis()
        )
        activeAiTerminalContexts[tabId] = tabContext
        try {
            project.service<AiTurnMonitorService>().registerTab(tabContext)
        } catch (exception: Throwable) {
            log.error("Failed to register OpenCode terminal lifecycle", exception)
            cleanupAiTerminalLifecycle(tabContext, showDiff = false)
            openCodeTerminalStartInProgress.set(false)
            notify(project, "注册 OpenCode 终端状态失败：${exception.message}", NotificationType.WARNING)
            return
        }

        try {
            scheduleTerminalStart(
                tabName = nextTerminalTabName(OPEN_CODE_TAB_NAME),
                command = launcherCommand,
                toolName = OPEN_CODE_TAB_NAME,
                inProgress = openCodeTerminalStartInProgress,
                tabContext = tabContext
            )
        } catch (exception: Throwable) {
            log.error("Failed to schedule OpenCode terminal startup", exception)
            cleanupAiTerminalLifecycle(tabContext, showDiff = false)
            openCodeTerminalStartInProgress.set(false)
            notify(project, "调度 OpenCode 终端启动失败：${exception.message}", NotificationType.WARNING)
        }
    }

    private fun scheduleClaudeCodeTerminalStart() {
        // Claude Code：注入监控上下文，使用 launcher 脚本启动
        val tabId = UUID.randomUUID().toString()
        val token = generateSecureToken()

        val port = try {
            project.service<AiTurnEventServer>().ensureStarted()
        } catch (exception: Throwable) {
            log.error("Failed to start AiTurnEventServer", exception)
            claudeCodeTerminalStartInProgress.set(false)
            notify(project, "启动 AI Turn Event Server 失败：${exception.message}", NotificationType.WARNING)
            return
        }

        val launcherCommand = try {
            val installer = AiTurnHookInstaller(project)
            val launcherPaths = installer.installClaudeHooks(tabId, token, port)
            if (isWindows()) {
                launcherPaths.cmdPath.toString()
            } else {
                launcherPaths.shPath.toString()
            }
        } catch (exception: Throwable) {
            log.error("Failed to install Claude hooks", exception)
            AiTurnHookInstaller(project).cleanupLauncherScripts(tabId)
            claudeCodeTerminalStartInProgress.set(false)
            notify(project, "安装 Claude Code Hooks 失败：${exception.message}", NotificationType.WARNING)
            return
        }

        val workingDirectory = terminalWorkingDirectory()
        val tabContext = AiTerminalTabContext(
            tabId = tabId,
            token = token,
            tool = AiTool.CLAUDE_CODE,
            workingDirectory = Path.of(workingDirectory),
            createdAtMillis = System.currentTimeMillis()
        )
        activeAiTerminalContexts[tabId] = tabContext
        try {
            project.service<AiTurnMonitorService>().registerTab(tabContext)
        } catch (exception: Throwable) {
            log.error("Failed to register Claude Code terminal lifecycle", exception)
            cleanupAiTerminalLifecycle(tabContext, showDiff = false)
            claudeCodeTerminalStartInProgress.set(false)
            notify(project, "注册 Claude Code 终端状态失败：${exception.message}", NotificationType.WARNING)
            return
        }

        try {
            scheduleTerminalStart(
                tabName = nextTerminalTabName(CLAUDE_CODE_TAB_NAME),
                command = launcherCommand,
                toolName = CLAUDE_CODE_TAB_NAME,
                inProgress = claudeCodeTerminalStartInProgress,
                tabContext = tabContext
            )
        } catch (exception: Throwable) {
            log.error("Failed to schedule Claude Code terminal startup", exception)
            cleanupAiTerminalLifecycle(tabContext, showDiff = false)
            claudeCodeTerminalStartInProgress.set(false)
            notify(project, "调度 Claude Code 终端启动失败：${exception.message}", NotificationType.WARNING)
        }
    }

    /** 将同一 tab 上下文贯穿三条终端启动路径，任何最终失败都进入统一释放流程。 */
    private fun scheduleTerminalStart(
        tabName: String,
        command: String,
        toolName: String,
        inProgress: AtomicBoolean,
        tabContext: AiTerminalTabContext
    ) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) {
                cleanupAiTerminalLifecycle(tabContext, showDiff = false)
                inProgress.set(false)
                return@invokeLater
            }

            val terminalToolWindowManager = TerminalToolWindowManager.getInstance(project)
            val toolWindow = terminalToolWindow(terminalToolWindowManager)
            if (toolWindow == null) {
                cleanupAiTerminalLifecycle(tabContext, showDiff = false)
                inProgress.set(false)
                notify(project, "Terminal tool window was not found.", NotificationType.WARNING)
                return@invokeLater
            }

            try {
                toolWindow.activate(Runnable {
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) {
                            cleanupAiTerminalLifecycle(tabContext, showDiff = false)
                            inProgress.set(false)
                            return@invokeLater
                        }

                        try {
                            val workingDirectory = terminalWorkingDirectory()
                            val result = startFrontendTerminal(tabName, workingDirectory, command, toolName, tabContext)
                                ?: if (shouldSkipLegacyReworkedTerminal(toolName)) {
                                    startClassicTerminal(tabName, workingDirectory, command, toolName, tabContext)
                                } else {
                                    startLegacyReworkedTerminal(tabName, workingDirectory, command, toolName, tabContext)
                                        ?: run {
                                            notifyLegacyReworkedFallbackIfNeeded(toolName)
                                            startClassicTerminal(tabName, workingDirectory, command, toolName, tabContext)
                                        }
                                }
                            if (result is BridgeResult.Error) {
                                cleanupAiTerminalLifecycle(tabContext, showDiff = false)
                                notify(project, result.message, NotificationType.WARNING)
                            }
                        } catch (exception: Throwable) {
                            log.error("Failed to start $toolName terminal", exception)
                            cleanupAiTerminalLifecycle(tabContext, showDiff = false)
                            notify(project, "启动 $toolName 失败：${exception.message}", NotificationType.WARNING)
                        } finally {
                            inProgress.set(false)
                        }
                    }
                }, true, true)
            } catch (exception: Throwable) {
                log.error("Failed to activate Terminal tool window", exception)
                cleanupAiTerminalLifecycle(tabContext, showDiff = false)
                inProgress.set(false)
                notify(project, "激活 Terminal 工具窗口失败：${exception.message}", NotificationType.WARNING)
            }
        }
    }

    private fun shouldSkipLegacyReworkedTerminal(toolName: String): Boolean {
        return toolName == OPEN_CODE_TAB_NAME && ideBaselineVersion() in 251..252
    }

    private fun startFrontendTerminal(
        tabName: String,
        workingDirectory: String,
        command: String,
        toolName: String,
        tabContext: AiTerminalTabContext
    ): BridgeResult? {
        val helper = frontendHelper ?: return null
        val tab = try {
            helper.createAiTerminal(tabName, workingDirectory)
        } catch (exception: Throwable) {
            notify(project, "新版终端不可用，改用 Classic Terminal：${exception.message}", NotificationType.WARNING)
            return null
        }

        if (!trackAiTerminal(TargetTerminal.Frontend(tab), tabContext)) {
            return BridgeResult.Error("$toolName 终端启动已取消。")
        }

        return try {
            helper.runCommand(
                tab,
                command,
                "已启动 $toolName 终端",
                "启动 $toolName 失败",
                onCommandFailed = {
                    cleanupAiTerminalLifecycle(tabContext, showDiff = false)
                }
            )
        } catch (exception: Throwable) {
            aiFrontendTerminals.remove(tab)
            notify(project, "新版终端不可用，改用 Classic Terminal：${exception.message}", NotificationType.WARNING)
            null
        }
    }

    private fun startLegacyReworkedTerminal(
        tabName: String,
        workingDirectory: String,
        command: String,
        toolName: String,
        tabContext: AiTerminalTabContext
    ): BridgeResult? {
        if (activeAiTerminalContexts[tabContext.tabId] !== tabContext) {
            return BridgeResult.Error("$toolName 终端启动已取消。")
        }

        return try {
            val widget = legacyReworkedTerminalHelper.createAiTerminal(tabName, workingDirectory)
                ?: return null
            if (!trackAiTerminal(TargetTerminal.LegacyReworked(widget), tabContext)) {
                return BridgeResult.Error("$toolName 终端启动已取消。")
            }
            legacyReworkedTerminalHelper.runCommand(
                widget = widget,
                command = command,
                successMessage = "已启动 $toolName 终端",
                failurePrefix = "运行 $command 失败",
                onCommandFailed = {
                    // Reworked 失败时只解除该终端关联，保留同一上下文供 Classic 回退使用。
                    aiLegacyReworkedTerminals.remove(widget)
                    val result = startClassicTerminal(tabName, workingDirectory, command, toolName, tabContext)
                    if (result is BridgeResult.Error) {
                        cleanupAiTerminalLifecycle(tabContext, showDiff = false)
                        notify(project, result.message, NotificationType.WARNING)
                    }
                }
            )
        } catch (exception: Throwable) {
            aiLegacyReworkedTerminals.entries.removeIf { it.value.context.tabId == tabContext.tabId }
            notify(project, "Reworked Terminal 不可用，改用 Classic Terminal：${exception.message}", NotificationType.WARNING)
            null
        }
    }

    private fun startClassicTerminal(
        tabName: String,
        workingDirectory: String,
        command: String,
        toolName: String,
        tabContext: AiTerminalTabContext
    ): BridgeResult {
        if (activeAiTerminalContexts[tabContext.tabId] !== tabContext) {
            return BridgeResult.Error("$toolName 终端启动已取消。")
        }

        val terminalToolWindowManager = TerminalToolWindowManager.getInstance(project)
        val toolWindow = terminalToolWindow(terminalToolWindowManager)
            ?: return BridgeResult.Error("Terminal tool window was not found.")
        val startupOptions = ShellStartupOptions.Builder()
            .workingDirectory(workingDirectory)
            .build()
        val startupDisposable: Disposable = Disposer.newDisposable("$toolName Terminal startup")
        val widget = try {
            terminalToolWindowManager.terminalRunner.startShellTerminalWidget(startupDisposable, startupOptions, true)
        } catch (exception: Throwable) {
            Disposer.dispose(startupDisposable)
            return BridgeResult.Error("Failed to create $toolName Terminal: ${exception.message}")
        }

        val content = try {
            terminalToolWindowManager.newTab(toolWindow, widget)
        } catch (exception: Throwable) {
            Disposer.dispose(startupDisposable)
            return BridgeResult.Error("Failed to create $toolName Terminal tab: ${exception.message}")
        }
        if (!trackAiTerminal(TargetTerminal.Classic(widget), tabContext, startupDisposable)) {
            return BridgeResult.Error("$toolName 终端启动已取消。")
        }
        content.displayName = tabName

        return try {
            toolWindow.activate(Runnable {
                try {
                    ShellTerminalWidget.toShellJediTermWidgetOrThrow(widget).executeCommand(command)
                    notify(project, "已启动 $toolName 终端", NotificationType.INFORMATION)
                } catch (exception: Throwable) {
                    cleanupAiTerminalLifecycle(tabContext, showDiff = false)
                    notify(project, "Failed to run $command: ${exception.message}", NotificationType.WARNING)
                }
            }, true, true)
            BridgeResult.Scheduled
        } catch (exception: Throwable) {
            BridgeResult.Error("Failed to activate $toolName Terminal: ${exception.message}")
        }
    }

    private fun pathPayload(file: VirtualFile): String {
        return "@${displayPath(project, file)}"
    }

    private fun bracketedPaste(payload: String): String {
        return BRACKETED_PASTE_START + payload + BRACKETED_PASTE_END
    }

    private fun terminalWorkingDirectory(): String {
        return project.basePath ?: System.getProperty("user.home")
    }

    private fun nextTerminalTabName(baseName: String): String {
        val existingNames = terminalNames()
        val pattern = Regex("""^${Regex.escape(baseName)} \((\d+)\)$""")
        val maxIndex = existingNames.fold(0) { max, name ->
            when {
                name == baseName -> maxOf(max, 1)
                else -> maxOf(max, pattern.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0)
            }
        }
        return if (maxIndex == 0) baseName else "$baseName (${maxIndex + 1})"
    }

    private fun terminalNames(): List<String> {
        val frontendNames = frontendHelper?.allTerminalNames().orEmpty()
        val classicNames = TerminalToolWindowManager.getInstance(project)
            .toolWindow
            ?.contentManager
            ?.contents
            ?.mapNotNull { it.displayName }
            .orEmpty()
        return frontendNames + classicNames
    }

    private fun terminalToolWindow(manager: TerminalToolWindowManager): ToolWindow? {
        manager.toolWindow?.let { return it }
        return try {
            val method = manager.javaClass.getDeclaredMethod("getOrInitToolWindow")
            method.isAccessible = true
            method.invoke(manager) as? ToolWindow
        } catch (_: Throwable) {
            null
        }
    }

    private fun notifyLegacyReworkedFallbackIfNeeded(toolName: String) {
        when (ideBaselineVersion()) {
            251 -> notify(
                project,
                "使用 Classic Terminal 启动 $toolName。",
                NotificationType.WARNING
            )
            252 -> notify(
                project,
                "使用 Classic Terminal 启动 $toolName。",
                NotificationType.WARNING
            )
        }
    }

    private fun ideBaselineVersion(): Int {
        return ApplicationInfo.getInstance().build.baselineVersion
    }

    private fun injectDirectInput(terminal: TargetTerminal, payload: String, settleAtLineEnd: Boolean): BridgeResult {
        return when (terminal) {
            is TargetTerminal.Classic -> injectClassicDirectInput(terminal.widget, payload, settleAtLineEnd)
            is TargetTerminal.LegacyReworked -> legacyReworkedTerminalHelper.injectDirectInput(
                terminal.widget,
                payload,
                settleAtLineEnd
            )
            is TargetTerminal.Frontend -> {
                val helper = frontendHelper
                    ?: return BridgeResult.Error("新版终端 API 在当前 IDE 中不可用。")
                helper.injectDirectInput(terminal.tab, payload, settleAtLineEnd)
            }
        }
    }

    /** 在异步发送启动命令前绑定终端对象，确保用户立即关闭标签时也能找到 tabId。 */
    private fun trackAiTerminal(
        terminal: TargetTerminal,
        context: AiTerminalTabContext,
        terminalDisposable: Disposable? = null
    ): Boolean {
        if (activeAiTerminalContexts[context.tabId] !== context) {
            terminalDisposable?.let { Disposer.dispose(it) }
            return false
        }

        val registration = AiTerminalRegistration(context, terminalDisposable)
        when (terminal) {
            is TargetTerminal.Classic -> aiClassicTerminals[terminal.widget] = registration
            is TargetTerminal.LegacyReworked -> aiLegacyReworkedTerminals[terminal.widget] = registration
            is TargetTerminal.Frontend -> aiFrontendTerminals[terminal.tab] = registration
        }
        project.service<AiTerminalDropService>().refreshDropTarget()
        return true
    }

    /**
     * 幂等释放一个 AI 终端的全部生命周期资源。
     * 只有成功移除活动上下文的调用方可以继续撤销 token、Turn 和 launcher。
     */
    private fun cleanupAiTerminalLifecycle(context: AiTerminalTabContext, showDiff: Boolean) {
        if (!activeAiTerminalContexts.remove(context.tabId, context)) {
            return
        }

        // 先解除所有终端对象关联，防止关闭、失败回调和项目销毁重复进入清理。
        val terminalDisposables = aiClassicTerminals.values
            .filter { registration -> registration.context.tabId == context.tabId }
            .mapNotNull { registration -> registration.terminalDisposable }
        aiFrontendTerminals.entries.removeIf { it.value.context.tabId == context.tabId }
        aiLegacyReworkedTerminals.entries.removeIf { it.value.context.tabId == context.tabId }
        aiClassicTerminals.entries.removeIf { it.value.context.tabId == context.tabId }

        // 先撤销服务端认可的 tabId/token，再处理本地文件和终端 Disposable。
        if (!project.isDisposed) {
            try {
                project.service<AiTurnMonitorService>().unregisterTab(context.tabId, showDiff)
            } catch (exception: Throwable) {
                log.warn("Failed to unregister AI terminal tab ${context.tabId}", exception)
            }
        }

        // launcher 只包含当前 tab 的鉴权信息，共享 hooks/plugin 配置继续保留。
        when (context.tool) {
            AiTool.OPENCODE -> AiTurnOpenCodeInstaller(project).cleanupLauncherScripts(context.tabId)
            AiTool.CLAUDE_CODE -> AiTurnHookInstaller(project).cleanupLauncherScripts(context.tabId)
        }
        terminalDisposables.forEach { disposable ->
            try {
                Disposer.dispose(disposable)
            } catch (exception: Throwable) {
                log.warn("Failed to dispose Classic terminal resources for ${context.tabId}", exception)
            }
        }
        log.info("Cleaned AI terminal lifecycle: ${context.tabId} (${context.tool})")
    }

    private fun isRecordedAiTerminal(terminal: TargetTerminal): Boolean {
        return when (terminal) {
            is TargetTerminal.Classic -> aiClassicTerminals.containsKey(terminal.widget)
            is TargetTerminal.LegacyReworked -> aiLegacyReworkedTerminals.containsKey(terminal.widget)
            is TargetTerminal.Frontend -> aiFrontendTerminals.containsKey(terminal.tab)
        }
    }

    private fun isFrontendContentOf(helper: FrontendTerminalHelper, tab: Any, content: Content): Boolean {
        return try {
            helper.isContentOf(tab, content)
        } catch (_: Throwable) {
            false
        }
    }

    /** 通过 TTY Connector 直接向经典终端写入文本 */
    private fun injectClassicDirectInput(terminal: TerminalWidget, payload: String, settleAtLineEnd: Boolean): BridgeResult {
        val connector = try {
            terminal.ttyConnector
        } catch (_: Throwable) {
            return BridgeResult.Error("当前 Terminal 没有暴露可写入的 TTY 连接。")
        } ?: return BridgeResult.Error("当前 Terminal 没有暴露可写入的 TTY 连接。")

        if (!connector.isConnected) {
            return BridgeResult.Error("当前激活的 Terminal 已断开连接。")
        }

        return try {
            terminal.requestFocus()
            connector.write(payload)
            if (settleAtLineEnd) {
                scheduleClassicLineEndSpace { connector.write(LINE_END_SPACE) }
            }
            BridgeResult.Success
        } catch (exception: IOException) {
            BridgeResult.Error("发送 AI Terminal 输入失败：${exception.message}")
        }
    }

    /** 经典终端上行尾空格延时 300ms（等待终端处理完输入） */
    private fun scheduleClassicLineEndSpace(writeLineEndSpace: () -> Unit) {
        Timer(SETTLE_INPUT_DELAY_MS) {
            try {
                writeLineEndSpace()
            } catch (exception: Throwable) {
                notify(project, "发送 AI Terminal 行尾空格失败：${exception.message}", NotificationType.WARNING)
            }
        }.apply {
            isRepeats = false
            start()
        }
    }

    /** 终端发现优先级：DataContext → 当前选中终端 */
    private fun resolveTargetTerminal(dataContext: DataContext): TargetTerminal? {
        classicTerminalFromDataContext(dataContext)?.let {
            val target = TargetTerminal.Classic(it)
            if (isUsable(target)) return target
        }
        return selectedTerminal()?.takeIf { isUsable(it) }
    }

    private fun classicTerminalFromDataContext(dataContext: DataContext): TerminalWidget? {
        return JBTerminalWidget.TERMINAL_DATA_KEY.getData(dataContext)?.asNewWidget()
    }

    /** 优先前端终端 → 经典终端 */
    private fun selectedTerminal(): TargetTerminal? {
        return frontendHelper?.selectedTerminal()?.let { TargetTerminal.Frontend(it) }
            ?: selectedClassicOrLegacyTerminal()
    }

    private fun selectedClassicOrLegacyTerminal(): TargetTerminal? {
        val toolWindow = TerminalToolWindowManager.getInstance(project).toolWindow ?: return null
        val selectedContent = toolWindow.contentManager.selectedContent ?: return null
        val widget = TerminalToolWindowManager.findWidgetByContent(selectedContent) ?: return null
        return if (legacyReworkedTerminalHelper.isReworkedWidget(widget)) {
            TargetTerminal.LegacyReworked(widget)
        } else {
            TargetTerminal.Classic(widget)
        }
    }

    /** 检查终端是否可用：经典终端检查 TTY 连接，前端终端检查 tab 仍存在 */
    private fun isUsable(terminal: TargetTerminal): Boolean {
        return when (terminal) {
            is TargetTerminal.Classic -> {
                try {
                    terminal.widget.ttyConnector?.isConnected == true
                } catch (_: Throwable) {
                    false
                }
            }
            is TargetTerminal.LegacyReworked -> legacyReworkedTerminalHelper.isWidgetContentExists(terminal.widget)
            is TargetTerminal.Frontend -> frontendHelper?.isTabExists(terminal.tab) == true
        }
    }

    private fun generateSecureToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun isWindows(): Boolean {
        return System.getProperty("os.name", "").lowercase().contains("win")
    }

    /** 项目关闭或插件卸载时仅释放资源，不再弹出新的 Diff 窗口。 */
    override fun dispose() {
        activeAiTerminalContexts.values
            .toList()
            .forEach { context -> cleanupAiTerminalLifecycle(context, showDiff = false) }
        aiFrontendTerminals.clear()
        aiLegacyReworkedTerminals.clear()
        aiClassicTerminals.clear()
    }

    companion object {
        private const val NOTIFICATION_GROUP_ID = "AI Terminal Tools"
        private const val OPEN_CODE_TAB_NAME = "OpenCode"
        private const val CLAUDE_CODE_TAB_NAME = "Claude Code"
        private const val NO_ACTIVE_TERMINAL_MESSAGE = "请先启动并激活 OpenCode 或 Claude Code 终端。"
        private const val LINE_END_SPACE = "\u0005 "
        private const val BRACKETED_PASTE_START = "\u001B[200~"
        private const val BRACKETED_PASTE_END = "\u001B[201~"
        private const val SETTLE_INPUT_DELAY_MS = 300

        fun getInstance(project: Project): AiTerminalBridgeService {
            return project.service()
        }

        fun notify(project: Project, message: String, type: NotificationType) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(message, type)
                .notify(project)
        }
    }

    /** 发送结果类型 */
    sealed class BridgeResult {
        data object Success : BridgeResult()
        data object Scheduled : BridgeResult()
        data class Error(val message: String) : BridgeResult()
    }

    /** 终端类型抽象：经典 / 前端（tab 在运行时为 TerminalToolWindowTab 类型） */
    private sealed class TargetTerminal {
        data class Classic(val widget: TerminalWidget) : TargetTerminal()
        data class LegacyReworked(val widget: TerminalWidget) : TargetTerminal()
        data class Frontend(val tab: Any) : TargetTerminal()
    }

    /** 终端对象与监控上下文的关联；Classic 同时持有其启动 Disposable。 */
    private data class AiTerminalRegistration(
        val context: AiTerminalTabContext,
        val terminalDisposable: Disposable? = null
    )
}
