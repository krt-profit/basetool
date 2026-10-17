# ADR-0275 — The plugin classpath resolves from the portal with Maven Central behind it

- **Status:** Accepted
- **Date:** 2026-10-17
- **Deciders:** @greluc
- **Related:** spec [`deployment-delivery.md`](../specs/deployment-delivery.md) (REQ-OPS-046,
  REQ-OPS-034) · ADR-0248 · ADR-0208

## Context

The scheduled E2E run on `main` of 2026-10-17 failed while *configuring* `:build-logic`. Its
buildscript `classpath` could not resolve `kotlin-stdlib`, `kotlin-gradle-plugin-api`,
`kotlin-sam-with-receiver`, `kotlin-assignment` and `kotlin-gradle-plugins-bom` — the Kotlin
artifacts the `kotlin-dsl` plugin puts on the classpath of the included build (ADR-0248). One
location was searched: `https://plugins.gradle.org/m2`.

`pluginManagement.repositories` was empty in both settings files, and that block is what seeds every
project's `buildscript.repositories`. The repositories of `dependencyResolutionManagement`, where
`mavenCentral()` already stood, do not apply to that classpath. Gradle's default therefore left the
Gradle Plugin Portal as the only source for the plugin classpath, in all eight projects of the two
builds — the E2E shard was where it surfaced, not what was special about it.

The portal is not where those artifacts live. It answers the plugin markers Gradle publishes itself,
and redirects everything else: a request for `kotlin-gradle-plugins-bom-2.4.20.pom` answers `303`
and names `repo.maven.apache.org`. Maven Central held every artifact of the failure, with bytes
matching the checksums already committed in `gradle/verification-metadata.xml` — which every
artifact must match before use, strict wherever a product is built (REQ-OPS-034, ADR-0208).

## Decision

1. **Both settings files declare `pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }`**
   — the root `settings.gradle.kts` and `build-logic/settings.gradle.kts`.
2. **The portal stays first.** It is the only source that answers Gradle's own plugin markers, so
   removing it breaks the build outright; Central is asked only when the portal does not answer.
3. **Central is a fallback, not a second source of truth.** It opens no trust boundary: it is the
   host the portal already redirects to, it is already declared for the module classpaths, and
   REQ-OPS-034 is untouched — an artifact must match the same committed SHA-256 whichever
   repository served it, or the build fails.

## Consequences

- A portal outage no longer stops every build in the repository for artifacts Central is serving.
- A genuinely missing artifact now reports two searched locations and costs a second request before
  the build fails.
- No coordinate changed, so `gradle/verification-metadata.xml` needed no regeneration.
- The set of artifacts the plugin classpath *can* resolve grows by those published only to Central.
  The checksum gate, not the repository list, is what keeps that honest.
- Nothing gates the declaration itself. A build added later with its own settings file inherits the
  portal-only default again; REQ-OPS-046 carries the rule for that case.

## Alternatives considered

- **Re-run the job and leave the build alone** — the failure wore the shape of a flake, one shard of
  fifteen, but the cause is structural and returns with the next portal hiccup. A re-run hides it.
- **Maven Central alone, portal removed** — tried, and it fails: the `kotlin-dsl` plugin marker
  `org.gradle.kotlin.kotlin-dsl:org.gradle.kotlin.kotlin-dsl.gradle.plugin:6.7.6` is not on Central.
- **A `buildscript { repositories { … } }` block in each project** — the same effect written eight
  times instead of twice, against the convention that repositories are declared once in the settings
  files, next to `FAIL_ON_PROJECT_REPOS`.
- **A caching proxy in front of both** — removes the dependency on either host being up, but adds
  infrastructure to operate and one more place where bytes could change. With the checksum gate
  already in place, the fallback is enough.
