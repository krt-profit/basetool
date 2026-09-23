// refreshVersions is ON THE CLASSPATH for every build and APPLIED only when asked for:
//
//     ./gradlew refreshVersions -PrefreshVersions
//
// Applied unconditionally it ran its settings hook on every build, and it is not
// configuration-cache compatible, so it alone kept the configuration cache off for the whole
// repository (audit item BLD-PERF-04). It proposes versions and never changes one -- it annotates
// `gradle/libs.versions.toml` with `## ⬆ = "…"` markers -- so no ordinary build needs it at all.
// `refresh-versions.yml` passes the flag; nothing else has a reason to.
//
// `apply false` rather than no declaration: the plugin still resolves (from the plugin portal, as
// before), which keeps the version pinned here and keeps it inside the dependency verification
// metadata, so the weekly job cannot run an unverified plugin jar.
plugins { id("de.fayard.refreshVersions") version "0.60.6" apply false }

if (providers.gradleProperty("refreshVersions").isPresent) {
  apply(plugin = "de.fayard.refreshVersions")
}

// One repository list for every project (audit item BLD-SIMP-06). It used to be declared seven
// times -- `allprojects {}` in the root script plus once in each of the six module scripts -- and
// FAIL_ON_PROJECT_REPOS makes a new copy a build error instead of a silent addition.
//
// The Node.js distribution repository is declared HERE, and `frontend/build.gradle.kts` sets
// `node { distBaseUrl = null }` so the node-gradle plugin stops adding it to the project itself --
// which FAIL_ON_PROJECT_REPOS would refuse. The layout and the content filter are the ones the
// plugin (7.1.0) registers on its own, so the Node archive resolves exactly as before, and only
// `org.nodejs:node` can be served from it.
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

// Custom Keycloak provider JAR (Discord federation + first-login membership gate,
// epic #720). A plain library module — NOT a Spring Boot app — compiled against the
// Keycloak 26 server SPIs (compileOnly) and emitting Java-21 bytecode so the
// Keycloak runtime JVM can load it. See keycloak-spi/build.gradle.kts.
include("keycloak-spi")

// Test-only helper library shared by the backend and frontend anonymous-surface sweeps
// (#1804). Not a Spring Boot app and not shipped: nothing depends on it at runtime.
// See test-support/build.gradle.kts.
include("test-support")

// The one implementation of the log hygiene every application ships: LogSafe and the PII maskers
// (ADR-0205). A plain library, NOT a Spring Boot app — but unlike test-support it IS shipped: the
// three applications depend on it at runtime. See logging-support/build.gradle.kts.
include("logging-support")
