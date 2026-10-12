plugins {
  id("basetool.java-conventions")
  id("org.springframework.boot")
  id("basetool.dependency-management-conventions")
}

tasks.named<JavaExec>("bootRun") {
  jvmArgs("--enable-native-access=ALL-UNNAMED")
  systemProperty("spring.profiles.active", "dev")
}
