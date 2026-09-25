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

configurations { compileOnly { extendsFrom(configurations.annotationProcessor.get()) } }

dependencies {
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter-security")
  implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
  implementation("org.springframework.boot:spring-boot-starter-validation")
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("io.micrometer:micrometer-registry-prometheus")
  implementation("org.springframework.boot:spring-boot-starter-opentelemetry") {
    exclude(group = "io.micrometer", module = "micrometer-registry-otlp")
  }
  implementation("org.springframework.boot:spring-boot-starter-data-redis")
  implementation(libs.resilience4j.spring.boot3)
  implementation(libs.bucket4j.core)
  implementation(libs.springdoc.openapi.starter.webmvc.api)
  implementation(libs.logstash.logback.encoder)
  implementation(project(":logging-support"))

  annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

  developmentOnly("org.springframework.boot:spring-boot-devtools")

  "mockitoAgent"("org.mockito:mockito-core")

  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("org.springframework.boot:spring-boot-test-autoconfigure")
  testImplementation("org.springframework.security:spring-security-test")
  testImplementation(libs.okhttp3.mockwebserver)
  testImplementation(libs.okhttp3.tls)
  testImplementation(libs.testcontainers.junit)
  testImplementation(libs.archunit.core)
  testImplementation(project(":test-support"))
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.javadoc {
  options { (this as CoreJavadocOptions).addStringOption("Xdoclint:none", "-quiet") }
  destinationDir = project.file("docs/javadoc")
}
