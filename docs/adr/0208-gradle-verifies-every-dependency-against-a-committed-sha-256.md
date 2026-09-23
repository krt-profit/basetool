# ADR-0208 — Gradle verifies every dependency against a committed SHA-256; signatures are not verified yet

- **Status:** Accepted
- **Date:** 2026-09-23
- **Deciders:** @greluc (SEC-15 approved for implementation with the improvement audit's open
  questions, 2026-09-22; "decide PGP and write a new ADR")
- **Related:** improvement audit 2026-09 finding SEC-15 ·
  [`docs/specs/deployment-delivery.md`](../specs/deployment-delivery.md) (`REQ-OPS-025`, the SBOMs
  that describe what these artifacts become) ·
  [ADR-0145](0145-build-provenance-anchored-outside-the-registry.md) (provenance of what the build
  produces — this ADR is about what it consumes) ·
  [`CONTRIBUTING.md`](../../CONTRIBUTING.md) → *Dependency verification*

## Context

Every CI run, every image build and every release resolves 2,216 files from 1,255 components (jars,
POMs and Gradle module metadata) — the applications' runtime and test classpaths, the Gradle plugins, the tool configurations Checkstyle,
SpotBugs, PIT, JaCoCo, Spotless and the OWASP scan load, and the Node.js distribution the frontend's
linters run on — from Maven Central, the Gradle plugin portal and nodejs.org. Nothing checked what
arrived. A tampered artifact at any of those sources, on a mirror in between, or in the Gradle cache
a runner restored would have been compiled into the images and signed by the release workflow, and
the SBOM would have described it faithfully. The version catalog pins *which* version is asked for;
nothing pinned *what the bytes are*.

Gradle has the mechanism built in: `gradle/verification-metadata.xml` lists an expected checksum per
artifact, and a build whose download does not match fails with "Dependency verification failed"
before the artifact is used.

## Decision

1. **`gradle/verification-metadata.xml` is committed and verification is strict**: `verify-metadata`
   is on (POMs and Gradle module metadata are verified too, not only jars), every artifact the build
   resolves carries a **SHA-256** checksum, and an artifact without an entry fails the build.
2. **Signatures are not verified** (`verify-signatures` is off). Decided against for now, for three
   reasons:
   - A measurable share of the graph is not signed at all, or signed with keys no keyserver serves;
     each of those would need a hand-written `trust` entry or a checksum fallback, i.e. the file would
     mix two mechanisms and the weaker one would decide.
   - Fetching keys from keyservers during `--write-verification-metadata` and during CI is the
     least reliable network step this build would have; a committed keyring
     (`verification-keyring.keys`) avoids that but is a second artifact to keep in step.
   - What signatures add over checksums is trust in the **next** version before anyone has seen it.
     Here every version change is a reviewed pull request that regenerates the checksums anyway, so
     the gain is narrow: a malicious release of an existing publisher would pass both, and a tampered
     copy of an existing version fails the checksum today.
   Revisit when a release PR shows that the regeneration step itself is where review stops.
3. **Two exemptions, both by artifact type**: `-sources.jar` and `-javadoc.jar` are trusted. Neither
   is ever on a build classpath; IDEs download them, and without the exemption every IDE sync
   reports verification failures that a contributor learns to ignore — which is the habit this file
   must not teach.
