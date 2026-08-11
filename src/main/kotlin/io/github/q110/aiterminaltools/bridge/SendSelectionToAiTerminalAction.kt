// "发送选区到 AI Terminal" Action — 将编辑器选中代码通过桥接发送到当前激活的 AI 终端输入区
package io.github.q110.aiterminaltools.bridge

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.FileDocumentManager
import io.github.q110.aiterminaltools.filter.displayPath

class SendSelectionToAiTerminalAction : AnAction(AllIcons.Debugger.Console) {
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }

    override fun update(event: AnActionEvent) {
        val editor = event.getData(CommonDataKeys.EDITOR)
        val project = event.project
        val hasSelection = editor?.selectionModel?.hasSelection() == true
        event.presentation.isVisible = project != null && hasSelection
        event.presentation.isEnabled = project != null && hasSelection
    }

    /** 构造 payload 并调用桥接服务 */
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return

        val selectionModel = editor.selectionModel
        val selectedText = selectionModel.selectedText ?: return
        if (selectedText.isEmpty()) return

        val document = editor.document
        val virtualFile = event.getData(CommonDataKeys.VIRTUAL_FILE)
            ?: FileDocumentManager.getInstance().getFile(document)

        val payload = if (virtualFile != null) {
            val startOffset = selectionModel.selectionStart
            val endOffset = selectionModel.selectionEnd
            val endOffsetForLine = (endOffset - 1).coerceAtLeast(startOffset)
            val startLine = document.getLineNumber(startOffset) + 1
            val endLine = document.getLineNumber(endOffsetForLine) + 1
            val filePath = displayPath(project, virtualFile)
            val lineRange = if (startLine == endLine) startLine else "$startLine-$endLine"
            "@$filePath:$lineRange\n-------\n$selectedText\n-------\n"
        } else {
            "-------\n$selectedText\n-------\n"
        }

        val result = AiTerminalBridgeService.getInstance(project).sendDirectPaste(payload, event.dataContext)
        if (result is AiTerminalBridgeService.BridgeResult.Error) {
            AiTerminalBridgeService.notify(project, result.message, NotificationType.WARNING)
        }
    }
}
