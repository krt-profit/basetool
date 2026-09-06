import com.github.gradle.node.npm.task.NpxTask
import com.github.gradle.node.task.NodeTask
import org.cyclonedx.Version

plugins {
  java
  checkstyle
  id("jacoco")
  alias(libs.plugins.spring.boot)
  alias(libs.plugins.spring.dependency.management)
  alias(libs.plugins.cyclonedx.bom)
  alias(libs.plugins.spotbugs.base)
  alias(libs.plugins.pitest)
  id("com.diffplug.spotless")
  // Node toolchain for the web-asset linters (ESLint / Stylelint / HTMLHint). The
  // plugin downloads its own Node + npm under `.gradle/nodejs` (download = true
  // below), so neither the developer machine nor the CI runner needs a
  // pre-installed Node — consistent with the "only the Gradle wrapper" rule.
  alias(libs.plugins.node.gradle)
}

description = "frontend"

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

// Resolve the version string that ends up in `META-INF/build-info.properties`
// (consumed by `AppVersionAdvice` to render the sidebar's discreet version
// chip). Priority chain — first non-blank wins:
//
//   1. `-PappVersion=<value>` on the Gradle command line. Used by the CI
//      Docker build, where `.git` is excluded from the build context via
//      `.dockerignore`: the GitHub Actions workflow computes the version on
//      the runner and forwards it as a `--build-arg APP_VERSION=...` which
//      the Dockerfile relays to Gradle via this property.
//   2. `git describe --tags --always --dirty` against the worktree's `.git`.
//      Used by local developer builds, where the host has both `.git` and
//      a `git` binary on PATH. `--tags` matches lightweight tags (the
//      project uses `vX.Y.Z` tags); `--always` falls back to a short SHA
//      when no tag is reachable; `--dirty` appends a marker if the worktree
//      has uncommitted changes so a half-committed build never claims to
//      be the clean tag. Drains stderr to `Redirect.DISCARD` so a chatty
//      git binary cannot block on a full pipe buffer.
//   3. `project.version` (currently `0.0.1-SNAPSHOT`) as the final fallback
//      for Docker builds without an injected `-PappVersion` and for hosts
//      without git installed.
//
// The leading `v` from a canonical tag (e.g. `v0.2.3`) is stripped at the
// end so the sidebar's i18n template `v{0}` does not produce `vv0.2.3`. The
// SNAPSHOT fallback has no `v` prefix and surfaces as `v0.0.1-SNAPSHOT`,
// SHA-only fallback surfaces as `vabc1234` — both consistent with the
// canonical-tag rendering.
val resolvedAppVersion: String by lazy {
  val override = (findProperty("appVersion") as String?)?.takeIf { it.isNotBlank() }
  val gitDescribed: String? =
    runCatching {
        val proc =
          ProcessBuilder("git", "describe", "--tags", "--always", "--dirty")
            .directory(rootDir)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val stdout = proc.inputStream.bufferedReader().readText().trim()
        if (proc.waitFor() == 0 && stdout.isNotBlank()) stdout else null
      }
      .getOrNull()
  val raw = override ?: gitDescribed ?: project.version.toString()
  raw.removePrefix("v")
}

// Generate `META-INF/build-info.properties` at build time so Spring Boot's
// `ProjectInfoAutoConfiguration` auto-wires a `BuildProperties` bean. The bean
// feeds the sidebar's discreet version label (rendered by `AppVersionAdvice`)
// without forcing every Thymeleaf template to read a Gradle-substituted token
// directly. `bootBuildInfo` is wired into `processResources`, so the file is on
// the test/runtime classpath without an extra task dependency.
springBoot {
  buildInfo {
    properties {
      // Override the default `project.version` value with the chain resolved
      // above so the deployed image's sidebar reflects the actual git tag of
      // the commit it was built from.
      version.set(resolvedAppVersion)
    }
  }
}

