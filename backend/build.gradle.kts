import org.cyclonedx.Version

plugins {
  java
  checkstyle
  id("jacoco")
  id("application")
  id("idea")
  alias(libs.plugins.spring.boot)
  alias(libs.plugins.spring.dependency.management)
  alias(libs.plugins.cyclonedx.bom)
  alias(libs.plugins.spotbugs.base)
  alias(libs.plugins.pitest)
  id("com.diffplug.spotless")
}

group = "de.greluc.krt.profit.basetool"

version = "0.0.1-SNAPSHOT"

description = "backend"

java {
  toolchain { languageVersion = JavaLanguageVersion.of(25) }
  withSourcesJar()
}

// Lombok + the MapStruct annotation processor expose APIs we use at compile
// time as well. Extending compileOnly from annotationProcessor lets `javac`
// see them without dragging them onto the runtime classpath.
configurations { compileOnly { extendsFrom(configurations.annotationProcessor.get()) } }

repositories { mavenCentral() }

dependencies {
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter-webflux")
  implementation("org.springframework.boot:spring-boot-starter-data-jpa")
  implementation("org.springframework.boot:spring-boot-starter-security")
  implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
  implementation("org.springframework.boot:spring-boot-starter-validation")
  implementation("org.springframework.boot:spring-boot-starter-cache")
  // Transactional e-mail (account approval/rejection notices — REQ-NOTIF-013/-014). BOM-managed
  // version; brings JavaMailSender. The channel is dual-gated off by default (app.mail.enabled +
  // spring.mail.host), so no SMTP is contacted in dev/test/CI.
  implementation("org.springframework.boot:spring-boot-starter-mail")
  implementation("com.github.ben-manes.caffeine:caffeine")
  // springdoc -api (NOT -ui): generates the OpenAPI document at /v3/api-docs without bundling the
  // Swagger UI webjar. The committed openapi.json is the single documentation artifact (produced by
  // OpenApiGeneratorTest); /v3/api-docs is additionally disabled in the prod profile so the spec is
  // never reachable from outside a deployed environment.
  implementation(libs.springdoc.openapi.starter.webmvc.api)
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  // Prometheus text-format rendering for /actuator/prometheus (REQ-OBS-005). Version from the
  // Spring Boot BOM. The endpoint itself is guarded by the fail-closed basic-auth chain in
  // MonitoringScrapeSecurityConfig.
  implementation("io.micrometer:micrometer-registry-prometheus")
  // Distributed tracing (REQ-OBS-009, epic #936 Phase 1b): Boot 4's OpenTelemetry starter
  // (Micrometer Tracing on the OTel SDK + OTLP export auto-configuration). Version from the
  // Spring Boot BOM. Inert unless MONITORING_TRACING_ENABLED=true (see application.yml
  // `management.tracing`). micrometer-registry-otlp is excluded: it would activate Boot's OTLP
  // metrics PUSH with a localhost default endpoint in every environment (periodic
  // connection-refused noise) - metrics are exclusively Prometheus PULL via
  // /actuator/prometheus (REQ-OBS-005).
  implementation("org.springframework.boot:spring-boot-starter-opentelemetry") {
    exclude(group = "io.micrometer", module = "micrometer-registry-otlp")
  }
  implementation(libs.bucket4j.core)
  implementation(libs.semver4j.core)
  // Structured JSON logging (LogstashEncoder) for production profile in logback-spring.xml.
  implementation(libs.logstash.logback.encoder)

  // MapStruct for compile-time mappers
  implementation(libs.mapstruct.core)
  annotationProcessor(libs.mapstruct.processor)

  // Database
  runtimeOnly("org.postgresql:postgresql")
  implementation(libs.flyway.core)
  implementation(libs.flyway.postgresql)

  compileOnly("org.projectlombok:lombok")
  annotationProcessor("org.projectlombok:lombok")
  compileOnly(libs.jetbrains.annotations)
  // PDF generation
  implementation(libs.openpdf.core)
  // Ensure MapStruct understands Lombok-generated accessors
  annotationProcessor(libs.lombok.mapstruct.binding)

  developmentOnly("org.springframework.boot:spring-boot-devtools")

  // FindSecBugs: security-focused SpotBugs detector plugin (taint analysis for
  // SQLi / path traversal / SSRF / weak crypto / XXE on our OWN code). Loaded
  // into spotbugsMain via `pluginJarFiles` below. Runs inside the Gradle build,
  // complementing CodeQL (CI-only) with the same class of analysis on every
  // local `check`.
  spotbugsPlugins(libs.findsecbugs.plugin)

  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("org.springframework.boot:spring-boot-test-autoconfigure")
  testImplementation("org.springframework.security:spring-security-test")
  // In-memory OTel span exporter for the tracing tests (REQ-OBS-009): asserts recorded spans
  // without any network export. Version from the Spring Boot BOM (opentelemetry-bom).
  testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
  testImplementation(libs.testcontainers.junit)
  testImplementation(libs.testcontainers.postgresql)
  // ArchUnit core (no archunit-junit5: the latter brings its own JUnit Platform
  // version that clashes with Spring Boot 4's. We invoke `.check(CLASSES)` from
  // plain @Test methods, which is enough for our rule set).
  // Pin the architectural rules from CLAUDE.md (Controllers do not return JPA
  // entities, service-layer code does not touch SecurityContextHolder, REST
  // endpoints are authorisation-annotated, ...). See ArchitectureTest.
  testImplementation(libs.archunit.core)
  // MockWebServer for UexClient WebClient testing (already in version catalog
  // and used by the frontend module; backend gets the same shared version).
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

// Test, JavaCompile and BootRun task setup is shared with the frontend module via
// the `basetool.java-conventions` precompiled script plugin (see buildSrc/).

// SpotBugs task for the main source set. We use the `-base` variant of the
// plugin which does not auto-create tasks, so we register one explicitly and
// wire it into `check`. BLOCKING (`ignoreFailures = false`): a HIGH-confidence
// finding (incl. the FindSecBugs security detectors) fails the build. The
// codebase is currently clean at this level, so the gate starts green.
tasks.register<com.github.spotbugs.snom.SpotBugsTask>("spotbugsMain") {
  group = "verification"
  description = "Runs SpotBugs analysis on the main source set."
  sourceDirs.from(sourceSets.main.get().allSource.sourceDirectories)
  classDirs.from(sourceSets.main.get().output.classesDirs)
  auxClassPaths.from(sourceSets.main.get().compileClasspath)
  // Suppress EI_EXPOSE_REP / EI_EXPOSE_REP2 on JPA entities — see the long
  // architectural justification in the filter file. Keeps the bot's
  // recurring per-commit re-flagging out of PR threads.
  excludeFilter.set(rootProject.file("config/spotbugs/exclude.xml"))
  // Wire the FindSecBugs detectors (declared in the `spotbugsPlugins`
  // configuration) into this manually-registered task. The convention
  // `spotbugsMain` task auto-wires this, but the `-base` plugin variant used
  // here does not, so it is explicit.
  pluginJarFiles.from(configurations.named("spotbugsPlugins"))
  effort.set(com.github.spotbugs.snom.Effort.DEFAULT)
  reportLevel.set(com.github.spotbugs.snom.Confidence.HIGH)
  ignoreFailures = false
  // XML reporter ONLY — do NOT also enable HTML here. SpotBugs 4.9.8 has a
  // multi-output ordering bug: with both reporters configured the plugin
  // passes `-html` before `-xml`, and in that order SpotBugs writes a report
  // with ZERO analyzed classes — i.e. the gate silently scans nothing
  // (verified: html+xml -> total_classes=0; xml-only -> total_classes=768).
  // XML is the canonical machine-readable format (IDE import, quality tooling).
  // Re-add an HTML report only once SpotBugs fixes the ordering.
  reports.create("xml") {
    required.set(true)
    outputLocation.set(layout.buildDirectory.file("reports/spotbugs/main.xml"))
  }
  dependsOn("classes")
}

tasks.named("check").configure { dependsOn("spotbugsMain") }

tasks {
  javadoc {
    options { (this as CoreJavadocOptions).addStringOption("Xdoclint:none", "-quiet") }
    destinationDir = project.file("docs/javadoc")
  }

  cyclonedxBom {
    schemaVersion.set(Version.VERSION_16)
    jsonOutput.set(file("docs/${project.name}-bom.json"))
    xmlOutput.set(file("docs/${project.name}-bom.xml"))
    includeBomSerialNumber = true
    includeLicenseText = true
    includeBuildSystem = true
  }
}
