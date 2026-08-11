// 独立 IDE Frame 窗口展示 AI Turn Diff，由平台统一管理标题栏主题和窗口生命周期。
package io.github.q110.aiterminaltools.monitor

import com.intellij.diff.DiffManager
import com.intellij.diff.requests.DiffRequest
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.FrameWrapper
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JPanel

/**
 * 在独立 [FrameWrapper] 中展示本轮 AI 修改。
 *
 * FrameWrapper 使用 IDE 的窗口装饰并保留原生最大化/最小化按钮；内容由 IntelliJ
 * Diff API 渲染。顶部栏显示本轮修改文件数和文件选择下拉框。
 */
class AiTurnDiffDialog(
    project: Project,
    private val requests: List<DiffRequest>
) {
    private val frameWrapper = FrameWrapper(project, DIMENSION_SERVICE_KEY, false).apply {
        title = "AI Terminal 本轮修改 - ${requests.size} 个文件"
        WindowManager.getInstance().getFrame(project)?.iconImage?.let(::setImage)
    }
    private val window = frameWrapper.getFrame()
    private val diffPanel = DiffManager.getInstance()
        .createRequestPanel(project, frameWrapper, window)
    private val fileComboBox = ComboBox(requests.map { it.title }.toTypedArray()).apply {
        isEnabled = requests.size  > 1
        toolTipText = "选择本轮修改的文件"
    }

    private var currentIndex = 0

    init {
        fileComboBox.addActionListener {
            val index = fileComboBox.selectedIndex
            if (index >= 0 && index < requests.size && index != currentIndex) {
                currentIndex = index
                diffPanel.setRequest(requests[index])
            }
        }

        val container = JPanel(BorderLayout()).apply {
            preferredSize = JBUI.size(1100, 760)
            minimumSize = JBUI.size(800, 520)
            add(createHeaderPanel(), BorderLayout.NORTH)
            add(diffPanel.component, BorderLayout.CENTER)
        }
        frameWrapper.component = container
        frameWrapper.preferredFocusedComponent = diffPanel.component
        window.minimumSize = JBUI.size(800, 520)
    }

    /** 设置首个 Diff 请求并显示由 IDE 主题装饰的独立窗口。 */
    fun show() {
        if (requests.isNotEmpty()) {
            diffPanel.setRequest(requests[0])
        }
        frameWrapper.show()
    }

    /** 创建使用 IDE 默认前景色和背景色的文件选择顶部栏。 */
    private fun createHeaderPanel(): JPanel {
        val header = JPanel(GridBagLayout()).apply {
            border = JBUI.Borders.empty(8, 10)
        }
        val countLabel = JBLabel("本轮修改 ${requests.size} 个文件").apply {
            border = JBUI.Borders.emptyRight(10)
        }

        header.add(countLabel, GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            weightx = 0.0
            fill = GridBagConstraints.NONE
            anchor = GridBagConstraints.WEST
        })
        header.add(fileComboBox, GridBagConstraints().apply {
            gridx = 1
            gridy = 0
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
        })
        return header
    }

    companion object {
        private const val DIMENSION_SERVICE_KEY = "AiTerminalTools.AiTurnDiffDialog"
    }
}
