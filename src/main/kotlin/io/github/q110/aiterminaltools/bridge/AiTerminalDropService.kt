// 终端拖拽接收服务 — 将拖到当前激活终端标签的文件路径发送到该终端
package io.github.q110.aiterminaltools.bridge

import com.intellij.ide.dnd.DnDEvent
import com.intellij.ide.dnd.DnDSupport
import com.intellij.ide.dnd.FileCopyPasteUtil
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.terminal.JBTerminalWidget
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.ui.awt.RelativePoint
import io.github.q110.aiterminaltools.copy.CopyTextHyperlinkInfo
import io.github.q110.aiterminaltools.filter.AiTerminalToolsFilter
import io.github.q110.aiterminaltools.filter.FilterPatterns
import io.github.q110.aiterminaltools.filter.isCopyNoise
import io.github.q110.aiterminaltools.settings.AiTerminalToolsSettings
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent

@Service(Service.Level.PROJECT)
class AiTerminalDropService(
    private val project: Project
) : Disposable {
    private val contentManagerListeners = mutableMapOf<ContentManager, ContentManagerListener>()
    private val dropTargetDisposables = mutableMapOf<Content, Disposable>()
    private val classicCopyDisposables = mutableMapOf<Content, Disposable>()
    private var initialized = false

    /** 监听终端工具窗口和内容切换，确保拖拽目标始终绑定到当前激活终端 */
    fun initialize() {
        if (initialized) {
            return
        }
        initialized = true

        project.messageBus.connect(this)
            .subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
                override fun toolWindowShown(toolWindow: ToolWindow) {
                    if (toolWindow.id == TERMINAL_TOOL_WINDOW_ID) {
                        installForToolWindow(toolWindow)
                    }
                }

                override fun toolWindowsRegistered(ids: List<String>, toolWindowManager: ToolWindowManager) {
                    if (TERMINAL_TOOL_WINDOW_ID in ids) {
                        installForCurrentTerminalToolWindow()
                    }
                }
            })

        ApplicationManager.getApplication().invokeLater {
            installForCurrentTerminalToolWindow()
        }
    }

    fun refreshDropTarget() {
        // 只给当前激活内容安装拖拽/Classic 点击复制监听，避免旧 tab 残留监听器。
        val targetContent = currentActiveContent()
        val dropEnabled = targetContent != null && (dragToAiTerminalEnabled() || isRecordedAiTerminalContent(targetContent))
        val isClassic = targetContent != null && copyLinksEnabled() && isClassicContent(targetContent)
        project.putUserData(AiTerminalToolsFilter.KEY_CURRENT_TERMINAL_CLASSIC, isClassic)

        dropTargetDisposables.keys
            .filter { it !== targetContent || !dropEnabled }
            .toList()
            .forEach { disposeDropTarget(it) }

        classicCopyDisposables.keys
            .filter { it !== targetContent || !copyLinksEnabled() }
            .toList()
            .forEach { disposeClassicCopyTarget(it) }

        if (dropEnabled) {
            installForContent(targetContent!!)
        }
        if (targetContent != null && copyLinksEnabled()) {
            installClassicCopyForContent(targetContent)
        }
    }

    private fun installForCurrentTerminalToolWindow() {
        val toolWindow = TerminalToolWindowManager.getInstance(project).toolWindow
            ?: ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID)
            ?: return
        installForToolWindow(toolWindow)
    }

    private fun installForToolWindow(toolWindow: ToolWindow) {
        val contentManager = toolWindow.contentManager
        if (!contentManagerListeners.containsKey(contentManager)) {
            val listener = object : ContentManagerListener {
                override fun contentAdded(event: ContentManagerEvent) {
                    refreshDropTarget()
                }

                override fun contentRemoved(event: ContentManagerEvent) {
                    // Detach、拖动或迁移标签时 Content 会被临时移除，此时终端会继续存活，不能撤销其鉴权状态。
                    if (event.content.getUserData(Content.TEMPORARY_REMOVED_KEY) == true) {
                        return
                    }
                    project.service<AiTerminalBridgeService>().unregisterAiTerminalContent(event.content)
                    disposeDropTarget(event.content)
                    disposeClassicCopyTarget(event.content)
                    refreshDropTarget()
                }

                override fun selectionChanged(event: ContentManagerEvent) {
                    refreshDropTarget()
                }
            }
            contentManager.addContentManagerListener(listener)
            contentManagerListeners[contentManager] = listener
        }
        refreshDropTarget()
    }

    private fun currentActiveContent(): Content? {
        val toolWindow = TerminalToolWindowManager.getInstance(project).toolWindow
            ?: ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID)
            ?: return null
        return toolWindow.contentManager.selectedContent
    }

    private fun installForContent(content: Content) {
        if (!dragToAiTerminalEnabled() && !isRecordedAiTerminalContent(content)) {
            return
        }
        val component = content.component ?: return
        if (dropTargetDisposables.containsKey(content)) {
            return
        }

        val disposable = Disposer.newDisposable("AI terminal drop target")
        dropTargetComponents(content, component).forEach { targetComponent ->
            installDropTarget(targetComponent, content, disposable)
        }

        dropTargetDisposables[content] = disposable
    }

    private fun installDropTarget(component: JComponent, content: Content, disposable: Disposable) {
        DnDSupport.createBuilder(component)
            .disableAsSource()
            .enableAsNativeTarget()
            .setTargetChecker { event ->
                val files = draggedFiles(event)
                val canDrop = canHandleDrop(files, content)
                if (canDrop) {
                    event.setDropPossible(true, "发送路径到 AI Terminal")
                }
                canDrop
            }
            .setDropHandler { event ->
                val files = draggedFiles(event)
                if (!canHandleDrop(files, content)) {
                    return@setDropHandler
                }
                val result = project.service<AiTerminalBridgeService>().sendDroppedPaths(files)
                if (result is AiTerminalBridgeService.BridgeResult.Error) {
                    AiTerminalBridgeService.notify(project, result.message, NotificationType.WARNING)
                }
            }
            .setDisposableParent(disposable)
            .install()
    }

    private fun dropTargetComponents(content: Content, component: JComponent): List<JComponent> {
        val components = mutableListOf<JComponent>()
        components += component
        val widget = TerminalToolWindowManager.findWidgetByContent(content)
        if (widget != null) {
            try {
                components += widget.component
            } catch (_: Throwable) {
            }
            try {
                components += widget.preferredFocusableComponent
            } catch (_: Throwable) {
            }
        }
        return components.filter { it.isShowing || it === component }.distinct()
    }

    private fun canHandleDrop(files: List<VirtualFile>, content: Content): Boolean {
        return files.isNotEmpty() &&
            currentActiveContent() === content &&
            (dragToAiTerminalEnabled() || isRecordedAiTerminalContent(content))
    }

    private fun dragToAiTerminalEnabled(): Boolean {
        return AiTerminalToolsSettings.getInstance().getState().isDragToAiTerminalEnabled()
    }

    private fun copyLinksEnabled(): Boolean {
        return AiTerminalToolsSettings.getInstance().getState().copyLinksEnabled
    }

    private fun isClassicContent(content: Content): Boolean {
        val widget = TerminalToolWindowManager.findWidgetByContent(content) ?: return false
        val jediTermWidget = try {
            JBTerminalWidget.asJediTermWidget(widget)
        } catch (_: Throwable) {
            null
        } ?: return false
        return isClassicWidget(jediTermWidget)
    }

    private fun isRecordedAiTerminalContent(content: Content): Boolean {
        return project.service<AiTerminalBridgeService>().isRecordedAiTerminalContent(content)
    }

    private fun disposeDropTarget(content: Content) {
        val disposable = dropTargetDisposables.remove(content) ?: return
        Disposer.dispose(disposable)
    }

    /** Classic 终端不能稳定显示复制链接样式，改用鼠标点击文本范围检测 */
    private fun installClassicCopyForContent(content: Content) {
        if (classicCopyDisposables.containsKey(content)) {
            return
        }
        val widget = TerminalToolWindowManager.findWidgetByContent(content) ?: return
        val jediTermWidget = try {
            JBTerminalWidget.asJediTermWidget(widget)
        } catch (_: Throwable) {
            null
        } ?: return
        if (!isClassicWidget(jediTermWidget)) {
            return
        }

        val panel = try {
            jediTermWidget.terminalPanel
        } catch (_: Throwable) {
            return
        }

        val listener = object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.button != MouseEvent.BUTTON1 || event.clickCount != 1) {
                    return
                }
                val match = copyMatchAt(panel, event) ?: return
                CopyTextHyperlinkInfo(project, match).navigate(project, RelativePoint(event))
            }
        }

        val disposable = Disposer.newDisposable("AI terminal classic copy target")
        panel.addMouseListener(listener)
        Disposer.register(disposable, Disposable {
            panel.removeMouseListener(listener)
        })
        classicCopyDisposables[content] = disposable
    }

    private fun isClassicWidget(widget: JBTerminalWidget): Boolean {
        val className = widget.javaClass.name
        return className.contains("ShellTerminalWidget") || className.contains("JediTerm")
    }

    private fun copyMatchAt(panel: Component, event: MouseEvent): String? {
        val cell = try {
            val method = findMethod(panel.javaClass, "panelPointToCell", java.awt.Point::class.java)
            method.invoke(panel, event.point)
        } catch (_: Throwable) {
            null
        } ?: return null

        val lineNumber = try {
            findMethod(cell.javaClass, "getLine").invoke(cell) as? Int
        } catch (_: Throwable) { null } ?: return null

        val column = try {
            findMethod(cell.javaClass, "getColumn").invoke(cell) as? Int
        } catch (_: Throwable) { null } ?: return null

        val line = terminalLineText(panel, lineNumber)
            ?: terminalLineText(panel, lineNumber - 1)
            ?: return null
        return findCopyMatchAt(line, column) ?: findCopyMatchAt(line, column - 1)
    }

    private fun terminalLineText(panel: Component, lineNumber: Int): String? {
        if (lineNumber < 0) {
            return null
        }
        return try {
            val buffer = findMethod(panel.javaClass, "getTerminalTextBuffer").invoke(panel)
            val line = findMethod(buffer.javaClass, "getLine", Int::class.java).invoke(buffer, lineNumber)
            findMethod(line.javaClass, "getText").invoke(line) as? String
        } catch (_: Throwable) {
            null
        }
    }

    private fun findMethod(type: Class<*>, name: String, vararg parameterTypes: Class<*>): java.lang.reflect.Method {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, *parameterTypes).also { it.isAccessible = true }
            } catch (_: NoSuchMethodException) {
                current = current.superclass
            }
        }
        throw NoSuchMethodException("$type.$name")
    }

    private fun findCopyMatchAt(line: String, column: Int): String? {
        if (column < 0) {
            return null
        }
        for (pattern in FilterPatterns.copyPatterns) {
            for (match in pattern.findAll(line)) {
                val text = match.value.trim()
                if (column in match.range && text.isNotEmpty() && !isCopyNoise(text)) {
                    return text
                }
            }
        }
        return null
    }

    private fun disposeClassicCopyTarget(content: Content) {
        val disposable = classicCopyDisposables.remove(content) ?: return
        Disposer.dispose(disposable)
    }

    private fun draggedFiles(event: DnDEvent): List<VirtualFile> {
        val attachedFiles = try {
            FileCopyPasteUtil.getVirtualFileListFromAttachedObject(event.attachedObject)
        } catch (_: Throwable) {
            emptyList()
        }
        if (attachedFiles.isNotEmpty()) {
            return attachedFiles.filter { it.isValid }
        }

        val localFiles = try {
            FileCopyPasteUtil.getFileList(event)
        } catch (_: Throwable) {
            emptyList()
        }
        return localFiles.orEmpty()
            .mapNotNull { LocalFileSystem.getInstance().findFileByIoFile(it) }
            .filter { it.isValid }
    }

    override fun dispose() {
        dropTargetDisposables.keys.toList().forEach { disposeDropTarget(it) }
        classicCopyDisposables.keys.toList().forEach { disposeClassicCopyTarget(it) }
        contentManagerListeners.forEach { (contentManager, listener) ->
            contentManager.removeContentManagerListener(listener)
        }
        contentManagerListeners.clear()
    }

    companion object {
        private const val TERMINAL_TOOL_WINDOW_ID = "Terminal"
    }
}
