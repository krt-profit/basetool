package de.greluc.krt.profit.basetool.conventions

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider

/**
 * A native tool registered by [registerNativeTool].
 *
 * @property install the task that installs [executable]
 * @property executable the installed executable, at a path fixed at configuration time
 */
class NativeTool(
  val install: TaskProvider<InstallNativeTool>,
  val executable: Provider<RegularFile>,
)

/**
 * Registers the configuration `<name>Tool` that resolves the release artifact of the native tool
 * [name] and the task `install<Name>` that installs it as `build/tools/<name>/<name>`, with `.exe`
 * on Windows.
 *
 * @param name the tool's name: the configuration's, the task's and the executable's
 * @param notation the release artifact for a platform, evaluated only when it is resolved
 * @receiver the project that owns the configuration and the task
 */
fun Project.registerNativeTool(
  name: String,
  notation: (NativeToolPlatform) -> String,
): NativeTool {
  val distribution =
    configurations.register("${name}Tool") {
      isCanBeConsumed = false
      isCanBeResolved = true
      isTransitive = false
      description = "The release artifact of the native tool $name."
    }
  val platform =
    providers.systemProperty("os.name").zip(providers.systemProperty("os.arch")) { osName, osArch ->
      NativeToolPlatform.of(osName, osArch)
    }
  dependencies.addProvider(distribution.name, platform.map { notation(it) })
  val suffix = NativeToolPlatform.executableSuffix(providers.systemProperty("os.name").get())
  val executable = layout.buildDirectory.file("tools/$name/$name$suffix")
  val install =
    tasks.register(
      "install${name.replaceFirstChar(Char::uppercaseChar)}",
      InstallNativeTool::class.java,
      Action<InstallNativeTool> {
        group = "build setup"
        description = "Installs the verified $name release binary under build/tools."
        this.distribution.from(distribution)
        this.executable.set(executable)
      },
    )
  return NativeTool(install, executable)
}
