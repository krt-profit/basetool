import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone

buildscript {
  dependencies {
    constraints {
      classpath(libs.commons.lang3)
      classpath(libs.jackson3.databind)
    }
  }
}

plugins {
  java
  checkstyle
  id("jacoco")
  id("idea")
  alias(libs.plugins.spring.boot)
  alias(libs.plugins.spring.dependency.management)
  alias(libs.plugins.cyclonedx.bom)
  alias(libs.plugins.licensee)
  alias(libs.plugins.spotbugs.base)
  alias(libs.plugins.pitest)
  alias(libs.plugins.errorprone)
  id("com.diffplug.spotless")
}

description = "backend"

configurations { compileOnly { extendsFrom(configurations.annotationProcessor.get()) } }

dependencies {
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("tools.jackson.dataformat:jackson-dataformat-cbor")
  implementation("org.springframework.boot:spring-boot-starter-data-jpa") {
    exclude(group = "org.springframework", module = "spring-aspects")
    exclude(group = "org.aspectj", module = "aspectjweaver")
  }
  implementation("org.springframework.boot:spring-boot-starter-security")
  implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
  implementation("org.springframework.boot:spring-boot-starter-validation")
  implementation("org.springframework.boot:spring-boot-starter-cache")
  implementation("org.springframework.boot:spring-boot-starter-data-redis")
  implementation("org.springframework.boot:spring-boot-starter-mail")
  implementation("com.github.ben-manes.caffeine:caffeine")
  implementation(libs.springdoc.openapi.starter.webmvc.api)
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("io.micrometer:micrometer-registry-prometheus")
  implementation("org.springframework.boot:spring-boot-starter-opentelemetry") {
    exclude(group = "io.micrometer", module = "micrometer-registry-otlp")
  }
  implementation(libs.bucket4j.core)
  implementation(libs.semver4j.core)
  implementation(libs.logstash.logback.encoder)
  implementation(project(":logging-support"))

  errorprone(libs.errorprone.core)
  errorprone(libs.nullaway)

  implementation(libs.mapstruct.core)
  annotationProcessor(libs.mapstruct.processor)

  runtimeOnly("org.postgresql:postgresql")
  implementation(libs.flyway.core)
  implementation(libs.flyway.postgresql)

  implementation(libs.openpdf.core)
  compileOnly(libs.spring.modulith.api)
  annotationProcessor(libs.lombok.mapstruct.binding)

  developmentOnly("org.springframework.boot:spring-boot-devtools")

  "mockitoAgent"("org.mockito:mockito-core")

  testImplementation(project(":test-support"))
  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("org.springframework.boot:spring-boot-test-autoconfigure")
  testImplementation("org.springframework.security:spring-security-test")
  testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
  testImplementation(libs.testcontainers.junit)
  testImplementation(libs.testcontainers.postgresql)
  testImplementation(libs.archunit.core)
  testImplementation(libs.spring.modulith.core)
  testImplementation(libs.spring.modulith.docs)
  testImplementation(libs.okhttp3.mockwebserver)
  testImplementation(libs.json.schema.validator) {
    exclude(group = "tools.jackson.dataformat", module = "jackson-dataformat-yaml")
  }
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

idea {
  module {
    inheritOutputDirs = true
    isDownloadJavadoc = true
    isDownloadSources = true
  }
}

tasks.named<com.github.spotbugs.snom.SpotBugsTask>("spotbugsMain") {
  excludeFilter.set(rootProject.file("config/spotbugs/exclude.xml"))
}

val nullAwayPackages =
  listOf(
      "admin",
      "audit",
      "bank",
      "catalogue",
      "exchange",
      "identity",
      "inventory",
      "joborder",
      "livesync",
      "materialexchange",
      "mission",
      "notification",
      "orgunit",
      "personalinventory",
      "platform",
      "privacy",
      "refinery",
      "scope",
    )
    .joinToString(",") { "de.greluc.krt.profit.basetool.backend.$it.api" }

tasks.named<JavaCompile>("compileJava") {
  options.errorprone {
    disableWarningsInGeneratedCode.set(true)
    check("NullAway", CheckSeverity.ERROR)
    disable("StringConcatToTextBlock")
    option("NullAway:AnnotatedPackages", nullAwayPackages)
  }
}

tasks
  .withType<JavaCompile>()
  .matching { it.name != "compileJava" }
  .configureEach {
    options.errorprone.enabled.set(false)
  }

tasks.javadoc {
  options { (this as CoreJavadocOptions).addStringOption("Xdoclint:none", "-quiet") }
  destinationDir = project.file("docs/javadoc")
}

val contractBaseline = layout.buildDirectory.file("contract-baseline/openapi.json")
val relayContractBaseline =
  layout.buildDirectory.file("contract-baseline/exchange-relay.openapi.json")
val contractBaselineRequired =
  providers
    .environmentVariable("CONTRACT_BASELINE_REQUIRED")
    .map { it.trim() == "true" }
    .orElse(false)

tasks.named<Test>("test") {
  inputs
    .files(contractBaseline)
    .withPropertyName("contractBaseline")
    .withPathSensitivity(PathSensitivity.NONE)
  inputs
    .files(relayContractBaseline)
    .withPropertyName("relayContractBaseline")
    .withPathSensitivity(PathSensitivity.NONE)
  inputs.property("contractBaselineRequired", contractBaselineRequired)
  val baseline = contractBaseline
  val relayBaseline = relayContractBaseline
  val baselineRequired = contractBaselineRequired
  jvmArgumentProviders.add(
    CommandLineArgumentProvider {
      listOf(
        "-Dcontract.baseline=" + baseline.get().asFile.absolutePath,
        "-Dcontract.baseline.relay=" + relayBaseline.get().asFile.absolutePath,
        "-Dcontract.baseline.required=" + baselineRequired.get(),
      )
    }
  )

  inputs
    .files(
      rootProject.file(
        "ingest/src/main/java/de/greluc/krt/profit/basetool/ingest/relay/ExchangeRelay.java"
      ),
      rootProject.file(
        "ingest/src/main/java/de/greluc/krt/profit/basetool/ingest/observability/ObservationPrivacyFilter.java"
      ),
      rootProject.file(
        "frontend/src/main/java/de/greluc/krt/profit/basetool/frontend/config/ObservationPrivacyFilter.java"
      ),
    )
    .withPropertyName("crossModuleParitySources")
    .withPathSensitivity(PathSensitivity.RELATIVE)

  inputs
    .dir(rootProject.file("ingest/src/main/resources/exchange/v1/schemas"))
    .withPropertyName("exchangeContractSchemas")
    .withPathSensitivity(PathSensitivity.RELATIVE)
  inputs
    .dir(rootProject.file("docs/exchange/examples"))
    .withPropertyName("exchangeContractFixtures")
    .withPathSensitivity(PathSensitivity.RELATIVE)

  inputs
    .files(
      rootProject.file("docker/edge/include/api-admission.conf"),
      rootProject.file("docker/edge/include/api-allowlist.conf"),
      rootProject.file(".github/workflows/edge-deny-probe.yml"),
      rootProject.file("docker-compose.yml"),
    )
    .withPropertyName("apiVhostAdmission")
    .withPathSensitivity(PathSensitivity.RELATIVE)

  inputs
    .file(
      rootProject.file(
        "frontend/src/main/java/de/greluc/krt/profit/basetool/frontend/websocket/LiveSyncTopicClass.java"
      )
    )
    .withPropertyName("liveSyncTopicRegistrySource")
    .withPathSensitivity(PathSensitivity.RELATIVE)

  inputs
    .file(rootProject.file("quadlet/env.d/backend.env.tmpl"))
    .withPropertyName("backendQuadletEnvTemplate")
    .withPathSensitivity(PathSensitivity.RELATIVE)

  val authzMatrixUpdate = providers.systemProperty("authz.matrix.update").orElse("false")
  inputs.property("authzMatrixUpdate", authzMatrixUpdate)
  jvmArgumentProviders.add(
    CommandLineArgumentProvider { listOf("-Dauthz.matrix.update=" + authzMatrixUpdate.get()) }
  )
}

tasks.register<JavaExec>("generateEdgeAdmission") {
  description =
    "Writes the API vhost's admission map, its include and the nightly probe table from the" +
      " frozen contract set (REQ-API-021)."
  classpath = sourceSets["test"].runtimeClasspath
  mainClass.set("de.greluc.krt.profit.basetool.backend.api.EdgeAdmission")
  args(rootDir.absolutePath)
}