dependencies {
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter-webflux")
  // Jackson 2 — kept ONLY for ThymeleafJavaScriptSerializerConfig, the JS-inlining bridge that must
  // track Thymeleaf's own Jackson version. Every other frontend class is on Jackson 3
  // (tools.jackson).
  // Thymeleaf 3.1.x (via thymeleaf-spring6) only supports Jackson 2 internally: the bridge
  // delegates
  // primitive values to Thymeleaf's StandardJavaScriptSerializer and mirrors its character-escape
  // table, and it needs the JSR-310 module so [[${dto}]] inline expressions can render java.time.*
  // fields (Instant/OffsetDateTime/LocalDateTime). Drop both deps once Thymeleaf supports Jackson 3
  // and the bridge is migrated — tracked in https://github.com/krt-profit/basetool/issues/294.
  implementation("com.fasterxml.jackson.core:jackson-databind")
  implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
  implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
  implementation("org.springframework.boot:spring-boot-starter-security")
  implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
  // WebSocket for the mission-detail presence/awareness feature: shows in real time which
  // section of a mission another user is currently editing. Native Spring WebSocket
  // (no STOMP) — minimal wire format, no broker required. The in-memory presence store
  // is single-instance only; running multiple frontend replicas would need a Redis-backed
  // fan-out (see MissionPresenceService javadoc).
  implementation("org.springframework.boot:spring-boot-starter-websocket")
  implementation("org.thymeleaf.extras:thymeleaf-extras-springsecurity6")
  // Validation for @ConfigurationProperties validation
  implementation("org.springframework.boot:spring-boot-starter-validation")
  // Caching
  implementation("org.springframework.boot:spring-boot-starter-cache")
  implementation("com.github.ben-manes.caffeine:caffeine")
  // CommonMark renderer for the mission-description markdown (escaped + URL-sanitized HTML).
  implementation(libs.commonmark.core)
  // Actuator for /actuator/health -- consumed by the Docker HEALTHCHECK
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
  // Spring Session with Redis for persistent sessions across restarts
  implementation("org.springframework.session:spring-session-data-redis")
  implementation("org.springframework.boot:spring-boot-starter-data-redis")
  // Resilience4j for resilience patterns + Reactor operators
  implementation(libs.resilience4j.spring.boot3)
  implementation(libs.resilience4j.reactor)
  // Reactor ThreadLocal propagation across WebClient worker threads — required so the
  // active-OrgUnit
  // pin and the correlation id flow from the servlet thread into the WebClient exchange filter.
  // Version is resolved by the Spring Boot BOM (no version.ref here).
  implementation(libs.micrometer.context.propagation)
  implementation(libs.logstash.logback.encoder)

  compileOnly("org.projectlombok:lombok")
  annotationProcessor("org.projectlombok:lombok")
  compileOnly(libs.jetbrains.annotations)
  // Optional: metadata for IDE assistance on configuration properties
  annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

  // FindSecBugs: security-focused SpotBugs detector plugin (taint analysis for
  // SQLi / path traversal / SSRF / weak crypto / XXE on our OWN code). Loaded
  // into spotbugsMain via `pluginJarFiles` below; complements CodeQL (CI-only).
  spotbugsPlugins(libs.findsecbugs.plugin)

  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("org.springframework.boot:spring-boot-test-autoconfigure")
  testImplementation("org.springframework.security:spring-security-test")
  // MockWebServer for HTTP simulations in WebClient tests
  testImplementation(libs.okhttp3.mockwebserver)
  // ArchUnit core (no archunit-junit5 — that pulls in a clashing JUnit Platform
  // version; we invoke `.check(CLASSES)` from plain @Test methods). Enforces the
  // frontend's "no JpaRepository / no direct JDBC" rule.
  testImplementation(libs.archunit.core)
  // Testcontainers for the live-sync Redis pub/sub fan-out integration test (a throwaway
  // redis container + a real Lettuce connection), mirroring the ingest module's pattern.
  testImplementation(libs.testcontainers.junit)
}

