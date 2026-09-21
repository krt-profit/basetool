# ADR-0192 — Lombok and the JetBrains annotations apply to every module and every source set

- **Status:** Accepted
- **Date:** 2026-09-21
- **Deciders:** @greluc
- **Related:** `CLAUDE.md` → *Java conventions* · `lombok.config` · ADR-0139 (test artifacts)

## Context

`CLAUDE.md` has said two things about Java style since early in the project: *"Lombok — maximize
it"*, and *"JetBrains annotations (`@NotNull`, `@Nullable`, `@Contract`) wherever they communicate a
real contract"*. A sweep of the whole repository against those two sentences found that neither was
applied where the build simply never offered it, and that the gaps were not decisions anybody had
taken — they were the defaults of Gradle's source-set wiring.

**The build offered the two toolkits to three of five modules, and to one source set in each.**
`backend`, `frontend` and `ingest` declared `compileOnly`/`annotationProcessor` for Lombok and
`compileOnly` for `org.jetbrains:annotations`. Those configurations feed `main` only: Gradle's
`testCompileOnly` does not extend `compileOnly`, and `testAnnotationProcessor` does not extend
`annotationProcessor`. So the 951 test sources and the frontend's 100 Playwright `e2e` sources
could not use either toolkit — measured: 4 of those 1,051 carried a JetBrains import, 0 a Lombok one.
`keycloak-spi` had the annotations wired but no Lombok at all and, at the time of writing, zero uses
of either across its 10 main sources; `test-support` had neither.

**Lombok's own generated code carried no nullity at all.** `lombok.config` set two keys —
`config.stopBubbling` and `lombok.addLombokGeneratedAnnotation`. `lombok.addNullAnnotations`
defaults to `none`, so every `toString()`, `equals`, `builder()`, `build()`, `with*` and
`@Builder.Singular` adder lombok emitted was un-annotated, in ~625 files that use lombok. This half
is invisible in source review, which is why it survived: nobody reads generated members.

Two properties of lombok 1.18.46 — the version Spring Boot 4.1.1 manages — were read out of its
source rather than assumed, because they decide how much configuration is actually needed:

- `org.jetbrains.annotations.NotNull`, `.Nullable` and `.UnknownNullability` are already in
  `HandlerUtil.BASE_COPYABLE_ANNOTATIONS`, so lombok copies them from a field onto the accessors it
  derives. **No `lombok.copyableAnnotations` entry is needed for them.**
- `org.jetbrains.annotations.NotNull` is also in `HandlerUtil.NONNULL_ANNOTATIONS`, which
  `HandleSetter`, `HandleConstructor`, `HandleWith` and `HandleSuperBuilder` consult to decide
  whether to emit a runtime null-check. **Annotating a field therefore also strengthens the
  generated constructor**, which is a behaviour change and is called out below.

`org.jetbrains:annotations` is at `26.1.0`, which is the newest release on Maven Central — nothing
to bump.

## Decision

**We will make both toolkits available everywhere Java is compiled in this repository, and we will
let lombok annotate the code it generates.**

1. Every module declares Lombok and `org.jetbrains:annotations` for **every source set it has** —
   `main`, `test`, and the frontend's `e2e`. Both stay `compileOnly` plus `annotationProcessor`
   wherever they appear, so neither reaches a runtime classpath, an image or an SBOM.
2. `keycloak-spi` gets Lombok, with its version pinned in the catalog next to `junit` and for the
   same reason: it is deliberately not a Spring Boot module and has no BOM to supply one. The pin is
   kept **equal** to the Boot-managed version, because `lombok.config` is one shared file at the
   repository root and two lombok versions would read it twice.
3. `lombok.config` sets `lombok.addNullAnnotations = jetbrains`.
4. The two toolkits are then applied to the code that had none: all 10 `keycloak-spi` main sources
   and all 3 `test-support` main sources, plus the hand-written boilerplate a sweep of `main` turned
   up — 15 constructors that did nothing but assign their fields, and one hand-written getter.
   Inside `keycloak-spi` the same sweep replaced four more such constructors and all five
   hand-rolled JBoss loggers, with `@RequiredArgsConstructor` and `@JBossLog`.

