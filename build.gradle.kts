buildscript {
  dependencies {
    constraints {
      classpath(libs.plexus.utils)
      classpath(libs.jackson2.plugins.databind)
    }
  }
}

plugins {
  id("idea")
  id("base")
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

val prometheusConfiguration = layout.projectDirectory.dir("monitoring/prometheus")
val prometheusImage =
  providers
    .fileContents(layout.projectDirectory.file("docker-compose.monitoring.yml"))
    .asText
    .map { compose ->
      Regex("(?m)^    image: (prom/prometheus:\\S+@sha256:[a-f0-9]{64})\\s*$")
        .find(compose)
        ?.groupValues
        ?.get(1) ?: error("The Prometheus compose image must carry a SHA-256 digest")
    }
val prometheusRuleScratch =
  providers
    .gradleProperty("prometheusRuleScratch")
    .map(::File)
    .getOrElse(layout.buildDirectory.dir("tmp/prometheus-rule-tests").get().asFile)

tasks.register<Exec>("testPrometheusRules") {
  val scratch = prometheusRuleScratch
  group = "verification"
  description = "Checks and tests Prometheus alert rules with the deployed image."
  inputs.dir(prometheusConfiguration)
  inputs.property("prometheusImage", prometheusImage)
  doFirst { scratch.mkdirs() }
  commandLine(
    "docker",
    "run",
    "--rm",
    "--network=none",
    "--read-only",
    "--entrypoint=sh",
    "--volume=${prometheusConfiguration.asFile.absolutePath}:/work:ro",
    "--volume=${prometheusRuleScratch.absolutePath}:/worktmp",
    "--env=TMPDIR=/worktmp",
    "--workdir=/work",
    prometheusImage.get(),
    "-c",
    "promtool check rules alerts/*.yml && promtool test rules tests/*_test.yml",
  )
}

val auditOperationsPython =
  providers
    .gradleProperty("auditOperationsPython")
    .getOrElse(
      if (System.getProperty("os.name").lowercase().contains("windows")) "python" else "python3",
    )
val auditOperationsScratch = providers.gradleProperty("auditOperationsScratch")

tasks.register<Exec>("testAuditOperations") {
  group = "verification"
  description = "Exercises synthetic operational audit failure and recovery scenarios."
  inputs.files(fileTree("scripts") { include("*.py", "*.sh", "lib/*.sh", "*.json") })
  inputs.file(".github/scripts/create_signed_commit.py")
  inputs.file("ansible/roles/basetool_host/tasks/23-admin-access.yml")
  inputs.dir("keycloak-theme/krt-theme/login")
  environment("PYTHONDONTWRITEBYTECODE", "1")
  if (auditOperationsScratch.isPresent) {
    val scratch = auditOperationsScratch.get()
    environment("TMPDIR", scratch)
    environment("TEMP", scratch)
    environment("TMP", scratch)
  }
  commandLine(auditOperationsPython, "scripts/test_audit_operations.py")
}
