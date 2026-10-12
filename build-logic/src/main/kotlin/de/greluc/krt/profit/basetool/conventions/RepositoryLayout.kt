package de.greluc.krt.profit.basetool.conventions

import org.gradle.api.Project
import org.gradle.api.file.Directory

/**
 * The directories of the backend application and of its library modules, `backend` and `backend-*`,
 * resolved from the settings directory so no module script depends on where it sits.
 *
 * A directory is listed whether or not `settings.gradle.kts` includes it; the settings file decides
 * what builds, this list only decides what a source scan reads.
 *
 * @return the module directories, sorted by name
 * @receiver the project whose settings directory is searched
 */
fun Project.backendModuleDirectories(): List<Directory> {
  val settingsDirectory = layout.settingsDirectory
  return settingsDirectory.asFile
    .listFiles { file ->
      file.isDirectory && (file.name == "backend" || file.name.startsWith("backend-"))
    }
    .orEmpty()
    .sortedBy { it.name }
    .map { settingsDirectory.dir(it.name) }
}
