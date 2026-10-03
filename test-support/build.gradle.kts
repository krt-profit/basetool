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

  testImplementation("jakarta.servlet:jakarta.servlet-api")
  testImplementation(libs.junit.jupiter)
  testImplementation("org.assertj:assertj-core")
  testImplementation("org.mockito:mockito-core")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
  inputs
    .files(
      rootProject.file("docker-compose.yml"),
      rootProject.file("quadlet/systemd/redis.container"),
    )
    .withPropertyName("productionRedisImageSources")
    .withPathSensitivity(PathSensitivity.RELATIVE)
}
