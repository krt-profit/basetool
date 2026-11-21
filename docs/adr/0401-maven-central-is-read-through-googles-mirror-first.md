# ADR-0401 — Maven Central is read through Google's mirror first

- **Status:** Accepted
- **Date:** 2026-11-21
- **Deciders:** @greluc
- **Related:** spec [`deployment-delivery.md`](../specs/deployment-delivery.md) (REQ-OPS-046,
  REQ-OPS-034) · amends ADR-0275 · ADR-0208 · ADR-0137

## Context

The Release Images run on `main` for the v1.26.6 release commit failed on 2026-11-21 in one of
its six image builds, `ingest / linux/amd64`, while configuring `:build-logic`. The Kotlin
artifacts the `kotlin-dsl` plugin puts on that classpath could not be resolved. The Gradle Plugin
Portal redirected the request to `repo.maven.apache.org`, which answered
`429 Too Many Requests`. Gradle then reported the repository as disabled. The `merge` job needs
every build, so it was skipped for all three images, and `:edge` was not moved.

This was not a one-off. Of the fourteen earlier failed runs on `main`, release branches and tags
between 2026-11-17 and 2026-11-20, five had failed on a Maven Central `429`: Release Images
(`frontend / linux/amd64`), Sandbox Images twice (`sandbox-ingest`, `sandbox-keycloak`), and CI on
`main` and on `release/v1.26.5`.

- **Central limits consumption per source IP.** Sonatype aims the limit at the highest-volume
  sources and counts what it sees from an egress address. GitHub-hosted runners share their egress
  addresses with unrelated users, so this repository's own volume does not decide whether a job is
  blocked. A block is temporary. A reported first block lasted 26–30 minutes
  (`Retry-After: 1586`–`1800`, aquasecurity/trivy#10691). Sonatype warns that more retries usually
  make it worse, and recommends a cache in front of Central.
- **Gradle cannot outwait the block.** Gradle 9.9.2 treats `429` as transient and makes three
  attempts with 1 s and 2 s between them. It then disables the repository for the rest of the build
  (`ErrorHandlingModuleComponentRepository`). A disabled repository fails the resolution: Gradle
  falls through to the next repository only on a `404`, never on an error
  (`RepositoryChainComponentMetaDataResolver`).
- **Every build fetches everything.** ADR-0137 accepted that an image build resolves its
  dependencies from scratch, which is six full resolutions per Release Images run. ADR-0275 put
  Central behind the portal for the plugin classpath and rejected running a caching proxy.

Google publishes a full mirror of Maven Central at
`https://maven-central.storage-download.googleapis.com/maven2/`. It is a Cloud Storage bucket with
no per-IP consumption limit. On 2026-11-21 it held the POM of the failed request, about 18 hours
after it appeared on Central, and its copy of a sampled plugin marker POM matched Central's bytes.

## Decision

1. **Every repository block in both settings files names the mirror first.** That covers
   `pluginManagement` and `dependencyResolutionManagement` in the root `settings.gradle.kts` and in
   `build-logic/settings.gradle.kts`. The block is declared as
   `maven("https://maven-central.storage-download.googleapis.com/maven2/")` with the name
   `Maven Central mirror (Google)`.
2. **The mirror never answers a plugin marker.** Each declaration carries
   `content { excludeModuleByRegex(".*", ".*\\.gradle\\.plugin") }`. Some plugins also publish
   their markers to Central, and those bytes differ from the portal's: for
   `org.springframework.boot.gradle.plugin-4.2.0.pom` the portal serves the 680-byte marker Gradle
   generates, and Central and the mirror serve the publisher's 1,727-byte one. The committed
   checksums are the portal's, so markers keep coming from the portal.
3. **Behind the mirror, the order stays as it was.** For the plugin classpath and for
   `build-logic`'s own dependencies, the portal comes before Central (ADR-0275). For the module
   classpaths, Central comes before the Node.js, Ruff and shfmt Ivy repositories.
4. **The mirror is a cache, not a source of truth.** The coordinates are the same, and every
   artifact must match the same committed SHA-256 whichever host served it (REQ-OPS-034,
   ADR-0208). An artifact the mirror does not hold yet is a `404`, and the next repository
   resolves it.
5. **Gradle's retry settings stay at their defaults.** Raising
   `org.gradle.internal.repository.max.tentatives` or `…initial.backoff` would only stretch a job
   into a block that outlasts it, against Sonatype's advice, and through internal properties.

## Consequences

- CI, the image builds, the sandbox images and local builds get nearly every library artifact from
  the mirror. Central is asked only for what the mirror lacks, so a Central block no longer stops a
  build that Central would not have served anyway.
- The mirror becomes the host the build depends on most. If it errors rather than answers `404`,
  Gradle fails the resolution without trying Central, as it did with Central before. Google's
  bucket replaces Sonatype's rate-limited endpoint in that role. No new trust boundary opens: the
  checksum gate rejects any byte that differs from the commit.
- A dependency released in the last few hours resolves from Central until the mirror has it. That
  costs one extra `404` per new artifact.
- The mirror URL appears in four declarations across the two settings files. Gradle compiles each
  `pluginManagement` block on its own, so nothing can share one constant. REQ-OPS-046 names all
  four, and the same rule covers a build added later with its own settings file.
- No coordinate changed, so `gradle/verification-metadata.xml` needed no regeneration.
- The pre-warmed dependency image that ADR-0137 names as a follow-up stays open. With the mirror it
  is a matter of speed, no longer of reliability.

## Alternatives considered

- **Re-run the failed jobs and leave the build alone.** That repaired this run, but the failure
  recurs about every second day and can take a release's `:edge` with it.
- **More and longer Gradle retries.** These cannot outlast a block of about half an hour, Sonatype
  says retries make blocks worse, and the knobs are internal Gradle properties.
- **A pre-warmed dependency base image (ADR-0137's follow-up).** This removes Central from the
  image builds only, leaving CI and the sandbox builds exposed. It also adds an image, its
  invalidation key and its signature to operate.
- **A self-hosted repository manager or caching proxy.** This is Sonatype's own recommendation, and
  ADR-0275 rejected it as infrastructure to run for a project of this size. The public mirror gives
  the caching without the operating cost.
- **The mirror for the module classpaths only, leaving the plugin classpath as it was.** The
  failure of 2026-11-21 came through the plugin classpath, because the portal redirects the Kotlin
  artifacts to Central, so this would leave the failing path in place.
