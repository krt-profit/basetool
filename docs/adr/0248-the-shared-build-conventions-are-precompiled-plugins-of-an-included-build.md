# ADR-0248 — The shared build conventions are precompiled plugins of an included build

- **Status:** Accepted
- **Date:** 2026-10-11
- **Deciders:** @greluc
- **Related:** spec REQ-OPS-046 · REQ-OPS-034 · REQ-OPS-037 · ADR-0192 · ADR-0208 · plan `docs/archive/DOMAIN_MODULARISATION_PLAN.md` §5.8, §7.7, §8.4 (P5-a)

## Context

Everything the modules share lived in the root `build.gradle.kts` as `subprojects {
plugins.withId(...) }` blocks: the toolchain, the Lombok and JetBrains wiring, the Mockito agent, the
`Test` and `JavaCompile` conventions, Checkstyle, Spotless, JaCoCo, PIT, SpotBugs with FindSecBugs,
CycloneDX and Licensee, plus the licence tables the frontend read back through `rootProject.extra`.
That worked for six modules and has three costs that a further library module would multiply (the extraction of `backend-exchange` and
`backend-bank`, plan §5.8, is not implemented, by decision of the owner on 2026-10-12):

- **A module gets conventions only for the plugins it remembers to apply**, and only because the root
  reaches into it. A module carved out of `backend` is configured from outside, by name, with no place
  that says what it is.
- **`subprojects {}` and `allprojects {}` are what Gradle's isolated projects reject**, and the
  configuration cache becomes the default in Gradle 10. Both were open items of the plan.
- **The root script was 620 lines**, of which the module conventions were about nine tenths; the
  security floors for the plugins' transitive jars sat in four `buildscript { constraints }` blocks.

## Decision

We will move every module convention into `build-logic/`, an **included build** declared in
`pluginManagement`, as **precompiled script plugins**, one per concern, and apply them by id from the
module scripts.

- The plugins are `basetool.java-conventions`, `…checkstyle-…`, `…spotless-…`, `…coverage-…` (JaCoCo),
  `…spotbugs-…`, `…quality-…` (those four), `…mutation-…` (PIT), `…sbom-…`, `…licensee-…`,
  `…supply-chain-…` (those two), `…dependency-management-…`, `…spring-library-…`, `…spring-app-…`,
  `…errorprone-…`, `…javadoc-…` and `…repository-format-…` (root only). Aggregates exist where a set
  must not be applied by halves.
- **The values stay with the module.** The quality settings keep their file (`build-settings.properties`,
  REQ-OPS-037, read by `Project.moduleBuildSettings()` in the included build); the two values a module
  used to pass as an inline list or an ad-hoc task block become the typed `basetool { }` extension
  (`javaRelease`, `nullAwayPackages`). The licence policy is the class `OssLicensePolicy`.
- **The catalog is imported** into the included build; plugin versions are the catalog's `[plugins]`
  entries, resolved through their markers, and the floors for their transitive jars are constraints of
  `build-logic/build.gradle.kts`. Third-party plugins are applied only through a convention, so each
  has exactly one class loader. The frontend keeps the Node and OpenAPI generator plugins it alone uses.
- **Verification stays one file.** The included build's artifacts (the Kotlin DSL plugin and its
  compiler) are added to `gradle/verification-metadata.xml`; the image builds copy `build-logic/`.
- **The move changes no task.** The proof is a dump of every project's tasks, configurations,
  compiler and test arguments and tool settings, taken before and after.

## Consequences

- **Easier:** a new module is a `plugins {}` block; a lost gate is a missing line in one file instead
  of a missing callback in 350 lines; the root script holds the group and version, the terms-version
  task and the OWASP settings only; the build is a step closer to isolated projects (the `rootProject`
  reads of the module scripts remain and are the next item).
- **Harder / costs accepted:** a cold image build compiles the Kotlin of `build-logic` once (tens of
  seconds), the verification file gained about 420 lines for the Kotlin toolchain, and precompiled scripts
  cannot use the type-safe catalog accessors (`libs.…`), so they read the catalog by alias through
  `Catalog.kt`. A script-level `val` captured by a task action is a configuration-cache error that a
  root closure never had; the conventions copy such values into locals.
- **Not covered:** `rootProject` accesses in the module scripts, JaCoCo aggregation and the
  prefix rules of the image-reuse and SBOM scripts — they belong to the extraction itself. *Done
  2026-10-12 in [ADR-0249](0249-an-applications-library-modules-are-named-app-name-and-the-build-reads-the-name.md).*

## Alternatives considered

- **`buildSrc`** — rejected: Gradle's guidance prefers an included build, and any change to `buildSrc`
  invalidates every task's classpath.
- **Keep the root `subprojects {}` blocks** — rejected: it is the shape isolated projects forbid and
  the reason a new module can silently miss a convention.
- **One `basetool.module` plugin applying everything** — rejected: `test-support` has no JaCoCo,
  SpotBugs or SBOM and the libraries have no PIT; a plugin that applies all would change tasks.
- **Type-safe catalog accessors through the `libs.javaClass` workaround** — rejected: it depends on
  generated-class internals and is unsupported.
- **A second verification file in `build-logic/`** — rejected: Gradle ignores an included build's own
  metadata and uses the current build's.
