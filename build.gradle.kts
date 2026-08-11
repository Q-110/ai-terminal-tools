plugins {
    id("org.jetbrains.kotlin.jvm") version "2.2.21"
    id("org.jetbrains.intellij.platform") version "2.12.0"
}

group = "io.github.q110"
version = "0.1.7"

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
            <p><b>Consolidated changes from 0.1.4 through 0.1.7:</b></p>
            <ul>
              <li>Preserves existing Claude Code settings, complex fields, and user hook groups when installing AI Turn Diff hooks.</li>
              <li>Updates only AI Terminal Tools hooks on repeated launches and aborts safely when settings.local.json is unreadable, malformed, or structurally incompatible.</li>
              <li>Releases per-terminal monitoring state, event tokens, active turns, and launcher scripts when Frontend, Reworked, or Classic AI terminal tabs close, startup fails, or the project is disposed.</li>
              <li>Finishes active turns on real tab close while ignoring temporary moves and preventing late HTTP events from restoring released state.</li>
              <li>Silences successful launches, sends, and commit message generation; compatibility fallbacks, skipped binary files, and delayed input-settle failures are logged instead of shown as intermediate balloons.</li>
              <li>Retains one final notification only for actionable launch, send, commit generation, or Diff failures, plus the explicit no-history Diff action.</li>
              <li>Stores the latest AI Turn Diff separately for each OpenCode or Claude Code terminal and exposes the selected terminal's result from the Terminal more-actions menu.</li>
              <li>Clears only the closed terminal's Diff history without affecting other terminal tabs.</li>
              <li>Uses the IntelliJ Platform FrameWrapper for AI Turn Diff windows so the title bar follows the active IDE theme from the first frame.</li>
              <li>Preserves the independent resizable window, native minimize and maximize controls, and per-project window size and position.</li>
              <li>Removes the Windows-specific DWM/JNA title-bar workaround and delegates Diff panel disposal to the platform window lifecycle.</li>
            </ul>
        """.trimIndent()

        ideaVersion {
            sinceBuild = "251"
        }
    }
}
