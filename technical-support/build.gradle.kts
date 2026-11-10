plugins {
  `java-library`
  id("basetool.java-conventions")
  id("basetool.quality-conventions")
  id("basetool.spring-library-conventions")
}

description = "technical-support"

dependencies {
  api("io.micrometer:micrometer-observation")
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
