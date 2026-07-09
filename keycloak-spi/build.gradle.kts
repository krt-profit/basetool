plugins {
  java
  checkstyle
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

  // The Keycloak SPI jars are needed on the TEST classpath too (the unit tests
  // instantiate the factories/providers directly), mirroring the compileOnly set.
  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.junit.jupiter)
  // Mockito to unit-test the authenticator orchestration against a mocked Keycloak flow context.
  // Pinned directly — this module has no Spring Boot dependency-management to supply a version.
  testImplementation("org.mockito:mockito-core:5.14.2")
  testImplementation("org.mockito:mockito-junit-jupiter:5.14.2")
  testImplementation(libs.keycloak.server.spi)
  testImplementation(libs.keycloak.server.spi.private)
  testImplementation(libs.keycloak.services)
  testImplementation(libs.keycloak.core)
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