// Test, JavaCompile, BootRun and JaCoCo setup is shared with the backend module
// via the root build.gradle.kts `subprojects { plugins.withId(...) }` blocks.

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
  // Wire the FindSecBugs detectors (declared in the `spotbugsPlugins`
  // configuration) into this manually-registered task; the `-base` plugin
  // variant does not auto-wire it.
  pluginJarFiles.from(configurations.named("spotbugsPlugins"))
  effort.set(com.github.spotbugs.snom.Effort.DEFAULT)
  reportLevel.set(com.github.spotbugs.snom.Confidence.HIGH)
  ignoreFailures = false
  // XML reporter ONLY — do NOT also enable HTML here. SpotBugs 4.9.8 has a
  // multi-output ordering bug: with both reporters configured the plugin
  // passes `-html` before `-xml`, and in that order SpotBugs writes a report
  // with ZERO analyzed classes — i.e. the gate silently scans nothing
  // (verified: html+xml -> total_classes=0; xml-only -> total_classes=N).
  // XML is the canonical machine-readable format (IDE import, quality tooling).
  // Re-add an HTML report only once SpotBugs fixes the ordering.
  reports.create("xml") {
    required.set(true)
    outputLocation.set(layout.buildDirectory.file("reports/spotbugs/main.xml"))
  }
  dependsOn("classes")
}

tasks.named("check").configure { dependsOn("spotbugsMain") }

tasks.cyclonedxBom {
  schemaVersion.set(Version.VERSION_16)
  jsonOutput.set(file("docs/${project.name}-bom.json"))
  xmlOutput.set(file("docs/${project.name}-bom.xml"))
  includeBomSerialNumber = true
  includeLicenseText = true
  includeBuildSystem = true
}

// Restrict the SBOM to the shipped runtime classpath so the signed BOM reflects only what actually
// ships in the bootJar/image — not build/test-scoped components (e2e Playwright/Testcontainers,
// test, checkstyle, spotbugs, annotationProcessor, compileOnly), which otherwise inflate the BOM
// with tooling that never runs in production and produce false-positive CVE hits for downstream
// scanners. In cyclonedx-gradle 3.x the dependency scan runs in the `cyclonedxDirectBom` task
// (CyclonedxDirectTask); `includeConfigs` is an allow-list of configuration-name regexes, so only
// the resolved runtime graph is enumerated. The explicit `^e2e.*` skip is kept as belt-and-braces.
tasks.named<org.cyclonedx.gradle.CyclonedxDirectTask>("cyclonedxDirectBom") {
  includeConfigs.set(listOf("^runtimeClasspath$"))
  skipConfigs.set(listOf("^e2e.*"))
}

// Third instance of the cross-module input defect the backend build already fixes twice
// (`crossModuleParitySources`, `apiVhostRunbook`). `DtoMirrorConsistencyTest` lives in this module
// but reads the BACKEND DTO records as source text — there is no compile-time dependency from
// frontend to backend, so none of those files reaches this task's classpath and nothing told
// Gradle that a backend DTO change must re-run the one guard standing between a forgotten frontend
// mirror and a Thymeleaf template that 500s at render time (three red CI runs on
// `feat(mission): registeredCount`, 2026-08-25).
//
// The hole was masked, not harmless — and the distinction is the whole reason to declare it.
// `bootBuildInfo` stamps a fresh `build.time` into `META-INF/build-info.properties` on EVERY
// invocation; that file sits on the test runtime classpath, so the classpath hash changed every
// run and this task was never UP-TO-DATE at all. Measured 2026-09-06: the guard had been executing
// by accident, and the documented `--rerun-tasks` workaround bought nothing. Pin the timestamp with
// one `time.set(…)` — exactly what a reproducible-build change would add — and the defect appears
// in full: task UP-TO-DATE, BUILD SUCCESSFUL, mirror silently out of sync. Verified both ways,
// before and after this declaration.
//
// Declared as the directory rather than file by file, unlike `crossModuleParitySources`: that test
// reads three fixed, named files, while this one derives its read set at RUNTIME — it lists the
// frontend DTO directory and resolves each name against the backend one. A static enumeration
// would go stale the moment a frontend mirror is added, silently re-opening the hole for that
// pair, and a backend DTO with no twin today is read the day one appears. It has to be an input
// before that.
//
// `*.java`, not `**/*.java`: the test uses `Files.list`, so `dto/request/` is never read and must
// not invalidate this task. The frontend half needs no declaration — those sources compile into
// this module's classes, which are already on the test runtime classpath.
val backendDtoMirrorDir = "backend/src/main/java/de/greluc/krt/profit/basetool/backend/model/dto"

