// "发送文件/文件夹路径到 AI Terminal" Action — 项目树或编辑器标签页右键发送路径
package io.github.q110.aiterminaltools.bridge

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vfs.VirtualFile
import io.github.q110.aiterminaltools.filter.displayPath

class SendPathToAiTerminalAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    /** 根据选中项是文件还是文件夹动态改变菜单文字 */
    override fun update(event: AnActionEvent) {
        val project = event.project
        val selectedFiles = selectedVirtualFiles(event)
        val virtualFile = selectedFiles.firstOrNull()
        val hasFile = selectedFiles.isNotEmpty()
        event.presentation.text = if (virtualFile?.isDirectory == true) {
            "发送文件夹路径到 AI Terminal"
        } else {
            "发送文件路径到 AI Terminal"
        }
        event.presentation.isEnabledAndVisible = project != null && hasFile
    }

    /** 以 @path 格式发送路径，settleAtLineEnd=true 结束 @路径补全 */
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val virtualFile = selectedVirtualFiles(event).firstOrNull() ?: return

        val payload = "@${displayPath(project, virtualFile)}"
        val result = AiTerminalBridgeService.getInstance(project)
            .sendDirectInput(payload, event.dataContext, settleAtLineEnd = true)
        if (result is AiTerminalBridgeService.BridgeResult.Error) {
            AiTerminalBridgeService.notify(project, result.message, NotificationType.WARNING)
        }
    }

    /** 获取选中文件：优先 VIRTUAL_FILE_ARRAY → VIRTUAL_FILE */
    private fun selectedVirtualFiles(event: AnActionEvent): List<VirtualFile> {
        val selectedFiles = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        if (!selectedFiles.isNullOrEmpty()) {
            return selectedFiles.toList()
        }

        val virtualFile = event.getData(CommonDataKeys.VIRTUAL_FILE)
        return if (virtualFile != null) listOf(virtualFile) else emptyList()
    }
}
