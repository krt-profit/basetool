package de.greluc.krt.profit.basetool.build

import java.io.StringReader
import java.math.BigDecimal
import java.util.Properties
import org.gradle.api.GradleException
import org.gradle.api.Project

/**
 * The quality settings one module declares in its own `build-settings.properties` (REQ-OPS-037).
 *
 * Every getter fails configuration when its key is absent or malformed; there is no default.
 */
class ModuleBuildSettings(
  private val projectPath: String,
  private val source: String,
  private val values: Map<String, String>,
) {
  private fun required(key: String): String =
    values[key]?.trim()?.takeIf { it.isNotEmpty() }
      ?: throw GradleException(
        "$projectPath: $source does not set '$key'. Every module declares its own quality " +
          "settings for each plugin it applies; nothing falls back to a default (REQ-OPS-037)."
      )

  private fun floor(key: String): BigDecimal {
    val value = required(key).toBigDecimalOrNull()
    if (value == null || value.signum() <= 0 || value > BigDecimal.ONE) {
      throw GradleException("$projectPath: $source sets '$key' to a value outside (0, 1].")
    }
    return value
  }

  private fun patterns(key: String): List<String> {
    val list = required(key).split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (list.isEmpty() || list.any { !Regex("[A-Za-z0-9_.\$*]+").matches(it) }) {
      throw GradleException("$projectPath: $source sets '$key' to $list, not class patterns.")
    }
    return list
  }

  /** The maximum heap of every `Test` JVM of the module, e.g. `3072m`. */
  val testMaxHeap: String
    get() =
      required("test.maxHeap").also {
        if (!Regex("[1-9][0-9]*[mg]").matches(it)) {
          throw GradleException("$projectPath: $source sets 'test.maxHeap' to '$it', not <n>m|g.")
        }
      }

  /** The JaCoCo instruction coverage floor of the module. */
  val instructionFloor: BigDecimal
    get() = floor("coverage.instruction")

  /** The JaCoCo branch coverage floor of the module. */
  val branchFloor: BigDecimal
    get() = floor("coverage.branch")

  /** The PIT target class patterns of the module. */
  val mutationTargetClasses: List<String>
    get() = patterns("mutation.targetClasses")

  /** The PIT target test patterns of the module. */
  val mutationTargetTests: List<String>
    get() = patterns("mutation.targetTests")

  companion object {
    /** Every key a `build-settings.properties` may hold. */
    val KEYS =
      setOf(
        "test.maxHeap",
        "coverage.instruction",
        "coverage.branch",
        "mutation.targetClasses",
        "mutation.targetTests",
      )
  }
}

/**
 * Reads this module's `build-settings.properties`, failing configuration when it is missing or
 * holds a key no convention reads.
 */
fun Project.moduleBuildSettings(): ModuleBuildSettings {
  val file = layout.projectDirectory.file("build-settings.properties")
  val source = file.asFile.relativeTo(rootDir).invariantSeparatorsPath
  val text =
    providers.fileContents(file).asText.orNull
      ?: throw GradleException(
        "$path: $source is missing. Every Java module declares its test heap, and its coverage " +
          "floors and mutation targets when it applies JaCoCo or PIT (REQ-OPS-037)."
      )
  val properties = Properties().apply { load(StringReader(text)) }
  val values = properties.stringPropertyNames().associateWith { properties.getProperty(it) }
  val unknown = values.keys - ModuleBuildSettings.KEYS
  if (unknown.isNotEmpty()) {
    throw GradleException("$path: $source holds unknown keys ${unknown.sorted()}.")
  }
  return ModuleBuildSettings(path, source, values)
}
