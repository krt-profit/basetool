import de.greluc.krt.profit.basetool.conventions.BasetoolExtension
import de.greluc.krt.profit.basetool.conventions.catalog
import de.greluc.krt.profit.basetool.conventions.requiredLibrary
import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone

plugins {
  id("basetool.java-conventions")
  id("net.ltgt.errorprone")
}

val basetool = the<BasetoolExtension>()
val catalog = project.catalog()

dependencies {
  errorprone(catalog.requiredLibrary("errorprone-core"))
  errorprone(catalog.requiredLibrary("nullaway"))
}

tasks.named<JavaCompile>("compileJava") {
  options.errorprone {
    disableWarningsInGeneratedCode.set(true)
    check("NullAway", CheckSeverity.ERROR)
    disable("StringConcatToTextBlock")
    disable("UnrecognisedJavadocTag")
    option("NullAway:AnnotatedPackages", basetool.nullAwayPackages.map { it.joinToString(",") })
    option("NullAway:TreatGeneratedAsUnannotated", "true")
  }
}

tasks
  .withType<JavaCompile>()
  .matching { it.name != "compileJava" }
  .configureEach { options.errorprone.enabled.set(false) }
