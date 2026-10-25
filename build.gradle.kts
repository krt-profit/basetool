buildscript {
  dependencies {
    constraints {
      classpath(libs.plexus.utils)
      classpath(libs.jsoup)
      classpath(libs.jackson2.plugins.databind)
    }
  }
}

plugins {
  id("idea")
  id("base")
  alias(libs.plugins.owasp.dependencycheck)
  id("basetool.repository-format-conventions")
}

allprojects {
  group = "de.greluc.krt.profit.basetool"
  version =
    providers
      .gradleProperty("releaseVersion")
      .filter { it.isNotBlank() }
      .getOrElse("0.0.1-SNAPSHOT")
}

tasks.register("generateTermsVersion") {
  description = "Regenerates the committed Terms-of-Use version from the German message bundle."
  val bundle = rootProject.file("backend/src/main/resources/messages_de.properties")
  val target = rootProject.file("backend/src/main/resources/terms-version.properties")
  val override = providers.gradleProperty("termsVersion").filter { it.isNotBlank() }
  val targetForLog = target.relativeTo(rootDir).path
  outputs.upToDateWhen { false }
  doLast {
    val version =
      override.orNull
        ?: run {
          val clauses =
            bundle
              .readLines(Charsets.UTF_8)
              .filter { it.startsWith("terms.") && it.contains('=') }
              .sorted()
              .joinToString(separator = "\n")
          check(clauses.isNotEmpty()) { "No terms.* entries found in ${bundle.path}" }
          check(clauses.lines().none { it.endsWith("\\") }) {
            "A terms.* entry in ${bundle.path} uses a backslash line continuation. The digest " +
              "only sees the first line, so edits to the rest would not re-prompt anyone. Put " +
              "the clause on one line."
          }
          java.security.MessageDigest.getInstance("SHA-256")
            .digest(clauses.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .substring(0, 16)
        }
    target.parentFile.mkdirs()
    target.writeText("basetool.terms.version=" + version + "\n", Charsets.UTF_8)
    logger.lifecycle("Terms-of-Use version: $version -> $targetForLog")
  }
}

dependencyCheck {
  failBuildOnCVSS = 7.0f
  formats = listOf("HTML", "SARIF")
  outputDirectory.set(layout.buildDirectory.dir("reports/dependency-check"))
  suppressionFile = rootProject.file("config/owasp/dependency-check-suppressions.xml").absolutePath
  setConnectionTimeout(java.time.Duration.ofSeconds(30))
  setReadTimeout(java.time.Duration.ofSeconds(120))
  nvd.validForHours = 168
  nvd.datafeedUrl = "https://nvd.nist.gov/feeds/json/cve/2.0/nvdcve-2.0-{0}.json.gz"
  val resolvedNvdApiKey = (project.findProperty("nvdApiKey") as String?)?.takeIf { it.isNotBlank() }
  if (resolvedNvdApiKey != null) {
    nvd.apiKey = resolvedNvdApiKey
    nvd.delay = 0
  } else {
    nvd.delay = 16000
  }
}
