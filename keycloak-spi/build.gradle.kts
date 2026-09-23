plugins {
  java
  checkstyle
  // Coverage report + the ratchet in `check`, wired by the root build's `plugins.withId("jacoco")`
  // block exactly as for the three applications. This JAR runs inside the Keycloak JVM on every
  // Discord login, so it is the one shipped module a coverage regression must not slip past.
  id("jacoco")
  alias(libs.plugins.cyclonedx.bom)
  alias(libs.plugins.licensee)
  // SpotBugs + FindSecBugs, the same `-base` variant and the same `spotbugsMain` task as
  // every other shipped module, registered by the root build (KC-CI-01). Until 2026-09-22
  // this module had neither, although it handles brokered Discord tokens, a shared secret and
  // a pinned truststore. The analysed class files are the `--release 21` bytecode Keycloak
  // actually loads.
  alias(libs.plugins.spotbugs.base)
  id("com.diffplug.spotless")
}

description = "keycloak-spi"

// The Discord federation provider + first-login membership gate that Keycloak
// loads from /opt/keycloak/providers. A PLAIN library JAR — deliberately NOT a
// Spring Boot module: it runs inside the Keycloak (Quarkus) JVM, not ours, and
// pulls in none of our application stack.
//
// The JDK 25 toolchain, repositories, Lombok/JetBrains on both source sets (Lombok at the
// catalog pin, since this module has no Boot BOM), the Mockito agent, `spotbugsMain` and the
// SBOM settings come from the root build.gradle.kts (`subprojects { plugins.withId(...) }`)
// and settings.gradle.kts.
//
// This JAR is shipped -- promote.yml pushes the `basetool-keycloak-spi` bundle to production
// alongside the three app images -- so it carries an SBOM like every other shipped module
// (REQ-OPS-025). Its component list is EMPTY by design: see the root build's CycloneDX block.

// CRITICAL: the Keycloak 26.7 container image runs on JDK 21. A provider JAR
// compiled to Java-25 bytecode (class-file major 69) throws
// UnsupportedClassVersionError when Keycloak's JVM (class-file major 65) tries to
// load it. Compile with the repo-standard JDK 25 toolchain but emit Java-21
// bytecode via `--release 21`, so the JAR is loadable by the runtime. Bump this
// in lockstep with the Keycloak image's JDK if a future Keycloak upgrades it.
tasks.withType<JavaCompile>().configureEach { options.release.set(21) }

// Byte Buddy (Mockito's backend) does not yet officially support the repo's JDK 25 toolchain; the
// flag lets it proceed (the same compatibility knob Byte Buddy itself recommends). The backend/
// frontend modules avoid this via Spring Boot's newer managed Mockito.
tasks.withType<Test>().configureEach { systemProperty("net.bytebuddy.experimental", "true") }

dependencies {
  // Keycloak server SPIs — PROVIDED by the Keycloak runtime, never bundled into
  // our JAR (compileOnly). keycloak-services carries AbstractOAuth2IdentityProvider
  // + SimpleHttp; keycloak-server-spi-private carries the Authenticator SPI.
  compileOnly(libs.keycloak.server.spi)
  compileOnly(libs.keycloak.server.spi.private)
  compileOnly(libs.keycloak.services)
  compileOnly(libs.keycloak.core)
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

  // The Keycloak SPI jars are needed on the TEST classpath too (the unit tests
  // instantiate the factories/providers directly), mirroring the compileOnly set.
  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.junit.jupiter)
  // Mockito to unit-test the authenticator orchestration against a mocked Keycloak flow context.
  // The version comes from the catalog rather than a BOM because this module deliberately has no
  // Spring Boot dependency management, exactly as with `junit` and `lombok`; a hardcoded version
  // here was invisible to refreshVersions. The same jar is the Test JVM's Java agent (BLD-PERF-10).
  testImplementation(libs.mockito.core)
  testImplementation(libs.mockito.junit.jupiter)
  "mockitoAgent"(libs.mockito.core)
  testImplementation(libs.keycloak.server.spi)
  testImplementation(libs.keycloak.server.spi.private)
  testImplementation(libs.keycloak.services)
  testImplementation(libs.keycloak.core)
  testImplementation(platform(libs.netty41.bom))
  testImplementation(platform(libs.protobuf3.bom))
  testImplementation(platform(libs.opentelemetry.bom))
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