4. **Regeneration is one documented command**, run by whoever changes the resolved graph (a catalog
   bump, a new dependency, a plugin), in the same PR:

   ```bash
   GRADLE_USER_HOME="$(mktemp -d)" ./gradlew --write-verification-metadata sha256 help build :frontend:compileE2eJava
   ```

   **On an empty Gradle user home**, and that part is not optional. Measured at introduction: the
   same command on the maintainer's warm cache wrote 1,225 components, on an empty one in Linux 1,255
   — a warm cache never re-reads the parent POMs, BOMs and Gradle module files behind what it already
   holds, so the writer never sees them, and CI on a cold cache then fails on
   `kotlinx-coroutines-bom-1.8.0.pom` and 29 more. The committed file is the union of both runs.

   `help` makes the writer resolve every resolvable configuration of every project — the e2e,
   PIT, JaCoCo and tool configurations included; `build` adds what tasks only resolve while they
   run (Spotless's formatters, the Node.js archive, a few parent POMs). It adds entries and keeps
   existing ones. Removing stale entries is a periodic clean-up (delete the file's `<components>` and
   regenerate), not a per-PR duty — an unused entry verifies nothing and harms nothing. The file is
   written with the platform's line separator; `.gitattributes` (`*.xml text eol=lf`) normalises it.
5. **The Node.js archive is per operating system**, so its component lists one artifact per
   platform the build has run on: `win-x64` (the maintainer's workstation) and `linux-x64` (CI) at
   introduction. A contributor on another platform adds theirs once with
   `./gradlew --write-verification-metadata sha256 :frontend:nodeSetup`, and a Node bump in the
   catalog regenerates both committed entries — on Windows with the command above, the Linux one
   with the same command in a Linux shell or container. The Docker image builds never resolve Node:
   they run `:<module>:bootJar`, which no Node task feeds. *(Updated 2026-09-23, ADR-0209 / BLD-PERF-01:
   this said every Node task was excluded by name and checked by
   `check_frontend_image_lint_exclusions.py`; the builds stopped running `build`, and the script and
   its exclusion list are gone.)*
6. **`dependency-submission.yml` runs lenient.** It builds nothing that ships, and the
   `gradle/actions/dependency-submission` action injects its own dependency-graph plugin through an
   init script whose classpath this file does not describe; strict mode would refuse it and stop the
   submission Dependabot's alerts depend on. Lenient still verifies and logs every mismatch.

## Consequences

- **A dependency change without its metadata fails CI** with the offending coordinates in the error
  (`build/reports/dependency-verification/…/dependency-verification-report.html`). That is the
  intended failure: the reviewer of the bump now also sees the checksum that is being trusted.
- **refresh-versions.yml is unaffected**: it annotates the catalog and changes no version, so it
  resolves nothing new. The PR that *takes* a proposed bump regenerates the metadata.
- **Dependabot** manages GitHub Actions, Docker images and the frontend's npm packages here, none of
  which Gradle resolves; its PRs are unaffected. Should a Dependabot *security update* ever open a
  Gradle PR, it fails verification until a maintainer pushes the regenerated file — the same one
  command, on that PR's branch.
- The Docker image build (`docker/app/Dockerfile` since 2026-09-23) copies `gradle/`, so it verifies too.
- The file is large and machine-written. It is reviewed for *which* coordinates change, not read.
- Verified at introduction (2026-09-23): strict mode on an empty Gradle user home in Linux
  (`eclipse-temurin:25-jdk-alpine`) ran `help`, `assemble`, `compileTestJava`,
  `:frontend:compileE2eJava`, `:frontend:nodeSetup`, `spotlessCheck`, `checkstyleMain`,
  `spotbugsMain`, every `licensee` and every `cyclonedxBom` clean; the full CI build ran clean on the
  maintainer's Windows workstation; and a single altered checksum (`refreshVersions-core-0.60.6.jar`)
  failed `./gradlew help` with "Dependency verification failed for configuration 'classpath'".

## Rejected alternatives

- **PGP verification with a committed keyring** — see decision 2; possible later without changing
  anything decided here.
- **`lenient` mode in CI** — reports and continues, so it verifies nothing that matters.
- **Verifying only jars (`verify-metadata` off)** — a tampered POM can redirect resolution to a
  different artifact; the POM is part of what is trusted.
- **Relying on the build cache or the Gradle cache being clean** — the caches are restored from
  earlier runs; they are an input to trust, not a source of it.
