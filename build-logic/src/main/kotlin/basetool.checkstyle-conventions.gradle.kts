import de.greluc.krt.profit.basetool.conventions.catalog
import de.greluc.krt.profit.basetool.conventions.requiredVersion

plugins {
  id("basetool.java-conventions")
  checkstyle
}

val checkstyleConfig = layout.settingsDirectory.dir("config/checkstyle")

checkstyle {
  toolVersion = project.catalog().requiredVersion("checkstyle")
  configFile = checkstyleConfig.file("google_checks.xml").asFile
  isIgnoreFailures = false
  maxWarnings = 0
}

tasks
  .withType<Checkstyle>()
  .matching { it.name != "checkstyleMain" }
  .configureEach { configFile = checkstyleConfig.file("javadoc_position.xml").asFile }
