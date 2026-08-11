// "显示上次 AI Turn Diff" 动作
package io.github.q110.aiterminaltools.monitor

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import io.github.q110.aiterminaltools.bridge.AiTerminalBridgeService

class ShowLastAiTurnDiffAction : AnAction() {
    /** 该动作读取终端工具窗口的当前选中标签，必须在 EDT 更新状态。 */
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }

    override fun update(event: AnActionEvent) {
        val project = event.project
        event.presentation.isEnabledAndVisible = project != null &&
            project.service<AiTerminalBridgeService>().selectedAiTerminalTabId() != null
    }

    /** 按当前选中 AI 终端的 tabId 重新打开其最近一次 Diff。 */
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val tabId = project.service<AiTerminalBridgeService>().selectedAiTerminalTabId() ?: return
        project.service<AiTurnDiffPresenter>().showLastDiff(tabId)
    }
}
