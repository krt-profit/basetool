import com.github.gradle.node.npm.task.NpxTask
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

// Keep the e2e (Playwright / Testcontainers) source-set dependencies out of the shipped SBOM:
// they are test-only and never enter the bootJar or the published image. In cyclonedx-gradle 3.x
// the dependency scan runs in the `cyclonedxDirectBom` task (CyclonedxDirectTask), which exposes
// `skipConfigs` (regexes matched against configuration names); the aggregate `cyclonedxBom` task
// only formats the output. Skipping every `e2e*` configuration there stops Playwright from
// polluting `frontend-bom.*`.
tasks.named<org.cyclonedx.gradle.CyclonedxDirectTask>("cyclonedxDirectBom") {
  skipConfigs.set(listOf("^e2e.*"))
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
    )
  )
  ignoreExitValue.set(false)
}

tasks.named("check").configure { dependsOn(lintCss, lintHtml, lintJs, prettierCheck) }
