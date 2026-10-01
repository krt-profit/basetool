plugins {
  java
  checkstyle
  id("jacoco")
  alias(libs.plugins.cyclonedx.bom)
  alias(libs.plugins.licensee)
  alias(libs.plugins.spotbugs.base)
  id("com.diffplug.spotless")
}

description = "keycloak-spi"

tasks.withType<JavaCompile>().configureEach { options.release.set(21) }

tasks.withType<Test>().configureEach { systemProperty("net.bytebuddy.experimental", "true") }

dependencies {
  compileOnly(libs.keycloak.server.spi)
  compileOnly(libs.keycloak.server.spi.private)
  compileOnly(libs.keycloak.services)
  compileOnly(libs.keycloak.core)
  compileOnly(platform(libs.protobuf3.bom))

  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.mockito.core)
  testImplementation(libs.archunit.core) { exclude(group = "org.slf4j") }
  testImplementation(libs.mockito.junit.jupiter)
  "mockitoAgent"(libs.mockito.core)
  testImplementation(libs.keycloak.server.spi)
  testImplementation(libs.keycloak.server.spi.private)
  testImplementation(libs.keycloak.services)
  testImplementation(libs.keycloak.core)
  testImplementation(platform(libs.protobuf3.bom))
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