**A nullity annotation is written only where the code establishes it**, never where it merely seems
plausible. A parameter the body dereferences is `@NotNull`; a parameter the body null-checks, or a
return documented as absent, is `@Nullable`; an SPI callback parameter the body never touches gets
nothing, because its nullity is Keycloak's contract and not ours. `@Contract` is used for the
relations a caller can act on (`"null -> null"`, `pure = true`), and `@Unmodifiable` only where the
value really is a `List.of` / `Set.of` / `List.copyOf` — a mutable `ArrayList` handed back by a
getter keeps `@NotNull` alone.

## Consequences

- Every Lombok-generated member across the repository now carries an accurate JetBrains nullity
  annotation, which is the largest single gain here and cost one configuration line. Both
  annotations are `CLASS`-retention, so nothing changes at runtime; they must, however, be on the
  **compile** classpath of every source set that runs lombok, which is exactly what point 1 above
  guarantees. Getting this ordering wrong is the one way this change breaks a build.
- **Moving a `@NotNull` from a hand-written constructor parameter onto its field adds a runtime
  null-check** that the hand-written constructor did not have: lombok emits one for any field
  carrying a recognised non-null annotation. Every call site involved is either Spring injecting a
  bean or an enum constant passing a literal, so no reachable caller can pass `null` — but the
  failure mode moves from a later `NullPointerException` to an immediate one, and that is a change,
  not a no-op.
- `@Unmodifiable` and `@Contract(pure = true)` enter the codebase for the first time. They are
  documentation for the IDE and for readers; no build gate enforces them, so a wrong one is a lie
  nothing will catch. That is the same standing risk `@NotNull` already carries and the reason for
  the "only where the code establishes it" rule above.
- The 951 test sources and 100 e2e sources may now use both toolkits. **They do not yet** — this
  decision opens the door, it does not walk through it, and no test source was rewritten here.
- **The large gap this ADR does not close:** 555 of the 1,763 main sources now carry a nullity
  annotation, leaving about 1,200 that do not. Some legitimately have nothing to annotate (an enum
  of constants, a marker interface); most are records and classes whose contracts are simply
  unstated. Closing that needs a per-file reading of what is actually nullable — a wrong `@NotNull`
  is worse than none, and on a field it now also generates a null-check — so it cannot be done
  mechanically, and it is deliberately left as follow-up work rather than guessed at in bulk.

## Alternatives considered

- **`lombok.copyableAnnotations += org.jetbrains.annotations.NotNull`** — rejected as a no-op:
  lombok 1.18.46 already carries the three JetBrains nullity annotations in its hardcoded
  `BASE_COPYABLE_ANNOTATIONS` list. Adding them would have read as though it did something.
- **`lombok.addNullAnnotations = jspecify` (or `spring`)** — rejected. Both would need a new
  dependency, and the repository already writes `org.jetbrains.annotations` in 542 files by hand. A
  second nullity vocabulary next to the first is worse than either alone.
- **`lombok.extern.findbugs.addSuppressFBWarnings = true`** — rejected. It would put a blanket
  `@SuppressFBWarnings` on all generated code, which is a suppression, and `CLAUDE.md` allows those
  only per call site with a reason. It would also add a compile dependency on the FindBugs
  annotations for no defect anybody has observed.
- **`@UtilityClass` on the two utility classes with private constructors** — rejected. It is an
  experimental lombok feature and it silently makes members `static`; the classes already say what
  they are with `final` + a documented private constructor.
- **Replacing the hand-written `equals`/`hashCode` on `AbstractEntity` and the composite-id
  `@Embeddable`s with `@EqualsAndHashCode`** — rejected. `AbstractEntity` unwraps a
  `HibernateProxy` before comparing, which lombok cannot express at all, and the id classes are
  JPA-identity-critical code with their own tests. This is the "optimistic-locking landmine" class
  of change `CLAUDE.md` warns about, for no boilerplate worth removing.
- **Giving `keycloak-spi` the Spring Boot BOM to resolve lombok** — rejected. The module exists to
  be free of our application stack, and a catalog pin says the same thing in one line without
  introducing a plugin it does not otherwise need.
