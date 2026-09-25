// Security override on THIS script's buildscript classpath, where the Spring Boot plugin below is
// loaded: spring-boot-buildpack-platform -> commons-compress 1.27.1 asks for commons-lang3 3.16.0,
// which carries CVE-2025-48924 (Dependabot alert #19). It ships nowhere and was never loaded -- the
// root classpath holds 3.20.0 and is asked first -- but the dependency-submission workflow reports
// every build classpath. The same line sits in backend/build.gradle.kts. The version, the reasoning
// and the removal condition live on `commonsLang3` in the version catalog.
buildscript { dependencies { constraints { classpath(libs.commons.lang3) } } }

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

description = "ingest"

// Group, version, toolchain, repositories, Lombok/JetBrains, the Mockito agent, `spotbugsMain`, the
// SBOM settings come from the root build.gradle.kts
// (`subprojects { plugins.withId(...) }`) and settings.gradle.kts.

// Lombok exposes APIs we use at compile time; extending compileOnly from
// annotationProcessor lets javac see them without putting them on the runtime
// classpath. Mirrors the backend setup.
configurations { compileOnly { extendsFrom(configurations.annotationProcessor.get()) } }

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

  // IDE assistance + metadata for the @ConfigurationProperties classes.
  annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

  developmentOnly("org.springframework.boot:spring-boot-devtools")

  // Attached to every Test JVM as a Java agent by the root build (BLD-PERF-10); the
  // Boot-managed version.
  "mockitoAgent"("org.mockito:mockito-core")

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
  // dispatcher for every mapping exactly the way the backend and frontend surface sweeps do, and
  // the production Redis image the handoff staging test runs against (TST-18).
  // Test-only: nothing from it reaches the bootJar, the image or the SBOM.
  testImplementation(project(":test-support"))
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Test, JavaCompile, BootRun, JaCoCo, SpotBugs and SBOM setup are shared with
// the other modules via the root build.gradle.kts `subprojects { plugins.withId(...) }` blocks.

tasks.javadoc {
  options { (this as CoreJavadocOptions).addStringOption("Xdoclint:none", "-quiet") }
  destinationDir = project.file("docs/javadoc")
}
