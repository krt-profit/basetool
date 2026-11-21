pluginManagement {
  repositories {
    // Google's Central mirror first; plugin markers from the portal only (ADR-0401).
    maven("https://maven-central.storage-download.googleapis.com/maven2/") {
      name = "Maven Central mirror (Google)"
      content { excludeModuleByRegex(".*", ".*\\.gradle\\.plugin") }
    }
    gradlePluginPortal()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
  repositories {
    maven("https://maven-central.storage-download.googleapis.com/maven2/") {
      name = "Maven Central mirror (Google)"
      content { excludeModuleByRegex(".*", ".*\\.gradle\\.plugin") }
    }
    gradlePluginPortal()
    mavenCentral()
  }
  versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}

rootProject.name = "build-logic"
