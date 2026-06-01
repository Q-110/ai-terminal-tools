plugins {
    id("org.jetbrains.kotlin.jvm") version "2.2.21"
    id("org.jetbrains.intellij.platform") version "2.12.0"
}

group = "io.github.q110"
version = "0.1.3"

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

val platformVersion = providers.gradleProperty("platformVersion").getOrElse("2025.1")
val platformType = providers.gradleProperty("platformType").getOrElse("IU")

dependencies {
    intellijPlatform {
        when (platformType.uppercase()) {
            "IC", "IU" -> intellijIdea(platformVersion)
            "PY"       -> pycharm(platformVersion)
            "WS"       -> webstorm(platformVersion)
            "GO"       -> goland(platformVersion)
            "PS"       -> phpstorm(platformVersion)
            "RM"       -> rubymine(platformVersion)
            "RD"       -> rider(platformVersion)
            "CL"       -> clion(platformVersion)
            "DG"       -> datagrip(platformVersion)
            else       -> intellijIdea(platformVersion)
        }
        bundledPlugin("org.jetbrains.plugins.terminal")
    }
}

intellijPlatform {
    publishing {
        token.set(providers.environmentVariable("PUBLISH_TOKEN"))
        channels.set(listOf("default"))
    }

    signing {
        certificateChain.set(providers.environmentVariable("CERTIFICATE_CHAIN"))
        privateKey.set(providers.environmentVariable("PRIVATE_KEY"))
        password.set(providers.environmentVariable("PRIVATE_KEY_PASSWORD"))
    }

    pluginConfiguration {
        id = "io.github.q110.aiterminaltools"
        name = "AI Terminal Tools"
        version = project.version.toString()
        description = """
            <p>AI Terminal Tools enhances JetBrains IDE terminals, consoles, and the Commit panel with fast navigation, click-to-copy, AI terminal sending, OpenCode / Claude Code launch actions, AI Turn Diff, and commit message generation.</p>
            <p>Designed for JetBrains IDEs 2025.1+ and compatible with Frontend, Reworked, and Classic terminal paths.</p>
            <ul>
              <li>Jump from terminal or console file references to editor locations, including line numbers and line ranges.</li>
              <li>Copy structured terminal output fragments such as URLs, method calls, dotted identifiers, strings, and numbers with one click.</li>
              <li>Send editor selections, file paths, dragged files, and console error blocks to the active OpenCode or Claude Code terminal.</li>
              <li>Start OpenCode or Claude Code in dedicated terminal tabs with the environment needed for AI Turn Diff.</li>
              <li>Review files with actual content changes from each AI turn in an IntelliJ diff window.</li>
              <li>Generate concise commit messages from checked files in the Commit panel, with configurable AI tool, model, and extra prompt.</li>
            </ul>
        """.trimIndent()
        changeNotes = """
            <ul>
              <li>修复 OpenCode 只读取文件时也会弹出 AI Turn Diff 窗口的问题。</li>
              <li>OpenCode 现在只会对 edit、write、apply_patch 等写入类工具记录变更。</li>
              <li>AI Turn Diff 会在展示前过滤前后内容完全相同的文件，没有真实文件内容变化时不再弹出 Diff 窗口。</li>
              <li>跳过项目根目录和目录路径，避免 Diff 文件列表出现无文件名条目。</li>
            </ul>
        """.trimIndent()

        ideaVersion {
            sinceBuild = "251"
        }
    }
}
