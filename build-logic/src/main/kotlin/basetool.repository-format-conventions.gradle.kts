import com.diffplug.spotless.LineEnding

plugins { id("com.diffplug.spotless") }

spotless {
  isEnforceCheck = true
  lineEndings = LineEnding.UNIX
  val vendored =
    arrayOf(
      "**/build/**",
      "**/.gradle/**",
      "**/node_modules/**",
      "**/.claude/**",
      "**/.git/**",
      "ansible/collections/**",
      "ansible/.ansible/**",
    )
  fun sources(vararg includes: String) =
    fileTree(rootDir) {
      include(*includes)
      exclude(*vendored)
    }

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
    targetExclude("CHANGELOG.md", "CHANGELOG-ARCHIVE.md", "LICENSE.md", "CLAUDE.md")
    trimTrailingWhitespace()
    endWithNewline()
  }

  format("properties") {
    target(sources("**/*.properties"))
    targetExclude("versions.properties", "**/gradle-wrapper.properties")
    endWithNewline()
  }
}
