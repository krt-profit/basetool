plugins { id("de.fayard.refreshVersions") version "0.60.6" apply false }

if (providers.gradleProperty("refreshVersions").isPresent) {
  apply(plugin = "de.fayard.refreshVersions")
}

dependencyResolutionManagement {
  repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
  repositories {
    mavenCentral()
    ivy {
      name = "Node.js"
      setUrl("https://nodejs.org/dist")
      patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
      metadataSources { artifact() }
      content { includeModule("org.nodejs", "node") }
    }
  }
}

rootProject.name = "basetool"

include("backend")

include("frontend")

include("ingest")

include("keycloak-spi")

include("test-support")

include("logging-support")
