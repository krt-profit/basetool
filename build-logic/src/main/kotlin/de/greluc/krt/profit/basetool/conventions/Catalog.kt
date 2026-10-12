package de.greluc.krt.profit.basetool.conventions

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider

/** The version catalog `gradle/libs.versions.toml` as the included build imports it. */
fun Project.catalog(): VersionCatalog =
  extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

/** The required version of the catalog entry [alias], failing when the entry does not exist. */
fun VersionCatalog.requiredVersion(alias: String): String =
  findVersion(alias)
    .orElseThrow { IllegalStateException("The version catalog has no version '$alias'.") }
    .requiredVersion

/** The catalog library [alias] as a dependency provider, failing when the entry does not exist. */
fun VersionCatalog.requiredLibrary(alias: String): Provider<MinimalExternalModuleDependency> =
  findLibrary(alias).orElseThrow {
    IllegalStateException("The version catalog has no library '$alias'.")
  }
