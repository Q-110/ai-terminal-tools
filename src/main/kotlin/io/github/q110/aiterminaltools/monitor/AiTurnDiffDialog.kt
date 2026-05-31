// 独立 JFrame 窗口展示 AI Turn Diff，保留原生窗口按钮并跟随 IDE 主题。
package io.github.q110.aiterminaltools.monitor

import com.intellij.diff.DiffManager
import com.intellij.diff.requests.DiffRequest
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame
import javax.swing.JPanel

/**
 * 在独立 [JFrame] 中展示本轮 AI 修改。
 *
 * 使用 [JFrame] 是为了保留 Windows 原生最大化/最小化按钮；内容仍由 IntelliJ
 * Diff API 渲染。顶部栏显示本轮修改文件数和文件选择下拉框。
 */
class AiTurnDiffDialog(
    project: Project,
    private val requests: List<DiffRequest>
) {
    private val disposable = Disposer.newDisposable().also {
        Disposer.register(project, it)
    }

    private val parentFrame = WindowManager.getInstance().getFrame(project)
    private val frame = JFrame().apply {
        title = "AI Terminal 本轮修改 - ${requests.size} 个文件"
        iconImage = parentFrame?.iconImage
        isResizable = true
        defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        minimumSize = Dimension(800, 520)
        setSize(1100, 760)
        setLocationRelativeTo(parentFrame)
        contentPane.background = UIUtil.getPanelBackground()
        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent) {
                Disposer.dispose(disposable)
            }
        })
    }
    private val diffPanel = DiffManager.getInstance().createRequestPanel(project, disposable, frame)
    private val fileComboBox = ComboBox(requests.map { it.title }.toTypedArray()).apply {
        isEnabled = requests.size > 1
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
            background = UIUtil.getPanelBackground()
            add(createHeaderPanel(), BorderLayout.NORTH)
            add(diffPanel.component, BorderLayout.CENTER)
        }
        frame.contentPane.add(container, BorderLayout.CENTER)
    }

    fun show() {
        if (requests.isNotEmpty()) {
            diffPanel.setRequest(requests[0])
        }
        frame.isVisible = true
        applyTitleBarTheme(frame)
    }

    private fun createHeaderPanel(): JPanel {
        val header = JPanel(GridBagLayout()).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(8, 10)
        }
        val countLabel = JBLabel("本轮修改 ${requests.size} 个文件").apply {
            foreground = UIUtil.getLabelForeground()
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

    // ---- Windows 标题栏主题 ----

    private fun applyTitleBarTheme(window: Window) {
        if (!SystemInfo.isWin10OrNewer) return
        try {
            val hwnd = Native.getComponentPointer(window)
            val useDarkMode = IntByReference(if (JBColor.isBright()) 0 else 1)
            val result = DwmApi.INSTANCE.DwmSetWindowAttribute(
                hwnd,
                DWMWA_USE_IMMERSIVE_DARK_MODE,
                useDarkMode.pointer,
                BOOL_SIZE
            )
            if (result != 0) {
                DwmApi.INSTANCE.DwmSetWindowAttribute(
                    hwnd,
                    DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1,
                    useDarkMode.pointer,
                    BOOL_SIZE
                )
            }
        } catch (_: Throwable) {
            // 非 Windows 或 JNA/DWM 不可用时使用系统默认标题栏。
        }
    }

    /** JNA 映射 dwmapi.dll 的 DwmSetWindowAttribute。 */
    private interface DwmApi : Library {
        companion object {
            val INSTANCE: DwmApi = Native.load("dwmapi", DwmApi::class.java)
        }

        fun DwmSetWindowAttribute(
            hwnd: Pointer?,
            dwAttribute: Int,
            pvAttribute: Pointer?,
            cbAttribute: Int
        ): Int
    }

    companion object {
        private const val DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1 = 19
        private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
        private const val BOOL_SIZE = 4
    }
}
