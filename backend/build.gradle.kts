// Security override on THIS script's buildscript classpath, where the Spring Boot plugin below is
// loaded: spring-boot-buildpack-platform -> commons-compress 1.27.1 asks for commons-lang3 3.16.0,
// which carries CVE-2025-48924 (Dependabot alert #19). It ships nowhere and was never loaded -- the
// root classpath holds 3.20.0 and is asked first -- but the dependency-submission workflow reports
// every build classpath. The same line sits in ingest/build.gradle.kts. The version, the reasoning
// and the removal condition live on `commonsLang3` in the version catalog.
buildscript { dependencies { constraints { classpath(libs.commons.lang3) } } }

plugins {
  java
  checkstyle
  id("jacoco")
  // No `application` plugin and no `withSourcesJar()` (audit item BLD-PERF-02): the image ships the
  // Spring Boot jar, and nothing consumed the distZip/distTar/bootDistZip/bootDistTar archives or
  // the sources jar that every `build` assembled -- no workflow, no Dockerfile, no SBOM.
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

// Group, version, toolchain, repositories, Lombok/JetBrains, the Mockito agent, `spotbugsMain`, the
// SBOM settings come from the root build.gradle.kts
// (`subprojects { plugins.withId(...) }`) and settings.gradle.kts.

// Lombok + the MapStruct annotation processor expose APIs we use at compile
// time as well. Extending compileOnly from annotationProcessor lets `javac`
// see them without dragging them onto the runtime classpath.
configurations { compileOnly { extendsFrom(configurations.annotationProcessor.get()) } }

dependencies {
  implementation("org.springframework.boot:spring-boot-starter-web")
  // No spring-boot-starter-webflux (ADR-0204): every outbound call -- UEX, SC Wiki, the Keycloak
  // Admin API -- is blocking and goes through `RestClient` on the JDK HttpClient
  // (config.RestClientConfig), so WebFlux and Reactor Netty are off the runtime classpath.
  // CBOR on the frontend<->backend hop (ADR-0161 §8.5). No version: the Spring Boot BOM already
  // manages tools.jackson:jackson-bom, and pinning a second one here is how the two Jackson 3
  // module sets drift apart. Its only job is to be PRESENT -- Spring Framework 7 detects
  // `tools.jackson.dataformat.cbor.CBORMapper` on the classpath and registers the CBOR message
  // converter on its own, so no wiring follows from this line. Which side actually asks for CBOR
  // is `app.http.codec` on the frontend, and nothing else asks at all.
  implementation("tools.jackson.dataformat:jackson-dataformat-cbor")
  // AspectJ is excluded for its licence, not its size (ADR-0197). `spring-boot-data-jpa` pulls
  // `spring-aspects` -> `aspectjweaver`, whose jar is `EPL-2.0 AND BSD-3-Clause AND Apache-1.1`
  // (its own LICENSE-AspectJ.adoc): EPL-2.0 with no GPL Secondary License, plus a modified BCEL
  // under Apache-1.1 — neither may be redistributed inside a GPL-3.0-only image. Nothing here
  // needs it: no `@Aspect`, `@Configurable`, `@Timed` or `@Observed`, no load-time weaving.
  // `@Transactional`, `@PreAuthorize`, `@Cacheable`, `@Async` and `@Scheduled` are proxy-based
  // infrastructure advisors, which Boot's AopAutoConfiguration keeps applying without AspectJ
  // (ClassProxyingConfiguration). The licence gate refuses EPL-2.0 on its own, so a new path that
  // brings AspectJ back fails `:backend:licensee` instead of shipping silently.
  implementation("org.springframework.boot:spring-boot-starter-data-jpa") {
    exclude(group = "org.springframework", module = "spring-aspects")
    exclude(group = "org.aspectj", module = "aspectjweaver")
  }
  implementation("org.springframework.boot:spring-boot-starter-security")
  implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
  implementation("org.springframework.boot:spring-boot-starter-validation")
  implementation("org.springframework.boot:spring-boot-starter-cache")
  // Redis for the cross-replica notification SSE pub/sub fan-out (REQ-FE-015, ADR-0093). Brings
  // Lettuce + StringRedisTemplate. The fan-out is property-gated off by default (see
  // NotificationRedisConfig / app.notifications.redis-fanout.enabled) and the Redis health
  // indicator is disabled so no Redis is contacted in dev/test/CI; only prod wires it.
  implementation("org.springframework.boot:spring-boot-starter-data-redis")
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
  // The one implementation of LogSafe and the PII maskers the logback configuration names
  // (ADR-0205). `implementation`, not `testImplementation`: it ships inside the boot JAR.
  implementation(project(":logging-support"))

  // MapStruct for compile-time mappers
  implementation(libs.mapstruct.core)
  annotationProcessor(libs.mapstruct.processor)

  // Database
  runtimeOnly("org.postgresql:postgresql")
  implementation(libs.flyway.core)
  implementation(libs.flyway.postgresql)

  // PDF generation
  implementation(libs.openpdf.core)
  // Ensure MapStruct understands Lombok-generated accessors
  annotationProcessor(libs.lombok.mapstruct.binding)

  developmentOnly("org.springframework.boot:spring-boot-devtools")

  // Attached to every Test JVM as a Java agent by the root build (BLD-PERF-10); the version is the
  // Boot-managed one, the same mockito-core that spring-boot-starter-test puts on the classpath.
  "mockitoAgent"("org.mockito:mockito-core")

  // The endpoint-enumeration engine behind AnonymousSurfaceSweep* (#1804), shared with the other
  // module's sweep so a defect in it cannot blind both guards at once. Test-scoped: nothing from
  // that module reaches a runtime classpath, an image or an SBOM.
  testImplementation(project(":test-support"))
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
  // MockWebServer for the UEX / SC-Wiki / Keycloak RestClient tests (already in version catalog
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

// Test, JavaCompile, BootRun, JaCoCo, SpotBugs and SBOM setup is shared with the other modules via
// the root build.gradle.kts `subprojects { plugins.withId(...) }` blocks (there is no buildSrc).

// The one backend-specific part of `spotbugsMain`: suppress EI_EXPOSE_REP / EI_EXPOSE_REP2 on JPA
// entities — see the long architectural justification in the filter file. Keeps the bot's recurring
// per-commit re-flagging out of PR threads.
tasks.named<com.github.spotbugs.snom.SpotBugsTask>("spotbugsMain") {
  excludeFilter.set(rootProject.file("config/spotbugs/exclude.xml"))
}

tasks.javadoc {
  options { (this as CoreJavadocOptions).addStringOption("Xdoclint:none", "-quiet") }
  destinationDir = project.file("docs/javadoc")
}

// Cross-module parity tests read the OTHER modules' sources directly (they cannot see those classes
// on the test classpath). Gradle knows nothing about that, so after a change in `ingest` or
// `frontend` it considers `:backend:test` up-to-date and the parity test simply does not run —
// which was verified the hard way: renaming the on-behalf-of header in the gateway left the test
// green until `--rerun-tasks` forced it. A parity test that does not re-run is not a parity test.
//
// Declared file by file rather than as whole source trees: this must invalidate on the handful of
// files the assertions actually read, not on every Java change in two other modules.

// Where the CI step drops the previous release's openapi.json for the second half of
// REQ-API-009's schema diff (ADR-0136, ADR-0161 8.4). The path is ALWAYS handed to the test JVM;
// ExternalContractTest#theContractTypesMatchThePreviousRelease skips when the file is not there,
// which is every local run.
//
// `inputs.files(...)`, NOT `inputs.file(...).optional(true)`. `optional` only permits an absent
// PROVIDER VALUE, and `layout.buildDirectory.file(...)` always has one -- so Gradle still validated
// the path and failed the task outright with "Input file does not exist" on every machine without a
// baseline. That is `:backend:test`, `test`, `check` and `build` all failing before a single test
// runs, which is the exact opposite of the skip this wiring exists for. A FileCollection tolerates
// missing entries, and it is the idiom the cross-module parity inputs below already use.
val contractBaseline = layout.buildDirectory.file("contract-baseline/openapi.json")

tasks.named<Test>("test") {
  inputs
    .files(contractBaseline)
    .withPropertyName("contractBaseline")
    .withPathSensitivity(PathSensitivity.NONE)
  // Through an argument provider rather than `systemProperty`, because a system property is an
  // @Input: an absolute, machine-specific path in the cache key would defeat the
  // PathSensitivity.NONE chosen one line above and make the task non-relocatable. The provider
  // carries no input annotation, so the PATH contributes nothing to the key while the file's
  // CONTENT still does.
  //
  // Copied into a local first: a script-level `val` read from inside the lambda is a reference to
  // the build script object, which the configuration cache refuses to serialise.
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

  // Same defect, one directory further out. `ExternalContractTest` parses the API vhost allow-list
  // at runtime (`findRepoRoot().resolve("docker/edge/include/api-allowlist.conf")`) to assert that
  // every frozen REQ-API-009 operation is admitted by it. That file is not a source, not a resource
  // and not on any classpath, so an edit that touches ONLY the allow-list left this task
  // UP-TO-DATE: the run printed BUILD SUCCESSFUL in seconds and the guard never executed. (Until
  // 2026-09-22 the test read the allow-list's copy inside the API vhost rollout runbook, now
  // archived; the include has been the source of truth since 2026-09-12.)
  //
  // That is the worst shape a false green can take here, because the assertion it silences is the
  // one connecting the allow-list to the frozen contract set — a rule deleted from the allow-list
  // would pass locally and only fail on a fresh CI checkout, which has no cached output to trust.
  //
  // The document itself needs no declaration: the test reads `/api/openapi.json` off the CLASSPATH,
  // so `processResources` already tracks it.
  inputs
    .file(rootProject.file("docker/edge/include/api-allowlist.conf"))
    .withPropertyName("apiVhostAllowList")
    .withPathSensitivity(PathSensitivity.RELATIVE)

  // `LiveSyncTopicRegistryParityTest` reads the frontend's `LiveSyncTopicClass` enum as source and
  // asserts that every backend live-sync topic has a frontend constant to route it. Same shape as
  // the parity sources above, same reason it must be declared: a frontend-only rename would leave
  // this task UP-TO-DATE and the routing table silently half-updated.
  inputs
    .file(
      rootProject.file(
        "frontend/src/main/java/de/greluc/krt/profit/basetool/frontend/websocket/LiveSyncTopicClass.java"
      )
    )
    .withPropertyName("liveSyncTopicRegistrySource")
    .withPathSensitivity(PathSensitivity.RELATIVE)

  // `JwkSetUriNamespaceTest` asserts that the backend's generated Quadlet environment passes
  // KEYCLOAK_JWK_SET_URI (REQ-SEC-024). Off the classpath like the files above, so a compose edit
  // that dropped the line would otherwise leave this task UP-TO-DATE and the check unrun.
  inputs
    .file(rootProject.file("quadlet/env.d/backend.env.tmpl"))
    .withPropertyName("backendQuadletEnvTemplate")
    .withPathSensitivity(PathSensitivity.RELATIVE)
}
