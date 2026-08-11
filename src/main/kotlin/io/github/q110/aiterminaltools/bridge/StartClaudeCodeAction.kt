// "启动 Claude Code" 动作 — 创建受插件监控的 Claude Code 终端标签页
package io.github.q110.aiterminaltools.bridge

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class StartClaudeCodeAction : AnAction() {
    /** 工具栏动作只依赖项目上下文，允许在 EDT 更新显示状态 */
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        // 具体终端创建、环境变量注入和 Hook 安装都由桥接服务统一处理。
        val result = AiTerminalBridgeService.getInstance(project).startClaudeCodeTerminal()
        if (result is AiTerminalBridgeService.BridgeResult.Error) {
            AiTerminalBridgeService.notify(project, result.message, NotificationType.WARNING)
        }
    }
}
