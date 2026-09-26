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
  compileOnly(platform(libs.netty41.bom))
  compileOnly(platform(libs.protobuf3.bom))
  compileOnly(platform(libs.opentelemetry.bom))

  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.junit.jupiter)
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
