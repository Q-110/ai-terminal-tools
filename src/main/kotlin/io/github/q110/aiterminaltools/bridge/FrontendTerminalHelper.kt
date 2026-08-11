// Frontend Terminal 兼容层 — 通过反射适配 IDE 2025.3+ 新终端 API
package io.github.q110.aiterminaltools.bridge

import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import io.github.q110.aiterminaltools.bridge.AiTerminalBridgeService.BridgeResult
import javax.swing.JComponent
import javax.swing.Timer

class FrontendTerminalHelper(
    private val project: Project
) {
    private val tabsManagerClass = Class.forName(FRONTEND_TABS_MANAGER_CLASS)
    private val tabBuilderClass = Class.forName(FRONTEND_TAB_BUILDER_CLASS)
    private val tabClass = Class.forName(FRONTEND_TAB_CLASS)
    private val tabsManager = Class.forName(FRONTEND_TABS_MANAGER_CLASS)
        .getMethod("getInstance", Project::class.java)
        .invoke(null, project)

    /** 当前选中的终端 Content 需要反查到 frontend tab 对象才能写入输入区 */
    fun selectedTerminal(): Any? {
        val selectedContent = ToolWindowManager.getInstance(project)
            .getToolWindow(TERMINAL_TOOL_WINDOW_ID)
            ?.contentManager?.selectedContent ?: return null
        return tabs().firstOrNull { contentOf(it) == selectedContent }
    }

    fun allTerminals(): List<Any> = tabs()

    fun allTerminalNames(): List<String> {
        return tabs().mapNotNull { contentOf(it)?.displayName }
    }

    fun createAiTerminal(tabName: String, workingDirectory: String): Any {
        var builder = tabsManagerMethod("createTabBuilder").invoke(tabsManager)
        builder = tabBuilderMethod("workingDirectory", String::class.java).invoke(builder, workingDirectory)
        builder = tabBuilderMethod("tabName", String::class.java).invoke(builder, tabName)
        builder = tabBuilderMethod("requestFocus", Boolean::class.javaPrimitiveType).invoke(builder, true)
        // 等 UI 完整显示后再启动会话，避免 2025.3+ 首次渲染宽度异常。
        builder = tabBuilderMethod("deferSessionStartUntilUiShown", Boolean::class.javaPrimitiveType).invoke(builder, true)
        return tabBuilderMethod("createTab").invoke(builder)
    }

    /** 在前端终端获得焦点后执行命令，避免命令写入到旧焦点组件 */
    fun runCommand(
        tab: Any,
        command: String,
        successMessage: String,
        failurePrefix: String,
        onCommandSent: () -> Unit = {},
        onCommandFailed: () -> Unit = {}
    ): BridgeResult {
        val content = contentOf(tab)
            ?: return BridgeResult.Error("Invalid frontend terminal tab.")
        val view = viewOf(tab)
            ?: return BridgeResult.Error("Invalid frontend terminal view.")
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID)
            ?: return BridgeResult.Error("Terminal tool window was not found.")

        toolWindow.activate(Runnable {
            val selectionCallback = toolWindow.contentManager.setSelectedContentCB(content, true, true)
            selectionCallback.doWhenDone(Runnable {
                val focusComponent = preferredFocusableComponent(view)
                if (focusComponent == null) {
                    AiTerminalBridgeService.notify(project, "Cannot focus the AI terminal input component.", NotificationType.WARNING)
                    onCommandFailed()
                    return@Runnable
                }
                IdeFocusManager.getInstance(project)
                    .requestFocusInProject(focusComponent, project)
                    .doWhenDone(Runnable {
                        try {
                            sendCommandToExecute(view, command)
                            onCommandSent()
                            AiTerminalBridgeService.notify(project, successMessage, NotificationType.INFORMATION)
                        } catch (exception: Throwable) {
                            AiTerminalBridgeService.notify(project, "$failurePrefix: ${exception.message}", NotificationType.WARNING)
                            onCommandFailed()
                        }
                    })
                    .doWhenRejected(Runnable {
                        AiTerminalBridgeService.notify(project, "Cannot focus the AI terminal input component.", NotificationType.WARNING)
                        onCommandFailed()
                    })
            })
            // 异步选择失败必须通知桥接服务，避免 tabId、token 和 launcher 继续残留。
            selectionCallback.doWhenRejected(Runnable {
                AiTerminalBridgeService.notify(project, "Cannot select the AI terminal tab.", NotificationType.WARNING)
                onCommandFailed()
            })
        }, true, true)

        return BridgeResult.Scheduled
    }

    fun isTabExists(tab: Any): Boolean {
        return tabs().any { it == tab }
    }

    fun isContentOf(tab: Any, content: Content): Boolean {
        return contentOf(tab) == content
    }

    /** 直接写入输入区，settleAtLineEnd 用于结束 @path 补全状态 */
    fun injectDirectInput(tab: Any, payload: String, settleAtLineEnd: Boolean): BridgeResult {
        val content = contentOf(tab)
            ?: return BridgeResult.Error("Invalid frontend terminal tab.")
        val view = viewOf(tab)
            ?: return BridgeResult.Error("Invalid frontend terminal view.")
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID)
            ?: return BridgeResult.Error("Terminal tool window was not found.")

        toolWindow.activate(Runnable {
            toolWindow.contentManager.setSelectedContentCB(content, true, true)
                .doWhenProcessed(Runnable {
                    val focusComponent = preferredFocusableComponent(view)
                    if (focusComponent == null) {
                        AiTerminalBridgeService.notify(project, "Cannot focus the AI terminal input component.", NotificationType.WARNING)
                        return@Runnable
                    }
                    val focusCallback = IdeFocusManager.getInstance(project)
                        .requestFocusInProject(focusComponent, project)
                    focusCallback.doWhenDone(Runnable {
                        try {
                            sendDirectInput(view, focusComponent, payload, settleAtLineEnd)
                        } catch (exception: Throwable) {
                            AiTerminalBridgeService.notify(project, "发送 AI Terminal 输入失败：${exception.message}", NotificationType.WARNING)
                        }
                    })
                    focusCallback.doWhenRejected(Runnable {
                        AiTerminalBridgeService.notify(project, "Cannot focus the AI terminal input component.", NotificationType.WARNING)
                    })
                })
        }, true, true)

        return BridgeResult.Scheduled
    }

    private fun tabs(): List<Any> {
        val tabs = tabsManagerMethod("getTabs").invoke(tabsManager) as? List<*>
        return tabs?.filterNotNull().orEmpty()
    }

    private fun sendDirectInput(view: Any, focusComponent: JComponent, payload: String, settleAtLineEnd: Boolean) {
        try {
            sendRawString(view, payload)
        } catch (_: Throwable) {
            sendText(view, payload)
        }
        if (settleAtLineEnd) {
            scheduleLineEndSpace(view, focusComponent, true)
        } else {
            AiTerminalBridgeService.notify(project, "已发送到 AI Terminal", NotificationType.INFORMATION)
        }
    }

    private fun scheduleLineEndSpace(view: Any, focusComponent: JComponent, notifyAfter: Boolean) {
        val timer = Timer(300) {
            try {
                IdeFocusManager.getInstance(project)
                    .requestFocusInProject(focusComponent, project)
                    .doWhenDone(Runnable {
                        sendLineEndSpace(view)
                        if (notifyAfter) {
                            AiTerminalBridgeService.notify(project, "已发送到 AI Terminal", NotificationType.INFORMATION)
                        }
                    })
                    .doWhenRejected(Runnable {
                        AiTerminalBridgeService.notify(project, "Cannot focus the AI terminal input component.", NotificationType.WARNING)
                    })
            } catch (exception: Throwable) {
                AiTerminalBridgeService.notify(project, "发送 AI Terminal 行尾空格失败：${exception.message}", NotificationType.WARNING)
            }
        }
        timer.isRepeats = false
        timer.start()
    }

    private fun sendRawString(view: Any, text: String) {
        val input = terminalInput(view)
        input.javaClass.getMethod("sendString", String::class.java).invoke(input, text)
    }

    private fun sendLineEndSpace(view: Any) {
        try {
            sendRawString(view, LINE_END_SPACE)
        } catch (_: Throwable) {
            sendText(view, LINE_END_SPACE)
        }
    }

    private fun sendText(view: Any, text: String) {
        view.javaClass.getMethod("sendText", String::class.java).invoke(view, text)
    }

    private fun sendCommandToExecute(view: Any, command: String) {
        val builder = view.javaClass.getMethod("createSendTextBuilder").invoke(view)
        val executableBuilder = builder.javaClass.getMethod("shouldExecute").invoke(builder)
        executableBuilder.javaClass.getMethod("send", String::class.java).invoke(executableBuilder, command)
    }

    private fun terminalInput(view: Any): Any {
        return findField(view.javaClass, "terminalInput")
            ?.let { field -> field.also { it.isAccessible = true }.get(view) }
            ?: throw IllegalStateException("Cannot access the frontend terminal input channel.")
    }

    private fun contentOf(tab: Any): Content? {
        return tabMethod("getContent").invoke(tab) as? Content
    }

    private fun viewOf(tab: Any): Any? {
        return tabMethod("getView").invoke(tab)
    }

    private fun preferredFocusableComponent(view: Any): JComponent? {
        return view.javaClass.getMethod("getPreferredFocusableComponent").invoke(view) as? JComponent
    }

    /** Frontend Terminal API 仍在变化，所有调用点集中通过反射兜底 */
    private fun tabsManagerMethod(name: String, vararg parameterTypes: Class<*>?): java.lang.reflect.Method {
        return tabsManagerClass.getMethod(name, *parameterTypes)
    }

    private fun tabBuilderMethod(name: String, vararg parameterTypes: Class<*>?): java.lang.reflect.Method {
        return tabBuilderClass.getMethod(name, *parameterTypes)
    }

    private fun tabMethod(name: String, vararg parameterTypes: Class<*>?): java.lang.reflect.Method {
        return tabClass.getMethod(name, *parameterTypes)
    }

    private fun findField(type: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return current.getDeclaredField(name)
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    companion object {
        private const val FRONTEND_TABS_MANAGER_CLASS =
            "com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager"
        private const val FRONTEND_TAB_BUILDER_CLASS =
            "com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabBuilder"
        private const val FRONTEND_TAB_CLASS =
            "com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab"
        private const val TERMINAL_TOOL_WINDOW_ID = "Terminal"
        private const val LINE_END_SPACE = "\u0005 "
    }
}
