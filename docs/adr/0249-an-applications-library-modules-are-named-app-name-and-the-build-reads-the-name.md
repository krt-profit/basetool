# ADR-0249 — An application's library modules are named `<app>-<name>`, and the build reads the name

- **Status:** Accepted
- **Date:** 2026-10-12
- **Deciders:** @greluc
- **Related:** spec REQ-OPS-047 · REQ-OPS-046 · REQ-OPS-038 · ADR-0248 · ADR-0210 · plan `docs/DOMAIN_MODULARISATION_PLAN.md` §5.8, §7.7 (P5-b)

## Context

`backend-exchange` and `backend-bank` (plan §5.8) are compiled into the backend's boot JAR and ship in
the backend image; neither is an application. Four parts of the build assumed there is one module per
application and would have treated them wrongly the moment they appeared:

- **32 `rootProject` reads in three module scripts** (`backend` 17, `frontend` 11, `test-support` 4)
  named files of other modules and of the repository by a path from the root project. That is a
  cross-project access Gradle's isolated projects reject, and it ties a script to where its project
  sits.
- **The image-reuse plan** (ADR-0210) classes every Dockerfile `COPY` source that is not under
  `${MODULE}` as shared, so a change to `backend-exchange/src/main` would rebuild all three images.
- **The SBOM coverage check** wants an SBOM, a committed BOM pair and a release-asset entry for every
  module of `settings.gradle.kts` that is not listed in `NOT_SHIPPED` or `SHIPPED_INSIDE`, so a library
  module would have failed it, or been given an SBOM that lists the product's components twice.
- **JaCoCo measured one module's classes with that module's tests.** Classes that moved to a library
  module would have left the backend's coverage floors without anyone lowering them, and the library's
  own tests would not count toward the application it ships in.

## Decision

We will name a library module of an application `<app>-<name>` (`<app>` being `backend`, `frontend` or
`ingest`), and let the build and its scripts read that name.

- **Paths come from the settings directory.** A module script names repository files with
  `layout.settingsDirectory.file|dir(…)`, never with `rootProject`. A guard in
  `scripts/check-gradle-input-paths.py` fails a tracked `*.gradle.kts` other than the root script and
  the settings files that names `rootProject`. A source scan that must cover the backend and its
  libraries asks `backendModuleDirectories()` of the included build (`backend` and `backend-*`).
- **Image reuse.** A Dockerfile `COPY` source `<app>-<name>/src/main[/…]` is an own input of `<app>`;
  the library's build script and settings stay shared, like every module's.
- **SBOM.** A module `<app>-<name>` counts as shipped inside `<app>` without an entry: it publishes no
  SBOM, and `<app>` must declare it as `implementation(project(":<app>-<name>"))`.
- **JaCoCo.** `basetool.coverage-conventions` derives the application's `<app>-*` project dependencies
  from its `implementation` configuration and adds their classes, execution data and sources to the
  application's `jacocoTestReport` and `jacocoTestCoverageVerification`, through variant-aware
  non-transitive configurations. The application's floors therefore cover the code it ships, and the
  libraries' own tests count toward them.

## Consequences

- **Easier:** extracting a library module needs no script, convention or workflow-script change; the
  rules fire on the name. A module that forgets its `implementation` line fails the SBOM check instead
  of shipping in no SBOM.
- **Harder / costs accepted:** a naming convention carries behaviour, so a module that merely starts
  with `backend-` is treated as the backend's library; and `:<app>:jacocoTestReport` now depends on the
  libraries' `test` tasks, so a coverage run of the application runs their tests too.
- **Not covered:** the Dockerfile still lists each module's build script by name, and the workflow path
  filters, the `docker-compose.build.yml` watch paths, the PIT matrix and the test classes that read
  source files of the backend by path are changed with the extraction itself (plan §7.7, P5-c).

## Alternatives considered

- **A list of library modules in each script** — rejected: it is the silent-omission shape the SBOM
  check exists to remove, and a fourth place to forget.
- **Gradle's `jacoco-report-aggregation` plugin** — rejected: it adds every project dependency of the
  application, `logging-support` among them, which has no tests and would pull its classes into the
  coverage floors at zero percent.
- **Automatic discovery of library modules from `settings.gradle.kts` in the Python scripts only** —
  rejected: the name is the one fact all four consumers can read without parsing the others.
- **`project(":x").projectDir` and `rootProject` for repository paths** — rejected: cross-project
  access that blocks isolated projects.

## Amendment 2026-10-12

`backend-exchange` and `backend-bank` are **not implemented**, by decision of the owner: a library that compiles against the whole `backend` (`compileOnly(project(":backend"))`, with `runtimeOnly` in the application, since `implementation` is a task cycle) gives no
compile-time isolation, and the isolation needs the api packages the library uses as Gradle modules of their own (plan §5.8). The naming rule and the build's reading of it stay as built, prepared for a library module that may come one day; nothing schedules one. A library
that the application does not compile against would also need the SBOM check and the coverage derivation to accept `runtimeOnly`, which they do not today (`implementation` only).
