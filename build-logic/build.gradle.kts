import org.gradle.plugin.use.PluginDependency

plugins { `kotlin-dsl` }

fun Provider<PluginDependency>.marker(): Provider<String> = map {
  "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}"
}

dependencies {
  implementation(libs.plugins.spring.boot.marker())
  implementation(libs.plugins.spring.dependency.management.marker())
  implementation(libs.plugins.cyclonedx.bom.marker())
  implementation(libs.plugins.licensee.marker())
  implementation(libs.plugins.spotbugs.base.marker())
  implementation(libs.plugins.pitest.marker())
  implementation(libs.plugins.errorprone.marker())
  implementation(libs.plugins.spotless.marker())

  constraints {
    implementation(libs.plexus.utils)
    implementation(libs.commons.lang3)
    implementation(libs.jackson2.plugins.databind)
    implementation(libs.jackson3.databind)
  }
}
