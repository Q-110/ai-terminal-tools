// 控制台错误 Inlay 服务 — 在错误首行旁显示发送到 AI Terminal 的图标
package io.github.q110.aiterminaltools.console

import com.intellij.ide.DataManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.TextRange
import io.github.q110.aiterminaltools.bridge.AiTerminalBridgeService
import io.github.q110.aiterminaltools.settings.AiTerminalToolsSettings
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Rectangle
import javax.swing.Icon
import javax.swing.JComponent

@Service(Service.Level.PROJECT)
class AiConsoleErrorInlayService(
    private val project: Project
) : Disposable {
    private val editorFactory = com.intellij.openapi.editor.EditorFactory.getInstance()
    private val documentInlays = mutableMapOf<Document, MutableMap<Int, TrackedErrorInlay>>()
    private val pendingDocuments = mutableSetOf<Document>()
    private var hoveredErrorIconComponent: JComponent? = null
    private var previousCursor: Cursor? = null
    private var previousToolTipText: String? = null

    init {
        // 控制台输出持续追加，文档变化时延迟重扫以合并同一批输出。
        editorFactory.eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                scheduleRescan(event.document)
            }
        }, this)
        editorFactory.eventMulticaster.addEditorMouseListener(ConsoleErrorMouseListener(), this)
        editorFactory.eventMulticaster.addEditorMouseMotionListener(ConsoleErrorMouseMotionListener(), this)
        editorFactory.addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                if (isProjectConsoleEditor(event.editor)) {
                    scheduleRescan(event.editor.document)
                }
            }

            override fun editorReleased(event: EditorFactoryEvent) {
                val document = event.editor.document
                if (projectConsoleEditors(document).isEmpty()) {
                    disposeDocumentInlays(document)
                }
            }
        }, this)
    }

    /** 项目启动后补扫已经存在的控制台编辑器 */
    fun initialize() {
        editorFactory.allEditors
            .filter { isProjectConsoleEditor(it) }
            .forEach { scheduleRescan(it.document) }
    }

    private fun scheduleRescan(document: Document) {
        if (project.isDisposed) return
        if (!pendingDocuments.add(document)) return

        ApplicationManager.getApplication().invokeLater {
            pendingDocuments.remove(document)
            if (!project.isDisposed) {
                rescan(document)
            }
        }
    }

    /** 根据解析出的错误块增删 inlay，避免同一行重复创建图标 */
    private fun rescan(document: Document) {
        val editors = projectConsoleEditors(document)
        if (editors.isEmpty()) {
            disposeDocumentInlays(document)
            return
        }

        if (!settings().errorToAiTerminalIconsEnabled) {
            disposeDocumentInlays(document)
            return
        }

        val blocks = try {
            ConsoleErrorBlockParser.parse(document)
        } catch (_: Throwable) {
            return
        }

        val expectedStartOffsets = blocks.mapTo(mutableSetOf()) { it.startOffset }
        val existing = documentInlays.getOrPut(document) { mutableMapOf() }
        existing.entries.removeIf { (startOffset, tracked) ->
            if (startOffset in expectedStartOffsets && tracked.inlay.isValid && tracked.rangeMarker.isValid) {
                false
            } else {
                tracked.dispose()
                true
            }
        }

        for (block in blocks) {
            if (existing.containsKey(block.startOffset)) continue
            val editor = editors.firstOrNull() ?: continue
            val marker = document.createRangeMarker(block.startOffset, block.endOffset).apply {
                setGreedyToLeft(false)
                setGreedyToRight(true)
            }
            val renderer = ConsoleErrorInlayRenderer(project, marker)
            val inlay = editor.inlayModel.addInlineElement(block.iconOffset, true, renderer)
            if (inlay == null) {
                marker.dispose()
            } else {
                existing[block.startOffset] = TrackedErrorInlay(inlay, marker)
            }
        }
    }

    /** 点击图标时只发送当前错误块文本，而不是整个控制台输出 */
    private fun sendErrorToAiTerminal(editor: Editor, rangeMarker: RangeMarker) {
        if (!rangeMarker.isValid) {
            AiTerminalBridgeService.notify(project, "控制台错误已失效，请重新选择。", NotificationType.WARNING)
            return
        }

        val document = rangeMarker.document
        val startOffset = rangeMarker.startOffset.coerceIn(0, document.textLength)
        val endOffset = rangeMarker.endOffset.coerceIn(startOffset, document.textLength)
        val errorText = document.getText(TextRange(startOffset, endOffset)).trim()
        if (errorText.isEmpty()) {
            AiTerminalBridgeService.notify(project, "控制台错误内容为空。", NotificationType.WARNING)
            return
        }

        val payload = "控制台错误：\n-------\n$errorText\n-------\n"
        val dataContext = DataManager.getInstance().getDataContext(editor.component)
        val result = AiTerminalBridgeService.getInstance(project).sendDirectPaste(payload, dataContext)
        if (result is AiTerminalBridgeService.BridgeResult.Error) {
            AiTerminalBridgeService.notify(project, result.message, NotificationType.WARNING)
        }
    }

    private fun projectConsoleEditors(document: Document): List<Editor> {
        return editorFactory.getEditors(document, project)
            .filter { isProjectConsoleEditor(it) }
    }

    private fun isProjectConsoleEditor(editor: Editor): Boolean {
        return editor.project == project && editor.editorKind == EditorKind.CONSOLE && !editor.isDisposed
    }

    private fun disposeDocumentInlays(document: Document) {
        documentInlays.remove(document)?.values?.forEach { it.dispose() }
    }

    override fun dispose() {
        clearErrorIconHover()
        pendingDocuments.clear()
        documentInlays.values.forEach { trackedByOffset ->
            trackedByOffset.values.forEach { it.dispose() }
        }
        documentInlays.clear()
    }

    private fun settings(): AiTerminalToolsSettings.StateData {
        return AiTerminalToolsSettings.getInstance().getState()
    }

    private inner class ConsoleErrorMouseListener : EditorMouseListener {
        override fun mouseClicked(event: EditorMouseEvent) {
            val renderer = event.inlay?.renderer as? ConsoleErrorInlayRenderer ?: return
            event.consume()
            sendErrorToAiTerminal(event.editor, renderer.rangeMarker)
        }
    }

    private inner class ConsoleErrorMouseMotionListener : EditorMouseMotionListener {
        override fun mouseMoved(event: EditorMouseEvent) {
            if (!isProjectConsoleEditor(event.editor)) {
                clearErrorIconHover()
                return
            }

            val component = event.editor.contentComponent
            if (event.inlay?.renderer is ConsoleErrorInlayRenderer) {
                if (hoveredErrorIconComponent == component) return
                clearErrorIconHover()
                hoveredErrorIconComponent = component
                previousCursor = component.cursor
                previousToolTipText = component.toolTipText
                component.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                component.toolTipText = "Send console error to AI Terminal"
            } else {
                clearErrorIconHover()
            }
        }

        override fun mouseDragged(event: EditorMouseEvent) {
        }
    }

    private fun clearErrorIconHover() {
        val component = hoveredErrorIconComponent ?: return
        component.cursor = previousCursor ?: Cursor.getDefaultCursor()
        component.toolTipText = previousToolTipText
        hoveredErrorIconComponent = null
        previousCursor = null
        previousToolTipText = null
    }

    private data class TrackedErrorInlay(
        val inlay: Inlay<ConsoleErrorInlayRenderer>,
        val rangeMarker: RangeMarker
    ) {
        fun dispose() {
            inlay.dispose()
            rangeMarker.dispose()
        }
    }

    private class ConsoleErrorInlayRenderer(
        private val project: Project,
        val rangeMarker: RangeMarker
    ) : EditorCustomElementRenderer {
        private val icon: Icon = IconLoader.getIcon("/icons/send-selection.svg", AiConsoleErrorInlayService::class.java)

        override fun calcWidthInPixels(inlay: Inlay<*>): Int {
            return icon.iconWidth + HORIZONTAL_PADDING * 2
        }

        override fun paint(
            inlay: Inlay<*>,
            graphics: Graphics,
            targetRegion: Rectangle,
            textAttributes: TextAttributes
        ) {
            val x = targetRegion.x + HORIZONTAL_PADDING
            val y = targetRegion.y + ((targetRegion.height - icon.iconHeight) / 2).coerceAtLeast(0)
            icon.paintIcon(inlay.editor.contentComponent, graphics, x, y)
        }

        override fun toString(): String {
            return "ConsoleErrorInlayRenderer(${project.name})"
        }

        companion object {
            private const val HORIZONTAL_PADDING = 4
        }
    }
}