// The six sources `LogSafeTest` compares: the guard exists once per module and asserts that the
// marked region of all three `LogSafe.java` implementations, and the expectation table in all
// three test classes, are byte-identical — so a sanitiser fix in one module cannot leave the other
// two logging what it strips. The whole set is declared rather than only the four that live
// elsewhere: two are already covered here by `classes`/`testClasses`, listing them costs nothing,
// and the three build files then carry the identical list instead of three different subsets a
// reader has to reason about.
val logSafeMirrorSources =
  listOf(
    "backend/src/main/java/de/greluc/krt/profit/basetool/backend/support/LogSafe.java",
    "frontend/src/main/java/de/greluc/krt/profit/basetool/frontend/logging/LogSafe.java",
    "ingest/src/main/java/de/greluc/krt/profit/basetool/ingest/logging/LogSafe.java",
    "backend/src/test/java/de/greluc/krt/profit/basetool/backend/support/LogSafeTest.java",
    "frontend/src/test/java/de/greluc/krt/profit/basetool/frontend/logging/LogSafeTest.java",
    "ingest/src/test/java/de/greluc/krt/profit/basetool/ingest/logging/LogSafeTest.java",
  )

tasks.named<Test>("test") {
  inputs
    .files(rootProject.fileTree(backendDtoMirrorDir) { include("*.java") })
    .withPropertyName("backendDtoMirrorSources")
    .withPathSensitivity(PathSensitivity.RELATIVE)

  // Three tests — `DtoOpenApiContractTest`, `FrontendDtoContractTest` and
  // `AdminAuditLogPageControllerTest` — read the BACKEND's committed OpenAPI document off the
  // filesystem, walking up to the repository root to find it. The frontend ships no `api/`
  // resource of its own, so unlike the backend's own generator tests there is no
  // `processResources` copy to track it: without this the frontend mirrors could drift from the
  // server contract with the task reporting UP-TO-DATE.
  inputs
    .file(rootProject.file("backend/src/main/resources/api/openapi.json"))
    .withPropertyName("backendOpenApiDocument")
    .withPathSensitivity(PathSensitivity.RELATIVE)

  // `BackendHealthUrlProdParityTest` compares this module's prod config against the backend's, so
  // the health URL the frontend probes cannot drift from the endpoint the backend actually exposes.
  // Its frontend half is tracked through `processResources`; the backend half is not tracked at
  // all.
  inputs
    .file(rootProject.file("backend/src/main/resources/application-prod.yml"))
    .withPropertyName("backendProdConfig")
    .withPathSensitivity(PathSensitivity.RELATIVE)

  inputs
    .files(logSafeMirrorSources.map { rootProject.file(it) })
    .withPropertyName("logSafeMirrorSources")
    .withPathSensitivity(PathSensitivity.RELATIVE)

  // In-module but still off this task's classpath: `E2eAudienceEnforcementParityTest` lives in
  // `src/test` and reads two files from the `e2e` source set, which `test` neither compiles nor
  // depends on. It asserts that the audience the compose stack hands the backend matches the
  // Keycloak client in the E2E realm export — exactly the pair whose silent divergence makes every
  // E2E flow fail authentication for a reason nothing in the suite explains.
  inputs
    .files(
      rootProject.file(
        "frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/E2eStackExtension.java"
      ),
      rootProject.file("frontend/src/e2e/resources/realm-export.e2e.json"),
    )
    .withPropertyName("e2eAudienceParitySources")
    .withPathSensitivity(PathSensitivity.RELATIVE)
}

