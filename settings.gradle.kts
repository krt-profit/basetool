pluginManagement {
  includeBuild("build-logic")
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

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
    ivy {
      name = "Ruff"
      setUrl("https://github.com/astral-sh/ruff/releases/download")
      patternLayout { artifact("[revision]/[artifact]-[classifier].[ext]") }
      metadataSources { artifact() }
      content { includeModule("sh.astral", "ruff") }
    }
    ivy {
      name = "shfmt"
      setUrl("https://github.com/mvdan/sh/releases/download")
      patternLayout { artifact("v[revision]/[artifact]_v[revision]_[classifier](.[ext])") }
      metadataSources { artifact() }
      content { includeModule("cc.mvdan", "shfmt") }
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

include("technical-support")
