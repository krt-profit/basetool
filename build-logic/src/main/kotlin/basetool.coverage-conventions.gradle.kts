import de.greluc.krt.profit.basetool.conventions.moduleBuildSettings
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.TestSuiteName
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.VerificationType

plugins {
  id("basetool.java-conventions")
  jacoco
}

tasks.withType<Test>().configureEach { finalizedBy(tasks.named("jacocoTestReport")) }

val generatedClassExcludes =
  listOf("**/*MapperImpl.class", "**/*MapperImpl\$*.class", "**/*Application.class")

fun filterGenerated(classes: FileCollection): FileCollection =
  files(classes.files.map { dir -> fileTree(dir) { exclude(generatedClassExcludes) } })

val coverageModules = configurations.dependencyScope("coverageModules")

val implementation = configurations.named("implementation")

coverageModules.configure {
  dependencies.addAllLater(
    provider {
      implementation
        .get()
        .dependencies
        .withType<ProjectDependency>()
        .filter { it.name.startsWith("${project.name}-") }
        .map { it.copy() }
    },
  )
}

fun coverageView(name: String, describe: AttributeContainer.() -> Unit) =
  configurations
    .resolvable(name) {
      extendsFrom(coverageModules.get())
      isTransitive = false
      attributes { describe() }
    }
    .get()

val moduleClasses =
  coverageView("coverageModuleClasses") {
    attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
    attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.CLASSES))
  }

val moduleExecutionData =
  coverageView("coverageModuleExecutionData") {
    attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.VERIFICATION))
    attribute(TestSuiteName.TEST_SUITE_NAME_ATTRIBUTE, objects.named("test"))
    attribute(
      VerificationType.VERIFICATION_TYPE_ATTRIBUTE,
      objects.named(VerificationType.JACOCO_RESULTS),
    )
  }

val moduleSources =
  coverageView("coverageModuleSources") {
    attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.VERIFICATION))
    attribute(
      VerificationType.VERIFICATION_TYPE_ATTRIBUTE,
      objects.named(VerificationType.MAIN_SOURCES),
    )
  }

val moduleClassTree =
  moduleClasses.incoming.files.asFileTree.matching { exclude(generatedClassExcludes) }

tasks.named<JacocoReport>("jacocoTestReport") {
  onlyIf { System.getenv("CI") != null }
  reports {
    xml.required.set(true)
    csv.required.set(true)
    html.required.set(true)
  }
  classDirectories.setFrom(filterGenerated(classDirectories))
  classDirectories.from(moduleClassTree)
  executionData.from(moduleExecutionData.incoming.files)
  sourceDirectories.from(moduleSources.incoming.files)
}

val coverageSettings = moduleBuildSettings()
val instructionFloor = coverageSettings.instructionFloor
val branchFloor = coverageSettings.branchFloor

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
  dependsOn(tasks.named("test"))
  classDirectories.setFrom(filterGenerated(classDirectories))
  classDirectories.from(moduleClassTree)
  executionData.from(moduleExecutionData.incoming.files)
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