// L-2 from the performance audit: minify CSS files inside the built jar so the
// shipped payload is smaller than the readable sources under
// `src/main/resources/static/css/`. The source files stay untouched (so editing
// and diffing remain pleasant) — `minifyStaticCss` runs after `processResources`
// has copied them into `build/resources/main/static/css/` and overwrites those
// copies in place. Wired as a dependency of `classes` so it always runs before
// the jar is assembled and before `bootRun` serves the resources.
//
// The minifier is deliberately conservative: it strips `/* ... */` block
// comments, drops blank lines, and trims leading/trailing whitespace per line.
// It does NOT touch whitespace around selectors or values, because CSS treats a
// space as the descendant combinator (`a :hover` vs `a:hover`) and an
// over-eager regex would silently change selector semantics. The savings target
// is ~25–30 % on `styles.css` (the only big file), which is what the audit
// estimated.
//
// No new build-time dependency was added — a battle-tested minifier
// (yuicompressor / closure-stylesheets) would compress more aggressively, but
// the LOW-priority finding does not justify a new classpath entry. Revisit if
// the CSS surface grows beyond ~200 KB.
tasks.register("minifyStaticCss") {
  group = "build"
  description = "Strips comments and blank lines from CSS files in the resources output (L-2)."
  dependsOn("processResources")

  val resourcesOutputDir = layout.buildDirectory.dir("resources/main/static/css")
  inputs.files(fileTree("src/main/resources/static/css").matching { include("**/*.css") })
  outputs.dir(resourcesOutputDir)

  doLast {
    val cssDir = resourcesOutputDir.get().asFile
    if (!cssDir.exists()) {
      logger.lifecycle("minifyStaticCss: no CSS directory at ${cssDir}; skipping")
      return@doLast
    }
    val blockComment = Regex("""/\*[\s\S]*?\*/""")
    var totalBefore = 0L
    var totalAfter = 0L
    cssDir
      .walkTopDown()
      .filter { it.isFile && it.extension == "css" }
      .forEach { file ->
        val original = file.readText(Charsets.UTF_8)
        val withoutComments = blockComment.replace(original, "")
        val minified =
          withoutComments
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(separator = "\n")
            .plus("\n")
        file.writeText(minified, Charsets.UTF_8)
        totalBefore += original.toByteArray(Charsets.UTF_8).size.toLong()
        totalAfter += minified.toByteArray(Charsets.UTF_8).size.toLong()
      }
    if (totalBefore > 0) {
      val pct = 100.0 * (totalBefore - totalAfter) / totalBefore
      logger.lifecycle(
        "minifyStaticCss: ${totalBefore} -> ${totalAfter} bytes (-${"%.1f".format(pct)}%)"
      )
    }
  }
}

tasks.named("classes").configure { dependsOn("minifyStaticCss") }

// ---------------------------------------------------------------------------
// E2E (Playwright) source set + task — Phase 0 spike (docs/e2e-test/README.md).
//
// Deliberately NOT wired into `check` / `test` / `build`: the suite needs a
// running stack and a downloaded Chromium, so it only runs on an explicit
// `./gradlew :frontend:e2eTest`. The `e2e` source set reuses the test
// dependencies (JUnit 5 + assertions from spring-boot-starter-test) and adds
// the Playwright Java binding on top.
// ---------------------------------------------------------------------------
sourceSets { create("e2e") }

configurations["e2eImplementation"].extendsFrom(configurations["testImplementation"])

