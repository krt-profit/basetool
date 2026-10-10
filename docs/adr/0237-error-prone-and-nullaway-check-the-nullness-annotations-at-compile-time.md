# ADR-0237 — Error Prone and NullAway check the nullness annotations at compile time

- **Status:** Accepted — implemented 2026-10-10 for the backend's module `api` packages
- **Date:** 2026-10-01
- **Deciders:** @greluc (owner decision D-13)
- **Related:** [domain modularisation plan](../DOMAIN_MODULARISATION_PLAN.md) §8.1 (*Nullness*),
  §6.3, §10 ·
  [ADR-0192](0192-lombok-and-the-jetbrains-annotations-apply-to-every-source-set.md) (the JetBrains
  annotations) · [ADR-0208](0208-gradle-verifies-every-dependency-against-a-committed-sha-256.md) ·
  [ADR-0231](0231-the-backend-becomes-a-modular-monolith-one-package-per-domain.md) · arc42 §11.4

## Context

ADR-0192 put the JetBrains `@NotNull` / `@Nullable` annotations on every source set, and the root
`CLAUDE.md` asks for them wherever the code establishes the contract. Nothing checks that they stay
true: a method that starts returning `null` under a `@NotNull` compiles cleanly (arc42 §11.4).
SpotBugs catches some of it at call sites, as a backstop. Module APIs (ADR-0231) make nullness a
contract between modules, where a wrong annotation crosses an owner boundary.

Error Prone 2.50.0 with NullAway 0.14.2 runs on JDK 25 and recognises the JetBrains annotations by
simple name; Spring Framework builds itself with the same pair.

## Decision

1. **Error Prone with NullAway becomes a compile-time gate** on the JetBrains annotations, as a
   compiler plugin on `compileJava` only. Nothing reaches a runtime classpath, an image or an SBOM.
2. **It starts with the module `api` packages** (NullAway's annotated-packages setting names them)
   and widens package by package from there; a package joins only when it compiles clean.
3. **NullAway findings are errors**; Error Prone's own checks run at their default severities, and
   any check switched off is switched off in the build script, never by a suppression in the code.
4. **The Lombok interplay is proven on one module first**, with the generated null-checks of
   `lombok.addNullAnnotations = jetbrains` in place, before the gate widens.
5. **JSpecify stays out** for now (ADR-0192); the gate reads the annotations the code already
   carries.
6. The new compile-time dependencies pass the usual supply-chain gates: a regenerated
   `gradle/verification-metadata.xml` (ADR-0208), the licence gate, and a clean configuration cache.

## Consequences

- A `@NotNull` return that can yield `null`, or a dereference of a `@Nullable` value, fails the
  build in the covered packages; arc42 §11.4 closes package by package.
- Cost: compilation gets slower, javac needs about ten `--add-exports` for Error Prone on JDK 25,
  and a new Error Prone release can add findings that block a toolchain bump until fixed.
- Annotations in covered packages must be correct, not only present; a guessed annotation now
  fails instead of misleading.

## Alternatives considered

- **SpotBugs alone** — catches some call-site dereferences, not the contract of a method's own
  return.
- **JSpecify annotations and its checker mode** — a second annotation vocabulary beside 1,063
  JetBrains annotations ADR-0192 applied.
- **The Checker Framework** — sound, much heavier on annotations and compile time than this code
  base needs.
- **Everything at once** — thousands of findings on the first run; the package-by-package ratchet
  keeps each step reviewable.

## Implementation (2026-10-10)

- `backend/build.gradle.kts` applies `net.ltgt.errorprone` 5.1.1 with `error_prone_core` 2.50.0 and
  NullAway 0.14.2 on the `errorprone` configuration, to `compileJava` only; every other compile task
  has Error Prone switched off. `NullAway:AnnotatedPackages` lists the 18 module `api` packages, which
  include their `api.events` sub-packages. NullAway findings are errors; Error Prone's own checks run
  at their default severities, except `StringConcatToTextBlock`, switched off in the build script
  because it crashes on a constructor call in `CustomJwtGrantedAuthoritiesConverter`
  (`NoSuchElementException` in `Iterables.getLast`).
- The first run found seven wrong or missing annotations, all in published types:
  `NotificationEvent.actorSub()` is documented as nullable but was unannotated; four
  `contextRecipientUserId()` overrides (two bank booking-request events, two Materialbörse events)
  return a `@Nullable` component; `InventoryAllocations.jobOrderSlice` and `missionSlice` document a
  nullable id but did not say so.
- **Lombok interplay (decision 4)** is proven on the `api` packages, ten of whose classes use Lombok:
  they compile clean, and a planted `@Getter` over a `@Nullable` field whose getter is dereferenced is
  reported, because `lombok.addNullAnnotations = jetbrains` and Lombok's copied annotations reach the
  generated accessor. A planted `return null` from an unannotated method in an `api` package fails
  the compile as well.
- The configuration cache stays clean; `gradle/verification-metadata.xml` is regenerated with the
  ADR-0208 command. Nothing reaches a runtime classpath.
- **Widened (2026-10-10)** to the whole `joborder`, `refinery` and `materialexchange` modules
  (`api`, `web` and `internal`), the first three Phase 3 modules. Their first run reported 49
  findings, nearly all a missing `@Nullable`: record components the services fill with `null`
  (among them six components of the two published job-order events and three of the refinery's
  `ImportIssueDto`), parameters that take `null`, the board queries' optional filters, and the
  offer's and the request's owning org unit. The owner redactor's three methods carry
  `@Contract("null -> null; !null -> !null")`, two map reads the code guarantees present are
  wrapped in `Objects.requireNonNull`, and the blueprint-owner fold skips a missing product name
  before it builds a family, as its empty match key already did.
- **Widened (2026-10-10)** to the whole `mission` module. Its first run reported 50 findings:
  missing `@Nullable` on the optional parameters of the mission commands (search filters, the
  core and schedule patches, participant add, party lead, owning org unit, step meta), 19
  `getAuthority()` dereferences in `MissionSecurityService`, now compared constant-first, and two
  reorder lookups wrapped in `Objects.requireNonNull`.
