import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsTask
import de.greluc.krt.profit.basetool.conventions.catalog
import de.greluc.krt.profit.basetool.conventions.requiredLibrary

plugins {
  id("basetool.java-conventions")
  id("com.github.spotbugs-base")
}

dependencies.add("spotbugsPlugins", project.catalog().requiredLibrary("findsecbugs-plugin"))

val main = the<SourceSetContainer>().named("main")

val spotbugsMain =
  tasks.register<SpotBugsTask>("spotbugsMain") {
    group = "verification"
    description = "Runs SpotBugs analysis on the main source set."
    sourceDirs.from(main.map { it.allSource.sourceDirectories })
    classDirs.from(main.map { it.output.classesDirs })
    auxClassPaths.from(main.map { it.compileClasspath })
    pluginJarFiles.from(configurations.named("spotbugsPlugins"))
    effort.set(Effort.DEFAULT)
    reportLevel.set(Confidence.HIGH)
    ignoreFailures = false
    reports.create("xml") {
      required.set(true)
      outputLocation.set(layout.buildDirectory.file("reports/spotbugs/main.xml"))
    }
    dependsOn("classes")
  }

tasks.named("check").configure { dependsOn(spotbugsMain) }
