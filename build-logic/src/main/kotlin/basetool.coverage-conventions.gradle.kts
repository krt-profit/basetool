import de.greluc.krt.profit.basetool.conventions.moduleBuildSettings

plugins {
  id("basetool.java-conventions")
  jacoco
}

tasks.withType<Test>().configureEach { finalizedBy(tasks.named("jacocoTestReport")) }

val generatedClassExcludes =
  listOf("**/*MapperImpl.class", "**/*MapperImpl\$*.class", "**/*Application.class")

fun filterGenerated(classes: FileCollection): FileCollection =
  files(classes.files.map { dir -> fileTree(dir) { exclude(generatedClassExcludes) } })

tasks.named<JacocoReport>("jacocoTestReport") {
  onlyIf { System.getenv("CI") != null }
  reports {
    xml.required.set(true)
    csv.required.set(true)
    html.required.set(true)
  }
  classDirectories.setFrom(filterGenerated(classDirectories))
}

val coverageSettings = moduleBuildSettings()
val instructionFloor = coverageSettings.instructionFloor
val branchFloor = coverageSettings.branchFloor

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
  dependsOn(tasks.named("test"))
  classDirectories.setFrom(filterGenerated(classDirectories))
  violationRules {
    rule {
      element = "BUNDLE"
      limit {
        counter = "INSTRUCTION"
        value = "COVEREDRATIO"
        minimum = instructionFloor
      }
      limit {
        counter = "BRANCH"
        value = "COVEREDRATIO"
        minimum = branchFloor
      }
    }
  }
}

tasks.named("check").configure { dependsOn(tasks.named("jacocoTestCoverageVerification")) }
