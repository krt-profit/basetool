package de.greluc.krt.profit.basetool.conventions

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.FileTree
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Installs the executable of a native tool's release artifact at [executable] and marks it
 * executable; a `.zip` or `.tar.gz` artifact is an archive holding a file of the same name.
 */
@DisableCachingByDefault(because = "Copying one local file is cheaper than a cache lookup")
abstract class InstallNativeTool : DefaultTask() {
  /** The release artifact: an archive, or the executable itself. */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.NAME_ONLY)
  abstract val distribution: ConfigurableFileCollection

  /** The installed executable; its file name is the archive member that is extracted. */
  @get:OutputFile abstract val executable: RegularFileProperty

  /** The service that opens the archive. */
  @get:Inject protected abstract val archives: ArchiveOperations

  /** The service that copies the executable into place. */
  @get:Inject protected abstract val fileSystem: FileSystemOperations

  /**
   * Copies the executable out of the distribution to [executable].
   *
   * @throws IllegalStateException when an archive holds no file named like [executable]
   */
  @TaskAction
  fun install() {
    val source = distribution.singleFile
    val target = executable.get().asFile
    val contents =
      when {
        source.name.endsWith(".zip") -> member(archives.zipTree(source), target.name)
        source.name.endsWith(".tar.gz") ->
          member(archives.tarTree(archives.gzip(source)), target.name)
        else -> source
      }
    fileSystem.copy {
      from(contents)
      eachFile { path = target.name }
      includeEmptyDirs = false
      into(target.parentFile)
      filePermissions { unix("rwxr-xr-x") }
    }
    check(target.isFile) { "${source.name} holds no ${target.name}" }
  }

  private fun member(tree: FileTree, name: String): FileTree = tree.matching { include("**/$name") }
}
