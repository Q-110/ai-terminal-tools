// Diff 展示器 — 使用 IntelliJ 原生 Diff API 弹出多文件 Diff 窗口
package io.github.q110.aiterminaltools.monitor

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import io.github.q110.aiterminaltools.bridge.AiTerminalBridgeService
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class AiTurnDiffPresenter(
    private val project: Project
) {
    private val log = Logger.getInstance(AiTurnDiffPresenter::class.java)

    /** 按终端 tabId 保存最近一次可展示的 Turn，避免多个 AI 终端相互覆盖。 */
    private val lastTurnsByTabId = ConcurrentHashMap<String, AiTurnState>()

    /**
     * 展示指定 Turn 中所有被修改文件的 Diff。
     * 在 EDT 中使用 IntelliJ DiffManager 弹出多文件 Diff 窗口。
     * rememberAsLast=false 用于终端关闭时的最终展示，避免已关闭终端重新写入历史记录。
     */
    fun showDiff(turn: AiTurnState, rememberAsLast: Boolean = true) {
        if (turn.changedFiles.isEmpty()) {
            return
        }

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            val requests = buildRequests(turn)
            if (requests.isEmpty()) {
                return@invokeLater
            }

            try {
                // 异步展示前再次确认终端仍处于活动状态，阻止关闭竞态恢复已清理的历史记录。
                if (rememberAsLast && project.service<AiTerminalBridgeService>().isAiTerminalTabActive(turn.tabId)) {
                    lastTurnsByTabId[turn.tabId] = turn
                }
                AiTurnDiffDialog(project, requests).show()
            } catch (exception: Throwable) {
                log.error("Failed to show diff", exception)
                notify("打开 Diff 窗口失败：${exception.message}", NotificationType.WARNING)
            }
        }
    }

    /** 重新打开当前 AI 终端最近一次可展示的 Diff。 */
    fun showLastDiff(tabId: String) {
        val turn = lastTurnsByTabId[tabId]
        if (turn == null) {
            notify("当前 AI 终端没有可显示的 Diff 记录。", NotificationType.INFORMATION)
            return
        }
        showDiff(turn)
    }

    /** 终端关闭或启动失败时删除其 Diff 历史，其他终端记录不受影响。 */
    fun removeLastDiff(tabId: String) {
        lastTurnsByTabId.remove(tabId)
    }

    private fun buildRequests(turn: AiTurnState): List<SimpleDiffRequest> {
        val contentFactory = DiffContentFactory.getInstance()
        val projectBasePath = project.basePath?.let { Path.of(it).normalize() }
        var skippedBinaryCount = 0

        val requests = turn.changedFiles.mapNotNull { path ->
            val oldSnapshot = turn.beforeSnapshots[path] ?: FileSnapshot.Missing
            if (!hasContentChange(path, oldSnapshot)) {
                return@mapNotNull null
            }

            // 跳过二进制文件
            if (oldSnapshot is FileSnapshot.Binary) {
                skippedBinaryCount++
                return@mapNotNull null
            }
            if (oldSnapshot is FileSnapshot.Missing && Files.exists(path)) {
                try {
                    val probe = Files.readAllBytes(path)
                    if (probe.any { it == 0.toByte() }) {
                        skippedBinaryCount++
                        return@mapNotNull null
                    }
                } catch (_: Throwable) {
                    // 读取失败则继续尝试
                }
            }

            val virtualFile = try {
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
            } catch (_: Throwable) {
                null
            }

            val oldContent = when (oldSnapshot) {
                FileSnapshot.Missing -> {
                    contentFactory.createEmpty()
                }
                is FileSnapshot.Text -> {
                    if (virtualFile != null) {
                        contentFactory.create(project, oldSnapshot.text, virtualFile.fileType)
                    } else {
                        contentFactory.create(oldSnapshot.text)
                    }
                }
                is FileSnapshot.Binary -> {
                    // 已被跳过，不会到这里
                    return@mapNotNull null
                }
            }

            val newContent = try {
                if (Files.exists(path)) {
                    if (virtualFile != null) {
                        contentFactory.create(project, virtualFile)
                    } else {
                        contentFactory.create(Files.readString(path))
                    }
                } else {
                    contentFactory.createEmpty()
                }
            } catch (exception: Throwable) {
                log.warn("Failed to read new content for $path", exception)
                contentFactory.createEmpty()
            }

            val displayPath = projectBasePath
                ?.let { base -> runCatching { base.relativize(path).toString() }.getOrNull() }
                ?: path.toString()

            SimpleDiffRequest(
                "AI Terminal 修改：$displayPath",
                oldContent,
                newContent,
                "Before AI turn",
                "After AI turn"
            )
        }

        if (skippedBinaryCount > 0) {
            log.info("AI Turn Diff 已跳过 $skippedBinaryCount 个二进制文件")
        }

        return requests
    }

    private fun hasContentChange(path: Path, oldSnapshot: FileSnapshot): Boolean {
        return when (oldSnapshot) {
            FileSnapshot.Missing -> Files.exists(path) && !Files.isDirectory(path)
            is FileSnapshot.Text -> {
                if (!Files.exists(path)) {
                    true
                } else if (Files.isDirectory(path)) {
                    false
                } else {
                    try {
                        Files.readString(path, oldSnapshot.charset) != oldSnapshot.text
                    } catch (_: Throwable) {
                        true
                    }
                }
            }
            is FileSnapshot.Binary -> true
        }
    }

    private fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(message, type)
            .notify(project)
    }

    companion object {
        private const val NOTIFICATION_GROUP_ID = "AI Terminal Tools"
    }
}
