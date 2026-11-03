# ADR-0333 — The backend uses Guava's immutable collections for constant catalogues

- **Status:** Accepted
- **Date:** 2026-11-03
- **Deciders:** @greluc
- **Related:**
  [ADR-0237](0237-error-prone-and-nullaway-check-the-nullness-annotations-at-compile-time.md)
  (Error Prone in the backend build),
  [ADR-0208](0208-gradle-verifies-every-dependency-against-a-committed-sha-256.md) (dependency
  verification)

## Context

Error Prone's `ImmutableEnumChecker` warns on every enum instance field whose type it cannot prove
immutable. `DashboardWidgetType` held its options and status choices as `java.util.Set` fields, and
`@Getter` handed out the `EnumSet` itself, so any caller could change a constant's catalogue for the
whole JVM. The JDK has no collection *type* that states immutability: `Set.of` and
`Collections.unmodifiableSet` are immutable at runtime but still typed `Set`, which the checker
treats as mutable. Guava was on the build-logic classpath only, not on the backend's.

## Decision

1. **The backend depends on Guava** (`libs.guava`, the catalog's floored version) as an
   `implementation` dependency. A constant held by an enum or another constant catalogue uses
   `ImmutableSet` / `ImmutableList` / `ImmutableMap` (an enum set through `Sets.immutableEnumSet`),
   so the type itself carries the guarantee and Error Prone verifies it.
2. **`error_prone_annotations` is a `compileOnly` dependency** of the backend, so a value type a
   constant holds can carry `@com.google.errorprone.annotations.Immutable`, which Error Prone's
   `ImmutableChecker` then enforces.
3. Ordinary collections in services, DTOs and entities stay JDK collections; Guava is not a
   general-purpose utility library for the backend.

## Consequences

- The `ImmutableEnumChecker` warnings disappear without a suppression, and a constant's catalogue
  can no longer be changed through its getter.
- The backend jar ships Guava and its runtime dependencies (`failureaccess`, `jspecify`,
  `error_prone_annotations`, `j2objc-annotations`); the SBOM, the licence report and
  `verification-metadata.xml` list them, and Guava advisories now affect the backend image.
