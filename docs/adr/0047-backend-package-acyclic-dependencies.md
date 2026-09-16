# ADR-0047 — Backend packages form an acyclic dependency graph

- **Status:** Accepted
- **Date:** 2026-06-27
- **Deciders:** @greluc
- **Related:** [`ArchitectureTest`](../../backend/src/test/java/de/greluc/krt/profit/basetool/backend/ArchitectureTest.java) (`backendPackagesShouldBeFreeOfDependencyCycles`, `supportPackageMustStayADependencyLeaf`, `mapperLayerShouldNotReachIntoSecurityContext`) · ADR-0012 (layering)

## Context

The backend's first-level packages (`config`, `service`, `controller`, `mapper`, `model`,
`repository`, `exception`, `integration`, `event`, `filter`, `validation`, …) are treated as
architectural "slices". An ArchUnit cycle probe over those slices found **nine package
dependency cycles** that had accreted over time:

1. `config ⇄ filter`
2. `config ⇄ service`
3. `config → service → exception → config`
4. `config → service → integration → config`
5. `event ⇄ service`
6. `integration ⇄ service`
7. `mapper ⇄ service`
8. `model ⇄ validation`
9. `model → validation → repository → model`

Package cycles defeat layering, make the build order ambiguous, block extracting a package
into its own module, and let an innocent-looking edit close a loop that ripples across
unrelated subsystems. The trigger was a follow-up to a name-sorted-primary-Staffel helper
that had to be shared between the `mapper` and `service` layers: parking it in either layer
would have deepened the pre-existing `mapper ⇄ service` cycle. That raised the question of
whether the graph could be made acyclic and *kept* acyclic by a gate.

Most of the cycles came from a single recurring cause: a class sitting in the wrong slice.
`@Component`/value classes that collaborate with the `service` layer were physically in
`config` (`CustomJwtGrantedAuthoritiesConverter`, `DefaultBlueprintBootstrap`,
`OrgUnitContextualAuthority`); SC-Wiki sync *orchestrators* that call services were in
`integration`; a notification listener that calls a service was in `event`; a constraint
validator reached from `validation` down into `repository`/`model` while `model.dto` carries
the constraint annotation back up into `validation`.

## Decision

**We will keep the backend's package dependency graph acyclic, enforced by an ArchUnit
`slices().…beFreeOfCycles()` rule (`backendPackagesShouldBeFreeOfDependencyCycles`).** We
removed all nine cycles by moving each misfiled class to the slice it actually belongs to,
and — where a class genuinely must be shared across layers or a dependency must point
"upward" — by inverting the dependency through a **dependency-leaf interface**:

- **New leaf package `support`** holds shared, dependency-free collaborators and value types
  (depending only on `model` / `repository` / the JDK / framework annotations), guarded by
  `supportPackageMustStayADependencyLeaf`. It now hosts `StaffelMembershipResolver`,
  `NotificationParamsCodec`, the `@ConfigurationProperties` holders `RateLimitProperties` /
  `AppProblemProperties`, the `OrgUnitContextualAuthority` value, and the two leaf SPIs below.
- **`config → service` removed** by relocating `CustomJwtGrantedAuthoritiesConverter` +
  `DefaultBlueprintBootstrap` into `service` and `OrgUnitContextualAuthority` into `support`;
  `SecurityConfig` now injects the converter through the Spring `Converter<…>` interface, so
  `config` no longer names a `service` type. (Kills cycles 2, 3, 4.)
- **`filter`/`exception` → `config` removed** by moving the two `@ConfigurationProperties`
  holders into `support`. (Kills cycle 1.)
- **`event → service` removed** by moving `NotificationEventListener` into `service`, leaving
  `event` a pure payload leaf. (Kills cycle 5.)
- **`integration → service` removed** by moving the SC-Wiki sync orchestrators into a new
  `service.scwiki` package (foreshadowed by `SC_WIKI_SYNC_PLAN.md`), leaving `integration`
  with only the HTTP clients (`UexClient`, `ScWikiClient`). (Kills cycle 6.)
- **`validation → model`/`repository` removed** by inverting the material lookup behind the
  `MaterialPieceTypeLookup` leaf interface (in `validation`, returns a plain `boolean`),
  implemented by `MaterialPieceTypeLookupService` in `service`. (Kills cycles 8, 9.)
- **`mapper → service` removed** by inverting `MissionMapper`'s access lookups behind the
  `MissionViewerAccess` leaf interface (in `support`), implemented by
  `MissionViewerAccessService` in `service`; and by moving the pure `NotificationParamsCodec`
  to `support`. (Kills cycle 7.)

All changes are behaviour-preserving package moves and dependency inversions — no API, DB,
or business-logic change. Spring wiring is by-type, so the relocations are transparent at
runtime; `@ConfigurationPropertiesScan` registers the moved properties wherever they live.

## Approval gate

Cycle 7 supersedes a previously **endorsed** pattern: the `mapperLayerShouldNotReachIntoSecurityContext`
rule comment told mappers to depend on `AuthHelperService` for auth lookups. The new rule is
"a mapper depends on a dependency-leaf SPI (e.g. `MissionViewerAccess`), never on the service
layer directly" — `AuthHelperService` is still the only thing that reads the security context,
but now from the *implementation* side. Per CLAUDE.md, changing an endorsed pattern needs
owner (@greluc) sign-off; **@greluc approved this change on 2026-06-27**, so the ADR is Accepted.
The `mapper ⇄ service` inversion was the only part gated on approval — the other eight cycle
removals are routine relocations.

## Consequences

- **Easier:** the slice graph is a DAG; a regression (a mapper importing a service, `support`
  importing upward, a `config` `@Component` reaching into `service`) fails the build instead
  of silently accreting. Shared helpers have an obvious home (`support`); cross-layer needs
  have a named pattern (leaf SPI + service-side impl).
- **Harder / costs we accept:** two extra leaf interfaces + impls (`MaterialPieceTypeLookup`
  / `…Service`, `MissionViewerAccess` / `…Service`) for indirection; `ScWikiClient.paceForRateLimit()`
  widened from `protected` to `public` so the relocated orchestrators can pace their loops;
  one new top-level concept (`support`) and one new sub-package (`service.scwiki`); a larger
  one-time rename diff (git rename-detection preserves history).
- **Follow-up:** the `backendPackagesShouldBeFreeOfDependencyCycles` gate is backend-only;
  the other modules (`frontend`, `ingest`, `keycloak-spi`) are not yet cycle-gated.

## Alternatives considered

- **Leave the cycles, freeze a baseline** (ArchUnit `FreezingArchRule`) so only *new* cycles
  fail — rejected: it blesses nine real cycles permanently and the cleanup turned out to be
  mostly mechanical relocations with no behaviour risk.
- **Targeted "mapper must not depend on service" rule only** — rejected: the `mapper`
  package already depended on `service` via the endorsed `MissionMapper → AuthHelperService`
  seam, so a blanket rule would have failed on pre-existing, partly-by-design code without
  first inverting that seam.
- **Keep the shared helpers in `service` and let the mapper import them** — rejected: that is
  exactly the `mapper → service` edge that closes the cycle; the leaf-SPI inversion is what
  breaks it.
- **Backend-wide `beFreeOfCycles` left unenforced (a one-off cleanup)** — rejected: without
  the gate the graph would re-acquire cycles within a few PRs.
