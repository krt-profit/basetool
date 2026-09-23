import org.cyclonedx.Version

plugins {
  java
  checkstyle
  id("jacoco")
  alias(libs.plugins.spring.boot)
  alias(libs.plugins.spring.dependency.management)
  alias(libs.plugins.cyclonedx.bom)
  alias(libs.plugins.licensee)
  alias(libs.plugins.spotbugs.base)
  alias(libs.plugins.pitest)
  id("com.diffplug.spotless")
}

// Force :test-support to be evaluated before this project, exactly as backend and frontend do.
// With org.gradle.configureondemand=true it would otherwise be configured lazily, mid-configuration
// of this one, and Spotless cannot register its afterEvaluate hook that late.
evaluationDependsOn(":test-support")

// The same for :logging-support, which this module depends on at runtime (ADR-0205).
evaluationDependsOn(":logging-support")

group = "de.greluc.krt.profit.basetool"

version = "0.0.1-SNAPSHOT"

description = "ingest"

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

// Lombok exposes APIs we use at compile time; extending compileOnly from
// annotationProcessor lets javac see them without putting them on the runtime
// classpath. Mirrors the backend/frontend setup.
configurations { compileOnly { extendsFrom(configurations.annotationProcessor.get()) } }

repositories { mavenCentral() }

dependencies {
  // Web MVC controllers (the two ingest endpoints). The relay to the internal backend and the
  // token grant against Keycloak are blocking `RestClient` calls on the JDK HttpClient
  // (config.RestClientConfig) -- no WebFlux, no Reactor Netty on the internet-facing module
  // (ADR-0204).
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter-security")
  // JWT resource server only — the gateway validates the caller's Keycloak token
  // and forwards it. It is NOT an OAuth2 login client (no session, no cookies).
  implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
  implementation("org.springframework.boot:spring-boot-starter-validation")
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
  // Redis for the short-lived single-use handoff staging (no DB, no JPA).
  implementation("org.springframework.boot:spring-boot-starter-data-redis")
  // Resilience4j circuit breaker (instance `backend`) around the backend relay, applied with
  // CircuitBreaker#executeSupplier -- so no resilience4j-reactor. The relay runs on the request
  // thread, so the correlation id and trace context need no Reactor context propagation either.
  implementation(libs.resilience4j.spring.boot3)
  // Per-IP rate limiting on the new ingress (same library the backend uses).
  implementation(libs.bucket4j.core)
  // springdoc -api (NOT -ui): serves /v3/api-docs in non-prod profiles; no Swagger UI webjar.
  implementation(libs.springdoc.openapi.starter.webmvc.api)
  // Structured JSON logging (LogstashEncoder) for the prod profile in logback-spring.xml.
  implementation(libs.logstash.logback.encoder)
  // The one implementation of LogSafe and the PII maskers the logback configuration names
  // (ADR-0205). `implementation`, not `testImplementation`: it ships inside the boot JAR.
  implementation(project(":logging-support"))

  compileOnly("org.projectlombok:lombok")
  annotationProcessor("org.projectlombok:lombok")
  compileOnly(libs.jetbrains.annotations)

  // The same two toolchains on the TEST source set. The three lines above configure `main` only:
  // Gradle's `testCompileOnly` does NOT extend `compileOnly`, and `testAnnotationProcessor` does
  // not
  // extend `annotationProcessor`, so until these were added the test sources could use neither
  // Lombok nor the JetBrains annotations. That was never a decision -- it was the default
  // source-set wiring -- and it stopped the "maximize Lombok" / "JetBrains annotations wherever
  // they
  // communicate a real contract" conventions (CLAUDE.md `Java conventions`) at the `src/main`
  // boundary. Lombok stays off the runtime classpath here exactly as it does for `main`.
  testCompileOnly("org.projectlombok:lombok")
  testAnnotationProcessor("org.projectlombok:lombok")
  testCompileOnly(libs.jetbrains.annotations)
  // IDE assistance + metadata for the @ConfigurationProperties classes.
  annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

  developmentOnly("org.springframework.boot:spring-boot-devtools")

  // FindSecBugs: security-focused SpotBugs detectors (taint analysis for SSRF /
  // path traversal / weak crypto on our own code). Wired into spotbugsMain below.
  spotbugsPlugins(libs.findsecbugs.plugin)

  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("org.springframework.boot:spring-boot-test-autoconfigure")
  testImplementation("org.springframework.security:spring-security-test")
  // MockWebServer to assert the backend relay forwards bearer + headers correctly.
  testImplementation(libs.okhttp3.mockwebserver)
  // Throwaway certificates for the TLS trust tests of RestClientConfig (HTTPS MockWebServer with a
  // deliberately misnamed certificate). Test-only; generated in memory, never written to disk.
  testImplementation(libs.okhttp3.tls)
  // Testcontainers (generic Redis container) for the handoff staging integration test.
  testImplementation(libs.testcontainers.junit)
  // ArchUnit core (no archunit-junit5: it drags a clashing JUnit Platform version;
  // rules are invoked from plain @Test methods).
  testImplementation(libs.archunit.core)
  // The shared endpoint-enumeration engine (#1804), so IngestEndpointSurfaceTest asks the
  // dispatcher for every mapping exactly the way the backend and frontend surface sweeps do.
  // Test-only: nothing from it reaches the bootJar, the image or the SBOM.
  testImplementation(project(":test-support"))
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Test, JavaCompile, BootRun and JaCoCo setup is shared with the backend/frontend
// modules via the root build.gradle.kts `subprojects { plugins.withId(...) }` blocks.

// SpotBugs task for the main source set. The `-base` plugin variant does not
// auto-create tasks, so we register one explicitly and wire it into `check`.
// BLOCKING (`ignoreFailures = false`): a HIGH-confidence finding fails the build.
tasks.register<com.github.spotbugs.snom.SpotBugsTask>("spotbugsMain") {
  group = "verification"
  description = "Runs SpotBugs analysis on the main source set."
  sourceDirs.from(sourceSets.main.get().allSource.sourceDirectories)
  classDirs.from(sourceSets.main.get().output.classesDirs)
  auxClassPaths.from(sourceSets.main.get().compileClasspath)
  pluginJarFiles.from(configurations.named("spotbugsPlugins"))
  effort.set(com.github.spotbugs.snom.Effort.DEFAULT)
  reportLevel.set(com.github.spotbugs.snom.Confidence.HIGH)
  ignoreFailures = false
  // XML reporter ONLY — the SpotBugs multi-output ordering bug writes a zero-class
  // report when html precedes xml. XML is the canonical machine-readable format.
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

// Restrict the SBOM to the shipped runtime classpath so the signed BOM reflects only what actually
// ships in the bootJar/image — not build/test-scoped components (test, checkstyle, spotbugs,
// annotationProcessor, compileOnly), which otherwise inflate the BOM with build tooling that never
// ships and produce false-positive CVE hits for downstream scanners. The dependency scan runs in
// `cyclonedxDirectBom` (CyclonedxDirectTask, cyclonedx-gradle 3.x); `includeConfigs` is an
// allow-list of configuration-name regexes, so only the resolved runtime graph is enumerated.
tasks.named<org.cyclonedx.gradle.CyclonedxDirectTask>("cyclonedxDirectBom") {
  includeConfigs.set(listOf("^runtimeClasspath$"))
}
