# 🚀 AI Terminal Tools

AI Terminal Tools 是一个 JetBrains IDE 插件，面向终端、控制台和 Commit 面板提供增强能力。核心能力包括：文件跳转、点击复制、AI 终端发送、OpenCode / Claude Code 启动、控制台错误发送、AI Turn Diff，以及提交信息生成。

## ⚡ 快速开始

前置条件：

- JetBrains IDE 2025.1+。
- 已安装 [OpenCode](https://opencode.ai/) 或 [Claude Code](https://docs.anthropic.com/en/docs/claude-code/overview)，并确保 `opencode` 或 `claude` 命令可在系统 `PATH` 中直接运行。
- 从源码运行或构建插件时需要 JDK 17 和 Gradle Wrapper。

基本流程：

1. 点击 IDE 工具栏的“启动 OpenCode”或“启动 Claude Code”。
2. 插件会创建新的终端标签页，注入 `AITT_*` 运行环境，并运行 `opencode` 或 `claude`。
3. 激活目标终端标签页。
4. 在编辑器、控制台、Diff 或只读查看器中发送选区，或从项目视图、编辑器标签页、Commit 面板发送文件路径。
5. 通过插件启动的 OpenCode / Claude Code 终端会自动接入 AI Turn Diff，检测到真实文件内容变化时弹出 Diff 窗口。

> AI Turn Diff 只自动接入通过插件按钮启动的 OpenCode / Claude Code 终端。手动启动的终端仍可接收选区和路径发送，但不会自动安装本轮修改监测所需的 hooks/plugin。

## 📌 功能详解

### 🔗 文件跳转

将终端和控制台输出中的文件引用自动识别为可点击超链接，点击后跳转到 IDE 编辑器中的对应文件位置，支持行号和行范围选中。

支持的文件引用格式：

```text
ExampleController.java
ExampleController.java:22
ExampleController.java:22-30
src/main/java/com/example/ExampleController.java:22
./src/main/java/com/example/ExampleController.java:22-30
../module/src/main/java/com/example/ExampleController.java:22
C:\Projects\demo\src\main\java\com\example\ExampleController.java:22
/projects/demo/src/main/java/com/example/ExampleController.java:22
@src/main/java/com/example/ExampleController.java:10
```

路径解析规则：

- 优先以路径后缀匹配项目文件。
- 支持扩展名：`java`, `kt`, `kts`, `gradle`, `js`, `ts`, `vue`, `html`, `css`, `scss`, `sass`, `less`, `py`, `c`, `cpp`, `cc`, `ps1`, `cmd`, `json`, `toml`, `yaml`, `yml`, `conf`, `env`, `properties`, `xml`, `md`, `sql`。
- `@路径` 引用优先级更高，用于匹配 AI 终端中的路径引用。

当多个文件匹配同一引用时，插件会利用 IntelliJ 项目索引打分排序；若仍有多个候选，弹出选择对话框供用户手动选取。

### 📋 点击复制

终端输出中的结构化文本片段会被识别为可点击链接，点击后自动复制到系统剪贴板，并在链接上方显示“已复制”提示。

支持的复制模式包括 `{{...}}`、`[[...]]`、函数调用、URL、点号链、引号字符串、标识符和数字。点击复制链接不会占用文件跳转链接的区间，两者互不干扰。

> Classic 终端通过鼠标点击检测实现复制，文本无额外样式。

### ⚡ AI 终端发送

将 IDE 编辑器中的选区、文件路径或控制台错误发送到当前激活的 Terminal 输入区。目标终端可以是 OpenCode，也可以是 Claude Code。

发送选区：

- 快捷键：`Ctrl+Alt+,`
- 菜单入口：
  - 编辑器右键 → 发送选区到 AI Terminal
  - 控制台/Run 输出区右键 → 发送选区到 AI Terminal
  - Diff 对比视图右键 → 发送选区到 AI Terminal
  - 只读文本查看器右键 → 发送选区到 AI Terminal
- 发送格式：

```text
@src/main/java/A.java:10-20
-------
<selected code>
-------
```

> 控制台、Diff、只读查看器等无关联文件时，仅发送纯文本内容。

发送文件或文件夹路径：

- 项目视图右键、编辑器标签页右键、Commit 面板变更文件列表右键或拖拽到终端，都会发送为 `@displayPath`。
- 多个文件或文件夹拖拽时，会合并为同一行 `@路径 @路径`，并保留末尾空格以结束补全。
- 插件仅接管通过“启动 OpenCode”或“启动 Claude Code”创建的 AI 终端的拖拽发送。

多行选区和控制台错误使用 bracketed paste 写入，文件路径使用普通输入并自动结束 `@路径` 补全状态。找不到可写终端时，会提示“请先启动并激活 OpenCode 或 Claude Code 终端。”

### 🚨 控制台错误发送

Run/Debug Console 中的多语言错误/异常首行会显示发送图标，点击后自动将当前可见错误段发送到当前激活的 Terminal。

发送范围：

- 从错误首行开始，例如 `Caused by: java.net.ConnectException: Connection timed out: connect`、`Traceback (most recent call last):`、`TypeError: ...`、`panic: ...`、`error[E0599]: ...`。
- 按常见输出格式识别 Java/JVM、Python、JavaScript/Node.js、TypeScript、Go、Rust、Ruby 以及 GCC/Clang C/C++ 编译诊断。
- 包含后续连续的调用栈、traceback、backtrace、编译诊断附属行、源码摘录和插入符定位行。
- 不包含 `<N folded frames>`、`Disconnected from ...` 或 `Process finished ...` 等非错误内容。

发送格式：

```text
控制台错误：
-------
<错误首行和连续错误上下文>
-------
```

可在 Settings → Tools → AI Terminal Tools 中通过“启用控制台错误发送图标”开关启用或关闭。

### 🧾 AI Turn Diff

通过插件启动 OpenCode 或 Claude Code 后，插件会监测每一轮 AI 对话中的文件修改，并在检测到真实文件内容变化时弹出本轮修改 Diff；没有实际变化时会静默跳过。

- OpenCode：启动时生成项目级 `.opencode/plugins/ai-terminal-tools.js` 和当前终端专用 launcher。
- Claude Code：启动时将 AI Turn Diff hooks 合并到 `.claude/settings.local.json` 并生成当前终端专用 launcher；已有配置和 hooks 会保留，配置异常时停止写入。
- Diff 内容按 `tabId` 和上游 `sessionID` 隔离，多个 OpenCode / Claude Code 终端同时运行时不会串台。
- 关闭 AI 终端时会结束活动 Turn、撤销该终端的事件鉴权，并删除其专用 launcher；启动失败和项目关闭也会释放对应资源。
- 每个 AI 终端分别保留自己的最近一次 Diff；选中目标终端后，可通过 Terminal 右上角“三点”菜单 →“显示当前 AI 终端的上次 Diff”重新打开。
- 关闭 AI 终端时只清理该终端的 Diff 历史，不影响其他 OpenCode / Claude Code 标签页。

> 已运行的旧 OpenCode / Claude Code 进程不会自动加载新生成的 plugin 或 hooks。更新插件或代码后，请通过插件按钮重新启动 AI 终端。

### 📝 生成提交信息

Commit 面板工具栏会显示“生成提交信息”动作，点击后使用设置中选择的 OpenCode 或 Claude Code，根据当前 Commit 面板中已勾选的文件生成简要提交信息。提交信息 AI 工具和模型可在下方“设置”部分中配置。

## 🧩 兼容性

| 终端引擎 | 适用范围 | 用途 |
|----------|----------|------|
| Frontend Terminal | IDE 2025.3+ | 优先使用新版终端 API |
| Legacy Reworked Terminal | IDE 2025.1 到 2025.2 | 通过反射兼容旧 Reworked Terminal API |
| Classic Terminal | 回退路径 | 使用 `ShellTerminalWidget` 和 TTY Connector |

## ⚙️ 设置

可在 Settings → Tools → AI Terminal Tools 中配置以下选项：

- 启用控制台错误发送图标：控制 Run/Debug Console 中异常首行的发送图标。
- 启用拖拽文件/文件夹到 AI 终端：控制拖拽接管范围，默认开启。
- 提交信息 AI 工具：选择生成提交信息时使用 OpenCode 还是 Claude Code，默认 OpenCode。
- 提交信息模型：模型按 AI 工具分别保存。OpenCode 填写 `provider/model` 格式；Claude Code 填写 `claude-sonnet-4-6` 完整模型名。留空时分别使用当前工具的默认模型。
- 提交信息附加提示词：填写后使用当前内容作为附加提示词。
- 额外文件扩展名：默认扩展名之外需要识别的扩展名，使用英文分号 `;` 分隔。

## 🛠️ 构建与运行

环境要求：

- JetBrains IDE 2025.1+，或通过 `-P` 参数切换 IDE 类型和版本。
- [JDK 17](https://www.jetbrains.com/help/idea/sdk.html)。
- [Gradle Wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html)。

常用命令：

```powershell
.\gradlew.bat runIde
.\gradlew.bat buildPlugin "-PplatformVersion=2025.1" "-PplatformType=IU"
.\gradlew.bat runIde "-PplatformVersion=2025.3" "-PplatformType=IU"
.\gradlew.bat buildPlugin
```

## 🆕 0.1.7 更新说明（汇总 0.1.4–0.1.7）

- 启动 Claude Code 时无损合并 `.claude/settings.local.json`，保留原有复杂字段、用户 hooks 和同事件下的其他 hook。
- 重复启动时只更新本插件 hook，不重复注册；配置无法读取、JSON 非法或 hooks 结构不兼容时停止安装，不覆盖原文件。
- Frontend、Reworked、Classic AI 终端关闭、最终启动失败或项目销毁时，释放对应 `tabId`、鉴权 token、活动 Turn 和专用 launcher。
- 真实关闭活动终端时完成本轮 Diff 收尾；临时移动或 Detach 不触发清理，迟到 HTTP 事件不能恢复已注销状态。
- 启动、发送和提交信息生成成功后静默完成；终端兼容回退、跳过二进制文件和行尾空格补发失败仅记录日志。
- 仅在最终启动、发送、提交信息生成或 Diff 打开失败等需要处理的场景提示，并保留主动查看但当前终端无历史 Diff 的提示。
- AI Turn Diff 最近记录按 `tabId` 独立保存，多个 OpenCode / Claude Code 终端不再相互覆盖。
- Terminal 右上角“三点”菜单新增“显示当前 AI 终端的上次 Diff”，关闭终端时只清理对应 Diff 历史。
- AI Turn Diff 独立窗口改用 IntelliJ Platform `FrameWrapper`，标题栏从首次显示起跟随当前 IDE 深色或浅色主题。
- 保留独立窗口的最小化、最大化和缩放能力，并由 IDE 记忆窗口尺寸与位置。
- 删除 Windows 专用 DWM/JNA 标题栏适配，Diff 面板资源改由平台窗口生命周期统一释放。

## 📚 开发者文档

项目结构、终端兼容层、OpenCode plugin 和 Claude Code hooks 的实现说明见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## ℹ️ 插件信息

| 项目 | 值 |
|------|-----|
| 插件 ID | `io.github.q110.aiterminaltools` |
| 当前版本 | `0.1.7` |
| Group | `io.github.q110` |
| Vendor | `zibo` |
| 许可证 | [MIT License](https://opensource.org/license/mit/) |

## 📄 许可证

本项目基于 MIT License 开源。详见 [LICENSE](./LICENSE) 文件。
