# AI Terminal Tools 架构说明

本文档说明 AI Terminal Tools 的源码结构、终端兼容层，以及 AI Turn Diff 与 OpenCode / Claude Code 的集成方式。

## 项目结构

```text
src/main/kotlin/io/github/q110/aiterminaltools/
|
├── bridge/                                # 桥接通信：终端交互、右键菜单、拖拽
│   ├── AiTerminalBridgeService.kt         # 核心桥接服务，直接向终端输入区写入内容
│   ├── FrontendTerminalHelper.kt          # 新版前端终端操作工具
│   ├── LegacyReworkedTerminalHelper.kt    # 旧版 Reworked 终端引擎操作工具
│   ├── SendSelectionToAiTerminalAction.kt # Action：发送选中代码到 AI 终端
│   ├── SendPathToAiTerminalAction.kt      # Action：发送文件/文件夹路径到 AI 终端
│   ├── StartOpenCodeAction.kt             # Action：启动 OpenCode 终端会话
│   ├── StartClaudeCodeAction.kt           # Action：启动 Claude Code 终端会话
│   ├── GenerateCommitMessageAction.kt     # Action：通过 AI 终端生成 Git 提交信息
│   ├── AiTerminalDropService.kt           # 拖拽服务：将文件拖入终端时自动发送路径
│   └── AiTerminalToolsMenuRegistrar.kt    # StartupActivity：动态注册右键菜单，初始化服务
|
├── filter/                                # 输出过滤：解析终端文本，生成跳转/复制链接
│   ├── AiTerminalToolsFilter.kt           # 核心 Filter：解析每行输出，生成跳转链接和复制链接
│   ├── AiTerminalToolsFilterProvider.kt   # 向控制台/终端注册核心 Filter
│   ├── FilterPatterns.kt                  # 正则常量：文件引用、@路径引用、点击复制模式
│   └── PathUtils.kt                       # 路径工具：VirtualFile 查找、路径规范化
|
├── jump/                                  # 链接跳转：文件/文件夹链接点击处理
│   ├── FileReferenceHyperlinkInfo.kt      # 文件跳转处理器：点击后定位文件并跳转到指定行
│   ├── FolderReferenceHyperlinkInfo.kt    # 文件夹跳转处理器：点击后在 Project View 中展开
│   └── FileChoiceDialog.kt                # 同名文件选择弹窗：多文件匹配时让用户手动选择
|
├── copy/                                  # 点击复制
│   └── CopyTextHyperlinkInfo.kt           # 点击复制处理器：将文本复制到剪贴板并提示
|
├── console/                               # 控制台错误处理
│   ├── ConsoleErrorBlockParser.kt         # 错误块解析器：识别并提取异常/错误堆栈块
│   └── AiConsoleErrorInlayService.kt      # 错误 Inlay 服务：在错误块旁添加可点击 AI 图标
|
├── monitor/                               # AI Turn Diff：回合事件、快照与 Diff 展示
│   ├── AiTurnEventServer.kt               # 本地 HTTP 服务：接收 OpenCode plugin / Claude hooks 事件
│   ├── AiTurnMonitorService.kt            # Turn 状态机：按 tabId 和 sessionID 维护本轮修改
│   ├── AiTurnOpenCodeInstaller.kt         # OpenCode plugin 与 launcher 生成器
│   ├── AiTurnHookInstaller.kt             # Claude Code hooks 与 launcher 生成器
│   ├── AiTurnSnapshotService.kt           # 文件修改前快照捕获
│   ├── AiTurnDiffPresenter.kt             # 构建并展示本轮文件 Diff
│   ├── AiTurnDiffDialog.kt                # 多文件 Diff 窗口
│   └── ShowLastAiTurnDiffAction.kt        # 重新打开最近一次 AI Turn Diff
|
└── settings/                              # 插件配置
    ├── AiTerminalToolsSettings.kt         # 配置持久化：存储到 ai-terminal-tools.xml
    └── AiTerminalToolsConfigurable.kt     # 设置面板 UI：各功能开关
```

## 终端兼容层

- Frontend：IDE 2025.3+ 使用 `TerminalToolWindowTabsManager`。
- Legacy Reworked：IDE 2025.1 到 2025.2 通过反射调用旧 Reworked Terminal API。
- OpenCode：IDE 2025.1 到 2025.2 使用 Classic Terminal 启动，避免旧 Reworked Terminal 显示异常。
- Classic：回退到 `ShellTerminalWidget` 和 TTY Connector。

## AI Turn Diff 集成

AI Turn Diff 通过本地事件服务、终端专用 launcher、OpenCode plugin 和 Claude Code hooks 协作完成。

OpenCode：

- 启动时生成项目级 `.opencode/plugins/ai-terminal-tools.js` 和当前终端专用 launcher。
- OpenCode plugin 从当前进程环境读取 `AITT_PORT`、`AITT_TOKEN`、`AITT_TAB_ID`。
- 使用 `session.status busy` 作为本轮开始信号。
- 使用 `session.idle` 作为本轮结束信号。

Claude Code：

- 启动时生成 `.claude/settings.local.json` hooks 和当前终端专用 launcher。
- 通过 `UserPromptSubmit`、`PreToolUse`、`PostToolUse`、`Stop` / `StopFailure` 维护本轮状态。

隔离与展示：

- Diff 内容按 `tabId` 和上游 `sessionID` 隔离。
- 多个 OpenCode / Claude Code 终端同时运行时不会串台。
- 本轮结束后由 `AiTurnDiffPresenter` 构建 Diff，并通过 `AiTurnDiffDialog` 展示。
