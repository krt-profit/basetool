plugins {
  `java-library`
  checkstyle
  id("jacoco")
  alias(libs.plugins.spring.dependency.management)
  alias(libs.plugins.spotbugs.base)
  id("com.diffplug.spotless")
}

description = "logging-support"

dependencyManagement {
  imports {
    mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}")
  }
}

dependencies {
  api("ch.qos.logback:logback-classic")
  api(libs.logstash.logback.encoder)

  testImplementation(libs.junit.jupiter)
  testImplementation("org.assertj:assertj-core")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
