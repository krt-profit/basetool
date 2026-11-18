package de.greluc.krt.profit.basetool.conventions

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskProvider
import org.gradle.process.CommandLineArgumentProvider

/**
 * Configures an [Exec] task to run [tool] from the project directory with [arguments] followed by
 * the paths of [sources] relative to it, and to skip the run while [sources] is empty.
 *
 * @param tool the installed tool, whose install task the run depends on
 * @param sources the files the tool reads
 * @param arguments the arguments placed before the source paths
 * @return the configuration action for the task
 * @receiver the project whose directory the tool runs in
 */
internal fun Project.runNativeTool(
  tool: NativeTool,
  sources: FileCollection,
  arguments: List<String>,
): Action<Exec> {
  val projectDirectory = layout.projectDirectory.asFile
  val executable = tool.executable.get().asFile.path
  return Action<Exec> {
    dependsOn(tool.install)
    this.executable = executable
    workingDir = projectDirectory
    argumentProviders.add(
      CommandLineArgumentProvider {
        arguments + sources.files.map { it.relativeTo(projectDirectory).path }
      },
    )
    onlyIf("there are files to run on") { !sources.isEmpty }
  }
}

/**
 * Registers the verification task [taskName], which runs [tool] over [sources] with [arguments] and
 * fails with the tool's exit code.
 *
 * The task is up to date while the sources, the settings and the executable are unchanged; a marker
 * file under `build/native-checks/` records the last passing run.
 *
 * @param taskName the name of the task
 * @param tool the installed tool
 * @param sources the files the tool checks
 * @param settings the files that hold the tool's settings
 * @param arguments the arguments that make the tool fail on a finding
 * @return the registered task
 * @receiver the project that owns the task
 */
internal fun Project.registerNativeVerification(
  taskName: String,
  tool: NativeTool,
  sources: FileCollection,
  settings: FileCollection,
  arguments: List<String>,
): TaskProvider<Exec> {
  val marker = layout.buildDirectory.file("native-checks/$taskName")
  val verification =
    tasks.register(taskName, Exec::class.java, runNativeTool(tool, sources, arguments))
  verification.configure {
    group = "verification"
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
  return verification
}
