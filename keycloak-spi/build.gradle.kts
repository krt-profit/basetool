import org.cyclonedx.Version

plugins {
  java
  checkstyle
  // Coverage report + the ratchet in `check`, wired by the root build's `plugins.withId("jacoco")`
  // block exactly as for the three applications. This JAR runs inside the Keycloak JVM on every
  // Discord login, so it is the one shipped module a coverage regression must not slip past.
  id("jacoco")
  alias(libs.plugins.cyclonedx.bom)
  alias(libs.plugins.licensee)
  // SpotBugs + FindSecBugs, the same `-base` variant and the same explicitly registered
  // `spotbugsMain` task as ingest (KC-CI-01). Until 2026-09-22 this module had neither, although
  // it handles brokered Discord tokens, a shared secret and a pinned truststore.
  alias(libs.plugins.spotbugs.base)
  id("com.diffplug.spotless")
}

description = "keycloak-spi"

// The Discord federation provider + first-login membership gate that Keycloak
// loads from /opt/keycloak/providers. A PLAIN library JAR — deliberately NOT a
// Spring Boot module: it runs inside the Keycloak (Quarkus) JVM, not ours, and
// pulls in none of our application stack.
java {
  toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

// CRITICAL: the Keycloak 26.7 container image runs on JDK 21. A provider JAR
// compiled to Java-25 bytecode (class-file major 69) throws
// UnsupportedClassVersionError when Keycloak's JVM (class-file major 65) tries to
// load it. Compile with the repo-standard JDK 25 toolchain but emit Java-21
// bytecode via `--release 21`, so the JAR is loadable by the runtime. Bump this
// in lockstep with the Keycloak image's JDK if a future Keycloak upgrades it.
tasks.withType<JavaCompile>().configureEach { options.release.set(21) }

// This JAR is shipped -- promote.yml pushes the `basetool-keycloak-spi` bundle to production
// alongside the three app images -- so it carries an SBOM like every other shipped module
// (REQ-OPS-025). Same output convention as backend/frontend/ingest: `docs/<module>-bom.{json,xml}`,
// committed, refreshed by release-prepare.yml and attached to the GitHub Release.
tasks.cyclonedxBom {
  schemaVersion.set(Version.VERSION_16)
  jsonOutput.set(file("docs/${project.name}-bom.json"))
  xmlOutput.set(file("docs/${project.name}-bom.xml"))
  includeBomSerialNumber = true
  includeLicenseText = true
  includeBuildSystem = true
}

// Restrict the SBOM to the shipped runtime classpath, exactly as the other three modules do. Here
// that classpath is EMPTY by design and the resulting component list is short on purpose: every
// Keycloak SPI dependency is `compileOnly` because the Keycloak runtime provides it, and the JAR
// bundles no third-party code at all. An empty list is the honest answer to "what does this add to
// the Keycloak JVM?" -- and it turns into a tripwire the day someone writes `implementation`,
// because the component would then appear in the released BOM.
tasks.named<org.cyclonedx.gradle.CyclonedxDirectTask>("cyclonedxDirectBom") {
  includeConfigs.set(listOf("^runtimeClasspath$"))
}

// Byte Buddy (Mockito's backend) does not yet officially support the repo's JDK 25 toolchain; the
// flag lets it proceed (the same compatibility knob Byte Buddy itself recommends). The backend/
// frontend modules avoid this via Spring Boot's newer managed Mockito.
tasks.withType<Test>().configureEach { systemProperty("net.bytebuddy.experimental", "true") }

repositories { mavenCentral() }

dependencies {
  // Keycloak server SPIs — PROVIDED by the Keycloak runtime, never bundled into
  // our JAR (compileOnly). keycloak-services carries AbstractOAuth2IdentityProvider
  // + SimpleHttp; keycloak-server-spi-private carries the Authenticator SPI.
  compileOnly(libs.keycloak.server.spi)
  compileOnly(libs.keycloak.server.spi.private)
  compileOnly(libs.keycloak.services)
  compileOnly(libs.keycloak.core)
  compileOnly(libs.jetbrains.annotations)
  // netty 4.1.x floor for the netty that keycloak-services drags in through Quarkus -- the catalog
  // entry `netty41` carries the CVEs and the removal condition. A platform on compileOnly (and on
  // testImplementation below) adds version constraints only: nothing reaches the provider JAR, its
  // runtimeClasspath or its SBOM, and the Keycloak container keeps running its own netty.
  compileOnly(platform(libs.netty41.bom))
  // The same kind of floor for two more Keycloak-dragged families: protobuf-java 3.25.1
  // (CVE-2024-7254) and OpenTelemetry 1.57.0 (CVE-2026-45292). The catalog entries `protobuf3` and
  // `opentelemetry` carry the chains and the removal conditions; like the netty floor, neither
  // reaches the provider JAR or the Keycloak container.
  compileOnly(platform(libs.protobuf3.bom))
  compileOnly(platform(libs.opentelemetry.bom))

  // FindSecBugs: security-focused SpotBugs detectors, wired into spotbugsMain below. A build-time
  // plugin configuration only -- nothing here reaches the provider JAR or its (empty) SBOM.
  spotbugsPlugins(libs.findsecbugs.plugin)

  // Lombok, on both source sets. `compileOnly` + `annotationProcessor` keeps it out of the JAR
  // exactly as the Keycloak SPIs are kept out -- nothing here reaches the Keycloak JVM, so the
  // module's "bundles no third-party code at all" property (see the SBOM note above) is untouched.
  // The version comes from the catalog rather than a BOM because this module deliberately has no
  // Spring Boot dependency management; the catalog entry explains the lockstep it has to keep.
  compileOnly(libs.lombok)
  annotationProcessor(libs.lombok)
  testCompileOnly(libs.lombok)
  testAnnotationProcessor(libs.lombok)
  testCompileOnly(libs.jetbrains.annotations)

  // The Keycloak SPI jars are needed on the TEST classpath too (the unit tests
  // instantiate the factories/providers directly), mirroring the compileOnly set.
  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.junit.jupiter)
  // Mockito to unit-test the authenticator orchestration against a mocked Keycloak flow context.
  // The version comes from the catalog rather than a BOM because this module deliberately has no
  // Spring Boot dependency management, exactly as with `junit` and `lombok` above; a hardcoded
  // version here was invisible to `./gradlew refreshVersions`.
  testImplementation(libs.mockito.core)
  testImplementation(libs.mockito.junit.jupiter)
  testImplementation(libs.keycloak.server.spi)
  testImplementation(libs.keycloak.server.spi.private)
  testImplementation(libs.keycloak.services)
  testImplementation(libs.keycloak.core)
  testImplementation(platform(libs.netty41.bom))
  testImplementation(platform(libs.protobuf3.bom))
  testImplementation(platform(libs.opentelemetry.bom))
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// SpotBugs task for the main source set, identical to ingest's. The `-base` plugin variant does not
// auto-create tasks, so it is registered explicitly and wired into `check`. BLOCKING
// (`ignoreFailures = false`): a HIGH-confidence finding fails the build. The analysed class files
// are the `--release 21` bytecode Keycloak actually loads.
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
  // XML reporter ONLY — the SpotBugs multi-output ordering bug writes a zero-class report when
  // html precedes xml. XML is the canonical machine-readable format.
  reports.create("xml") {
    required.set(true)
    outputLocation.set(layout.buildDirectory.file("reports/spotbugs/main.xml"))
  }
  dependsOn("classes")
}

tasks.named("check").configure { dependsOn("spotbugsMain") }
