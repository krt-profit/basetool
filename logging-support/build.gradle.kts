plugins {
  `java-library`
  id("basetool.java-conventions")
  id("basetool.quality-conventions")
  id("basetool.spring-library-conventions")
}

description = "logging-support"

dependencies {
  api("ch.qos.logback:logback-classic")
  api(libs.logstash.logback.encoder)

  testImplementation(libs.junit.jupiter)
  testImplementation("org.assertj:assertj-core")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