configurations["e2eRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

dependencies {
  "e2eImplementation"(libs.playwright)
  // axe-core accessibility engine (Playwright binding) for AccessibilitySmokeE2eTest. Injected and
  // run via Playwright's evaluate, which executes in an isolated world that bypasses the app's
  // strict CSP, so axe runs even though inline page scripts are blocked.
  "e2eImplementation"(libs.axe.core.playwright)
  // PostgreSQL driver for the JDBC catalog seeding (UEX-owned reference data the admin API can't
  // create); version is managed by the Spring Boot BOM.
  "e2eImplementation"("org.postgresql:postgresql")
  // JUnit Platform launcher so the custom Test task can discover Jupiter tests.
  "e2eRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

// Checkstyle auto-creates `checkstyleE2e` and wires it into `check`. E2E code,
// like test code (see the root build's `checkstyleTest` disable), uses
// conventions Google style flags as noise — disable it to keep `check` green.
tasks.matching { it.name == "checkstyleE2e" }.configureEach { enabled = false }

// Installs the Playwright-managed browsers into the per-user cache (~/.cache/ms-playwright).
// Cached in CI; a no-op once present. On a CI Linux runner it additionally installs the browsers'
// OS libraries via `--with-deps` — WebKit needs libs ubuntu-latest lacks and otherwise fails to
// launch with a DriverException. That path uses apt + passwordless sudo, so it is gated to CI
// Linux;
// local runs on any OS just download the browser binaries (a Linux dev installs deps manually).
val playwrightInstall =
  tasks.register<JavaExec>("playwrightInstall") {
    group = "verification"
    description =
      "Installs the Playwright browsers (Chromium, Firefox, WebKit) for e2eTest/smokeTest."
    classpath = sourceSets["e2e"].runtimeClasspath
    mainClass.set("com.microsoft.playwright.CLI")
    val withDeps =
      System.getenv("CI") == "true" &&
        System.getProperty("os.name").orEmpty().lowercase().contains("linux")
    val browsers = listOf("chromium", "firefox", "webkit")
    setArgs(
      if (withDeps) listOf("install", "--with-deps") + browsers else listOf("install") + browsers
    )
  }

// Shared wiring for the two Playwright Test tasks below. Both run from the `e2e` source set with a
// provisioned Chromium and forward the same `e2e.*` knobs; they differ only in the JUnit tag they
// select (and therefore the target they assume). E2E_BASE_URL is read straight from the inherited
// environment by E2eStackExtension, so it needs no forwarding; -Pe2e.baseUrl switches to
// external/staging mode.
val playwrightSuiteConfig: Test.() -> Unit = {
  group = "verification"
  testClassesDirs = sourceSets["e2e"].output.classesDirs
  classpath = sourceSets["e2e"].runtimeClasspath
  dependsOn(playwrightInstall)
  // These flows run against a full stack that E2eStackExtension builds at RUNTIME from the
  // entire app (main code, Thymeleaf templates, Flyway migrations, the backend image) — none of
  // which is a tracked Gradle input here (only the `e2e` source set + classpath are). So a PR
  // that touches only main code leaves those inputs unchanged and the Test task resolves
  // UP-TO-DATE / FROM-CACHE, reporting green WITHOUT ever booting the stack or running a browser
  // (seen on #733/#734). Force a real run whenever these opt-in, label-gated suites are invoked —
  // correctness beats caching for a stack-integration test whose true inputs cannot be hashed.
  outputs.upToDateWhen { false }
  outputs.cacheIf { false }
  // Chromium is provisioned by playwrightInstall; stop Playwright.create() from auto-downloading
  // the full browser set (Firefox + WebKit) on first run.
  environment("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")
  // CI passes credentials through the environment (masked in logs) rather than on the command line;
  // map them onto the e2e.* system properties the tests read. An explicit -P value (below) wins.
  // E2E_BASE_URL needs no mapping — E2eStackExtension reads it straight from the environment.
  mapOf("E2E_USERNAME" to "e2e.username", "E2E_PASSWORD" to "e2e.password").forEach { (env, prop) ->
    System.getenv(env)?.takeIf { it.isNotBlank() }?.let { systemProperty(prop, it) }
  }
  listOf("e2e.baseUrl", "e2e.browser", "e2e.username", "e2e.password", "e2e.hostResolverRules")
    .forEach { key -> (findProperty(key) as String?)?.let { systemProperty(key, it) } }
}

// Full functional flows incl. destructive CRUD; assumes an isolated stack (ephemeral by default).
tasks.register<Test>("e2eTest") {
  description =
    "Runs the destructive Playwright e2e flows against an isolated stack (JUnit tag: e2e)."
  playwrightSuiteConfig()
  useJUnitPlatform { includeTags("e2e") }
}

// Non-destructive login + core-page checks; target-agnostic, safe to run against staging.
tasks.register<Test>("smokeTest") {
  description =
    "Runs the non-destructive Playwright smoke checks (JUnit tag: smoke); set E2E_BASE_URL for staging."
  playwrightSuiteConfig()
  useJUnitPlatform { includeTags("smoke") }
}

// ---------------------------------------------------------------------------
// Web-asset linting: ESLint (JS), Stylelint (CSS), HTMLHint (Thymeleaf HTML).
//
// The Gradle Node plugin downloads a private Node + npm under `.gradle/nodejs`
// (download = true) so no host Node install is required — consistent with the
// "only the Gradle wrapper" rule. `npmInstall` reads the committed
// package.json / package-lock.json and is incremental.
//
// The three lint tasks are wired into `check` and run STRICT
// (ignoreExitValue = false): any finding fails the build. Introduction
// followed the staged SpotBugs pattern — report-only until the existing
// backlog was cleared (ESLint 79 -> 0, Stylelint 348 -> 0, HTMLHint 0), then
// flipped to strict. The vendored, minified JS bundles are excluded in the
// tool configs (eslint.config.mjs / .stylelintrc.json).
// ---------------------------------------------------------------------------
node {
  version.set(libs.versions.node.get())
  download.set(true)
}

val lintCss =
  tasks.register<NpxTask>("lintCss") {
    group = "verification"
    description = "Lints CSS sources with Stylelint (strict; fails the build on findings)."
    dependsOn(tasks.named("npmInstall"))
    command.set("stylelint")
    args.set(listOf("src/main/resources/static/css/**/*.css"))
    ignoreExitValue.set(false)
    inputs.files(fileTree("src/main/resources/static/css") { include("**/*.css") })
    inputs.file("package.json")
    inputs.file(".stylelintrc.json")
  }

val lintHtml =
  tasks.register<NpxTask>("lintHtml") {
    group = "verification"
    description =
      "Lints Thymeleaf HTML templates with HTMLHint (strict; fails the build on findings)."
    dependsOn(tasks.named("npmInstall"))
    command.set("htmlhint")
    args.set(listOf("src/main/resources/templates/**/*.html"))
    ignoreExitValue.set(false)
    inputs.files(fileTree("src/main/resources/templates") { include("**/*.html") })
    inputs.file("package.json")
    inputs.file(".htmlhintrc")
  }

val lintJs =
  tasks.register<NpxTask>("lintJs") {
    group = "verification"
    description =
      "Lints hand-written browser scripts with ESLint (strict; fails the build on findings)."
    dependsOn(tasks.named("npmInstall"))
    command.set("eslint")
    args.set(listOf("src/main/resources/static/js/**/*.js"))
    ignoreExitValue.set(false)
    inputs.files(fileTree("src/main/resources/static/js") { include("**/*.js") })
    inputs.file("package.json")
    inputs.file("eslint.config.mjs")
  }

// Prettier formats the hand-written CSS + JS — the function modern Stylelint and
// ESLint no longer cover (both dropped their stylistic rules and defer formatting
// to a dedicated formatter). It runs through the same node-gradle toolchain as the
// linters above; vendored/minified bundles are skipped via `.prettierignore`.
// `prettierCheck` is strict and wired into `check`; `prettierApply` rewrites in place.
val prettierCheck =
  tasks.register<NpxTask>("prettierCheck") {
    group = "verification"
    description = "Checks CSS/JS formatting with Prettier (strict; fails the build on findings)."
    dependsOn(tasks.named("npmInstall"))
    command.set("prettier")
    args.set(
      listOf(
        "--check",
        "src/main/resources/static/css/**/*.css",
        "src/main/resources/static/js/**/*.js",
        "types/**/*.d.ts",
      )
    )
    ignoreExitValue.set(false)
    inputs.files(fileTree("src/main/resources/static/css") { include("**/*.css") })
    inputs.files(
      fileTree("src/main/resources/static/js") {
        include("**/*.js")
        exclude("vendor/**")
      }
    )
    inputs.files(fileTree("types") { include("**/*.d.ts") })
    inputs.file("package.json")
    inputs.file(".prettierrc.json")
    inputs.file(".prettierignore")
  }

tasks.register<NpxTask>("prettierApply") {
  group = "formatting"
  description = "Reformats the hand-written CSS/JS in place with Prettier."
  dependsOn(tasks.named("npmInstall"))
  command.set("prettier")
  args.set(
    listOf(
      "--write",
      "src/main/resources/static/css/**/*.css",
      "src/main/resources/static/js/**/*.js",
      "types/**/*.d.ts",
    )
  )
  ignoreExitValue.set(false)
}

// ---------------------------------------------------------------------------
// Static type checking of the hand-written browser scripts (ADR-0125).
//
// TypeScript runs here as a CHECKER ONLY (`noEmit` in tsconfig.json): nothing is
// compiled, no bundle is produced, no <script> tag changes and the scripts stay
// classic non-module scripts sharing one global scope (ADR-0069). Files opt in
// individually with a leading `// @ts-check`; everything else is parsed for its
// types but not checked, so the gate was green from the first commit.
//
// The backend DTO shapes come from the OpenAPI spec rather than being restated
// by hand: `generateApiTypes` derives them on every build, so the frontend's
// view of a DTO cannot drift from the contract the backend publishes. The
// generated file is build output and is never committed.
// ---------------------------------------------------------------------------
val openApiSpec = rootProject.file("backend/src/main/resources/api/openapi.json")
val generatedApiTypes = layout.buildDirectory.file("generated/ts/api.d.ts")

// Runs our own zero-dependency emitter rather than `openapi-typescript` (ADR-0130): printing a
// `.d.ts` is printing text, and routing that through the TypeScript compiler API is what pinned
// the whole module to the TS 5.x line once TypeScript 7 removed `ts.factory`. A NodeTask (not
// NpxTask) because the script is ours and has no package to resolve.
val generateApiTypesScript = layout.projectDirectory.file("scripts/gen-api-types.mjs")

val generateApiTypes =
  tasks.register<NodeTask>("generateApiTypes") {
    group = "build"
    description =
      "Generates TypeScript types for the backend DTOs from the OpenAPI spec (build output)."
    dependsOn(tasks.named("npmSetup"))
    script.set(generateApiTypesScript.asFile)
    args.set(listOf(openApiSpec.absolutePath, generatedApiTypes.get().asFile.absolutePath))
    ignoreExitValue.set(false)
    inputs.file(openApiSpec)
    inputs.file(generateApiTypesScript)
    outputs.file(generatedApiTypes)
  }

val typecheckJs =
  tasks.register<NpxTask>("typecheckJs") {
    group = "verification"
    description =
      "Type-checks the opted-in browser scripts with tsc --noEmit (strict; fails on findings)."
    dependsOn(tasks.named("npmInstall"), generateApiTypes)
    command.set("tsc")
    args.set(listOf("-p", "tsconfig.json"))
    ignoreExitValue.set(false)
    inputs.files(
      fileTree("src/main/resources/static/js") {
        include("**/*.js")
        exclude("vendor/**")
      }
    )
    inputs.files(fileTree("types") { include("**/*.d.ts") })
    inputs.file(generatedApiTypes)
    inputs.file("package.json")
    inputs.file("tsconfig.json")
  }

// The emitter replaced a maintained package, so its own correctness is now our problem — and its
// guard (fail on an OpenAPI construct it cannot express) is the load-bearing part: without it an
// unhandled keyword degrades a DTO to `unknown`, which type-checks everywhere and silently voids
// REQ-FE-018's drift protection. Gated in `check` rather than given its own workflow (the pattern
// the root `scripts/*.test.sh` use) because it is a frontend build script and runs in ~200 ms.
val testGenApiTypes =
  tasks.register<NodeTask>("testGenApiTypes") {
    group = "verification"
    description = "Regression tests for the OpenAPI -> .d.ts emitter (scripts/gen-api-types.mjs)."
    dependsOn(tasks.named("npmSetup"))
    script.set(layout.projectDirectory.file("scripts/gen-api-types.test.mjs").asFile)
    ignoreExitValue.set(false)
    inputs.file(generateApiTypesScript)
    inputs.file(layout.projectDirectory.file("scripts/gen-api-types.test.mjs"))
    outputs.upToDateWhen { false }
  }

tasks.named("check").configure {
  dependsOn(lintCss, lintHtml, lintJs, prettierCheck, typecheckJs, testGenApiTypes)
}
