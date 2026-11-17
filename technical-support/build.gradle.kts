plugins {
  `java-library`
  id("basetool.java-conventions")
  id("basetool.quality-conventions")
  id("basetool.spring-library-conventions")
}

description = "technical-support"

dependencies {
  api("io.micrometer:micrometer-observation")
  compileOnly("org.springframework.data:spring-data-redis")
  compileOnly("org.slf4j:slf4j-api")
  testImplementation(libs.junit.jupiter)
  testImplementation("org.springframework.data:spring-data-redis")
  testImplementation("org.assertj:assertj-core")
  testImplementation("org.mockito:mockito-core")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
