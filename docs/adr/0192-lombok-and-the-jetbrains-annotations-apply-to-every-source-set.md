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
   up — 17 constructors that did nothing but assign their fields (16 become
   `@RequiredArgsConstructor`, one `@AllArgsConstructor`) and one hand-written getter. Inside
   `keycloak-spi` the same sweep replaced four more such constructors and all five hand-rolled
   JBoss loggers, with `@RequiredArgsConstructor` and `@JBossLog`. After it, `main` contains **no**
   hand-rolled logger and no trivial accessor or pure field-assignment constructor that Lombok
   could express; the handful that remain cannot be expressed by it and are listed under
   *Alternatives considered*.
5. Nullity is then derived **mechanically, from the code itself**, and applied as 1,063
   annotations: 758 method return types, 33 `@Contract("null -> null")`, and 272 parameters — see
   below. It is derived, not authored: nothing in that sweep is a judgement call.

**A nullity annotation is written only where the code establishes it**, never where it merely seems
plausible. A parameter the body dereferences is `@NotNull`; a parameter the body null-checks, or a
return documented as absent, is `@Nullable`; an SPI callback parameter the body never touches gets
nothing, because its nullity is Keycloak's contract and not ours. `@Contract` is used for the
relations a caller can act on (`"null -> null"`, `pure = true`).

`@Unmodifiable` and `@UnmodifiableView` are kept apart, because they say different things:
`@Unmodifiable` claims mutators throw **and** the stored references never change, which is true of
`List.of` / `Set.of` / `List.copyOf` / `Collections.emptyList()`; `@UnmodifiableView` claims only
the first half, which is exactly what `Collections.unmodifiableList(backing)` returns. Conflating
them would state something untrue about every defensive view in the codebase. A mutable
`ArrayList` handed straight back by a getter gets neither and keeps `@NotNull` alone.

### How the 1,063 derived annotations were derived

By a throwaway analyser built on the javac tree API (`com.sun.source`), run over every `main`
source in one parse, which reports a method only when it can *prove* the answer:

- **`@Nullable`** when the method body contains a bare `return null;`. There is no weaker reading of
  that.
- **`@NotNull`** when *every* return in the method's own body is provably non-null: a `new`
  expression, a literal, a string concatenation, `this`, a known non-null factory (`List.of`,
  `Optional.empty`, `ResponseEntity.ok`, …), a local whose initialiser is provably non-null and
  which is never reassigned, a field that is either annotated non-null or `final` with a provably
  non-null initialiser, an enum constant (resolved against an index of every enum constant declared
  in the sources, so the name really is one), or a call to a method in the same file that the same
  analysis has already proven — iterated to a fixpoint.
- **`@Unmodifiable` / `@UnmodifiableView`** by the factory distinction above.
- For an **enum**, a blank `final` field's nullity is decidable outright: the complete set of values
  it can ever hold is the argument each constant passes in that position.

It also derives two things beyond the return type:

- **`@Contract("null -> null")`** when a method's single reference parameter is null-guarded
  straight into `return null;` as the body's first statement. That one statement proves the
  contract on its own, whatever the rest of the body does. Restricted to methods no subclass can
  override, because a contract binds overrides the analyser cannot see.
- **`@NotNull` on a parameter** the body dereferences in its very first statement: if the first
  thing executed reads through the parameter, passing null throws immediately, so the method
  requires non-null. Overrides are skipped — strengthening a precondition a supertype does not
  state would be a claim about callers this file cannot see.

Returns and dereferences inside lambdas, anonymous classes and nested types are attributed to their
own method, not the enclosing one. Anything the analyser cannot prove it leaves alone — which is
why it decides ~1,000 places and not ten thousand. Re-running it against the finished branch
reports **zero** remaining derivable cases: what the analyser can prove, the branch now states.

> [!warning] One correction, recorded because the lesson is the point
> The first version of the parameter rule walked the whole first statement looking for a
> dereference, and so read `return result != null && result.page() < result.totalPages();` as
> proof that `result` is non-null — when the `&&` is precisely what makes that access
> *conditional*. It annotated 35 parameters that are in fact nullable. **SpotBugs caught it**:
> `NP_NULL_PARAM_DEREF`, on a call site passing `null` to one of them. The rule now stops at the
> left operand of `&&` / `||` and at a switch selector, exactly as it already did for a ternary,
> and the 49 affected annotations were withdrawn. A nullity annotation that is merely plausible is
> worse than none, and this is what that looks like in practice.

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
- **What is still unannotated, and why it is not an oversight:** 722 of the 1,763 main sources now
  carry a nullity annotation, up from 542. The remaining ~1,067 are dominated by three groups that
  the evidence in this repository cannot decide:
  - **~570 DTO and projection records.** A record component's nullity is a property of every
    construction site, not of the record, so nothing in the file proves it. Worse, 117 of them
    carry `jakarta.validation.constraints.@NotNull`, which means something *different* — "must be
    non-null once validated", while the record legitimately holds null between deserialization and
    validation. Copying that across as a language-level `@NotNull` would be wrong, not merely
    unproven.
  - **94 Spring Data repository interfaces and 43 MapStruct mapper interfaces**, whose bodies are
    generated by a framework and whose declarations carry no evidence either way.
  - **Methods that return the result of another call**, which needs whole-program type resolution
    to follow. The analyser deliberately stops at the file boundary rather than matching on method
    name, which would be unsound across overloads.

  Closing any of these is a per-case reading, and a wrong `@NotNull` is worse than a missing one —
  on a field it now also generates a runtime null-check. It is left as follow-up rather than
  guessed at in bulk.

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
- **`@ToString` on the five `Material*` entities that hand-write one** — rejected. Those
  implementations print the *foreign-key id* of each `LAZY` association (`jobOrder.getId()`)
  precisely so a log line does not initialise the proxy or surface a member's name; `@ToString`
  would either include the whole association and trigger the lazy load, or exclude it and lose the
  id. Lombok has no third option. `LiveSyncStreamService.Subscription` likewise keeps identity
  `equals`/`hashCode` on a record, which is the opposite of what any generated form would do.
- **`@AllArgsConstructor` on `UserApprovalEvent`** — rejected. Its hand-written constructor takes
  four of the entity's five fields, `id` being database-generated and deliberately excluded;
  `@AllArgsConstructor` would silently widen it to five arguments. Lombok has no "all fields but
  the id" form, so what is there is the correct spelling and not leftover boilerplate. The same
  reasoning leaves `getKind()` on `Organisationsleitung` / `SpecialCommand` / `Bereich` and
  `getFilename()` on the four `ByteArrayResource` subclasses alone: they return a constant or an
  enclosing local, not a field, so `@Getter` has nothing to generate.
- **Giving `keycloak-spi` the Spring Boot BOM to resolve lombok** — rejected. The module exists to
  be free of our application stack, and a catalog pin says the same thing in one line without
  introducing a plugin it does not otherwise need.
