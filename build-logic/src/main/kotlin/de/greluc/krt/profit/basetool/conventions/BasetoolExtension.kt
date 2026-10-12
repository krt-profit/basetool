package de.greluc.krt.profit.basetool.conventions

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * The `basetool { }` block of a module: the typed build settings a module passes to the convention
 * plugins, in place of ad-hoc project properties and inline lists.
 *
 * Registered by `basetool.java-conventions`; the quality settings that live in a file are read by
 * [moduleBuildSettings].
 */
abstract class BasetoolExtension {
  /** The Java release the module compiles for; unset means the toolchain's own release. */
  abstract val javaRelease: Property<Int>

  /**
   * The package prefixes NullAway treats as annotated; read by `basetool.errorprone-conventions`.
   */
  abstract val nullAwayPackages: ListProperty<String>
}
