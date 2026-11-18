import com.diffplug.spotless.LineEnding
import de.greluc.krt.profit.basetool.conventions.NativeToolPlatform
import de.greluc.krt.profit.basetool.conventions.catalog
import de.greluc.krt.profit.basetool.conventions.registerNativeFormatter
import de.greluc.krt.profit.basetool.conventions.registerNativeLinter
import de.greluc.krt.profit.basetool.conventions.registerNativeTool
import de.greluc.krt.profit.basetool.conventions.requiredVersion

plugins { id("com.diffplug.spotless") }

val vendored =
  arrayOf(
    "**/build/**",
    "**/.gradle/**",
    "**/node_modules/**",
    "**/.claude/worktrees/**",
    ".claude/skills/das-kartell-design/**",
    "**/.git/**",
    "ansible/collections/**",
    "ansible/.ansible/**",
  )

fun sources(vararg includes: String) =
  fileTree(rootDir) {
    include(*includes)
    exclude(*vendored)
  }

spotless {
  isEnforceCheck = true
  lineEndings = LineEnding.UNIX

  kotlinGradle {
    target(sources("**/*.gradle.kts", "build-logic/src/**/*.kt"))
    ktfmt().googleStyle()
  }

  json {
    target(sources("**/*.json"))
    targetExclude(
      "**/src/test/**",
      "**/src/e2e/**",
      "backend/src/main/resources/api/openapi.json",
      "backend/src/main/resources/api/exchange-relay.openapi.json",
      "**/docs/*-bom.json",
      "**/package.json",
      "**/package-lock.json",
      "**/*.local.json",
    )
    gson().indentWithSpaces(2)
  }

  format("yaml") {
    target(sources("**/*.yml", "**/*.yaml"))
    trimTrailingWhitespace()
    endWithNewline()
  }

  format("markdown") {
    target(sources("**/*.md"))
    targetExclude("CHANGELOG.md", "CHANGELOG-ARCHIVE.md", "LICENSE.md", "CLAUDE.md", "AGENTS.md")
    trimTrailingWhitespace()
    endWithNewline()
  }

  format("properties") {
    target(sources("**/*.properties"))
    targetExclude("versions.properties", "**/gradle-wrapper.properties")
    endWithNewline()
  }
}

run {
  val version = catalog().requiredVersion("ruff")
  val ruff =
    registerNativeTool("ruff") {
      when (it) {
        NativeToolPlatform.LINUX_X64 -> "sh.astral:ruff:$version:x86_64-unknown-linux-gnu@tar.gz"
        NativeToolPlatform.WINDOWS_X64 -> "sh.astral:ruff:$version:x86_64-pc-windows-msvc@zip"
      }
    }
  val python = sources("**/*.py").matching { exclude("docs/archive/**") }
  val settings = files("ruff.toml")
  registerNativeFormatter(
    name = "ruffFormat",
    tool = ruff,
    sources = python,
    settings = settings,
    checkArguments = listOf("format", "--config", "ruff.toml", "--check", "--diff"),
    applyArguments = listOf("format", "--config", "ruff.toml"),
  )
  registerNativeLinter(
    name = "ruffLint",
    tool = ruff,
    sources = python,
    settings = settings,
    arguments = listOf("check", "--config", "ruff.toml", "--no-fix", "--output-format", "concise"),
  )
}

run {
  val version = catalog().requiredVersion("shfmt")
  val shfmt =
    registerNativeTool("shfmt") {
      when (it) {
        NativeToolPlatform.LINUX_X64 -> "cc.mvdan:shfmt:$version:linux_amd64@"
        NativeToolPlatform.WINDOWS_X64 -> "cc.mvdan:shfmt:$version:windows_amd64@exe"
      }
    }
  registerNativeFormatter(
    name = "shfmt",
    tool = shfmt,
    sources = sources("**/*.sh", ".githooks/*").matching { exclude("docs/archive/**") },
    settings = files(".editorconfig"),
    checkArguments = listOf("--diff"),
    applyArguments = listOf("--write"),
  )
}
