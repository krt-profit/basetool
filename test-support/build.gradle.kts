plugins {
  `java-library`
  id("basetool.java-conventions")
  id("basetool.checkstyle-conventions")
  id("basetool.spotless-conventions")
  id("basetool.spring-library-conventions")
}

description = "test-support"

dependencies {
  api("org.springframework:spring-web")
  api("org.springframework:spring-webmvc")
  api("org.springframework:spring-test")
  api("ch.qos.logback:logback-classic")
  api(libs.archunit.core) { exclude(group = "org.slf4j") }

  testImplementation("jakarta.servlet:jakarta.servlet-api")
  testImplementation(libs.junit.jupiter)
  testImplementation("org.assertj:assertj-core")
  testImplementation("org.mockito:mockito-core")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
  inputs
    .files(
      layout.settingsDirectory.file("docker-compose.yml"),
      layout.settingsDirectory.file("quadlet/systemd/redis.container"),
      layout.settingsDirectory.file("quadlet/systemd/db-backend.container"),
      layout.settingsDirectory.file("backend/src/test/resources/application-test.yml"),
    )
    .withPropertyName("productionImageSources")
    .withPathSensitivity(PathSensitivity.RELATIVE)
}
