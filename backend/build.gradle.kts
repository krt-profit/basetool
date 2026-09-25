buildscript { dependencies { constraints { classpath(libs.commons.lang3) } } }

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

  implementation(libs.mapstruct.core)
  annotationProcessor(libs.mapstruct.processor)

  runtimeOnly("org.postgresql:postgresql")
  implementation(libs.flyway.core)
  implementation(libs.flyway.postgresql)

  implementation(libs.openpdf.core)
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
  testImplementation(libs.okhttp3.mockwebserver)
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

tasks.javadoc {
  options { (this as CoreJavadocOptions).addStringOption("Xdoclint:none", "-quiet") }
  destinationDir = project.file("docs/javadoc")
}

val contractBaseline = layout.buildDirectory.file("contract-baseline/openapi.json")

tasks.named<Test>("test") {
  inputs
    .files(contractBaseline)
    .withPropertyName("contractBaseline")
    .withPathSensitivity(PathSensitivity.NONE)
  val baseline = contractBaseline
  jvmArgumentProviders.add(
    CommandLineArgumentProvider {
      listOf("-Dcontract.baseline=" + baseline.get().asFile.absolutePath)
    }
  )

  inputs
    .files(
      rootProject.file(
        "ingest/src/main/java/de/greluc/krt/profit/basetool/ingest/service/BackendImportClient.java"
      ),
      rootProject.file(
        "ingest/src/main/java/de/greluc/krt/profit/basetool/ingest/config/ObservationPrivacyFilter.java"
      ),
      rootProject.file(
        "frontend/src/main/java/de/greluc/krt/profit/basetool/frontend/config/ObservationPrivacyFilter.java"
      ),
    )
    .withPropertyName("crossModuleParitySources")
    .withPathSensitivity(PathSensitivity.RELATIVE)

  inputs
    .file(rootProject.file("docker/edge/include/api-allowlist.conf"))
    .withPropertyName("apiVhostAllowList")
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
}
