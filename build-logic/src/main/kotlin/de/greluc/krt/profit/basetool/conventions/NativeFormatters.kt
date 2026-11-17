package de.greluc.krt.profit.basetool.conventions

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

/**
 * Registers the tasks `<name>Check` and `<name>Apply`, which run the native formatter [tool] over
 * [sources] from the project directory, and makes `spotlessCheck` and `spotlessApply` depend on
 * them.
 *
 * Each task passes its arguments followed by the source paths relative to the project directory.
 * The check is up to date while the sources, the settings and the executable are unchanged.
 *
 * @param name the prefix of the two task names
 * @param tool the installed formatter
 * @param sources the files the formatter owns
 * @param settings the files that hold the formatter's settings
 * @param checkArguments the arguments that make the formatter fail on a file it would change
 * @param applyArguments the arguments that make the formatter rewrite the files in place
 * @receiver the project whose `spotlessCheck` and `spotlessApply` run the formatter
 */
fun Project.registerNativeFormatter(
  name: String,
  tool: NativeTool,
  sources: FileCollection,
  settings: FileCollection,
  checkArguments: List<String>,
  applyArguments: List<String>,
) {
  val projectDirectory = layout.projectDirectory.asFile
  val executable = tool.executable.get().asFile.path
  fun runOverSources(arguments: List<String>) =
    Action<Exec> {
      dependsOn(tool.install)
      this.executable = executable
      workingDir = projectDirectory
      argumentProviders.add(
        CommandLineArgumentProvider {
          arguments + sources.files.map { it.relativeTo(projectDirectory).path }
        },
      )
      onlyIf("there are files to format") { !sources.isEmpty }
    }

  val apply = tasks.register("${name}Apply", Exec::class.java, runOverSources(applyArguments))
  apply.configure {
    group = "formatting"
    description = "Reformats the files $name owns in place."
  }

  val marker = layout.buildDirectory.file("format-checks/$name")
  val check = tasks.register("${name}Check", Exec::class.java, runOverSources(checkArguments))
  check.configure {
    group = "verification"
    description = "Fails when $name would reformat a file; ./gradlew spotlessApply fixes it."
    mustRunAfter(apply)
    inputs.files(sources).withPropertyName("sources").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs
      .files(settings)
      .withPropertyName("settings")
      .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs
      .file(tool.executable)
      .withPropertyName("executable")
      .withPathSensitivity(PathSensitivity.NONE)
    outputs.file(marker).withPropertyName("marker")
    doLast { marker.get().asFile.writeText("") }
  }

  tasks.named("spotlessCheck") { dependsOn(check) }
  tasks.named("spotlessApply") { dependsOn(apply) }
}
