plugins {
  `java-library`
  checkstyle
  id("com.diffplug.spotless")
  alias(libs.plugins.spring.dependency.management)
}

description = "test-support"

dependencyManagement {
  imports {
    mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}")
  }
}

dependencies {
  api("org.springframework:spring-web")
  api("org.springframework:spring-webmvc")
  api("org.springframework:spring-test")
  api("ch.qos.logback:logback-classic")
  api(libs.archunit.core) { exclude(group = "org.slf4j") }
  compileOnly(libs.testcontainers.core)

  testImplementation("jakarta.servlet:jakarta.servlet-api")
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.testcontainers.core)
  testImplementation("org.assertj:assertj-core")
  testImplementation("org.mockito:mockito-core")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
  inputs
    .files(
      rootProject.file("docker-compose.yml"),
      rootProject.file("quadlet/systemd/redis.container"),
      rootProject.file("quadlet/systemd/db-backend.container"),
      rootProject.file("backend/src/test/resources/application-test.yml"),
      rootProject.file("backend/src/test/resources/testcontainers.properties"),
    )
    .withPropertyName("productionImageSources")
    .withPathSensitivity(PathSensitivity.RELATIVE)
}
