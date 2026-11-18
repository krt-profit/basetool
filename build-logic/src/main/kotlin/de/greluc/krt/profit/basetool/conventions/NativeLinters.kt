package de.greluc.krt.profit.basetool.conventions

import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.language.base.plugins.LifecycleBasePlugin

/**
 * Registers the task [name], which runs the native linter [tool] over [sources] and fails on a
 * finding, and makes `check` depend on it once the project has one.
 *
 * The task is up to date while the sources, the settings and the executable are unchanged.
 *
 * @param name the name of the task
 * @param tool the installed linter
 * @param sources the files the linter checks
 * @param settings the files that hold the linter's settings
 * @param arguments the arguments that make the linter report its findings without fixing them
 * @receiver the project whose `check` runs the linter
 */
fun Project.registerNativeLinter(
  name: String,
  tool: NativeTool,
  sources: FileCollection,
  settings: FileCollection,
  arguments: List<String>,
) {
  val lint = registerNativeVerification(name, tool, sources, settings, arguments)
  lint.configure { description = "Fails on a finding of $name in the files it owns." }
  plugins.withType(LifecycleBasePlugin::class.java) {
    tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { dependsOn(lint) }
  }
}
