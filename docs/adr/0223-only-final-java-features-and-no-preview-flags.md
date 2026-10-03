# ADR-0223 — Only final Java features, and no preview flags

- **Status:** Accepted — amended 2026-10-02 (corrections, and `ScopedValue` for the backend's change
  source, see below)
- **Date:** 2026-09-27
- **Deciders:** @greluc (owner)
- **Related:** [ADR-0209](0209-the-images-ship-a-java-aot-cache-trained-eagerly-and-verified-at-build.md)
  (the AOT cache that already carries startup) ·
  [ADR-0180](0180-compact-object-headers-on-java-25.md) (a JDK 27 default taken early on 25) ·
  arc42 §2.2 · `CLAUDE.md` → *Java conventions*

## Context

The applications run on the **JDK 25** toolchain (`build.gradle.kts`, `JavaLanguageVersion.of(25)`),
the current LTS. `keycloak-spi` compiles with the same toolchain but emits Java-21 bytecode
(`options.release.set(21)`), because the Keycloak JVM runs 21.

Java 25 brought eight developer-facing JEPs. Four are **final**, four are not:

| JEP | Feature | Status in 25 |
| --- | --- | --- |
| 506 | Scoped Values | final |
| 511 | Module Import Declarations (`import module …;`) | final |
| 512 | Compact Source Files and Instance Main Methods | final |
| 513 | Flexible Constructor Bodies (statements before `super(…)` / `this(…)`) | final |
| 502 | Stable Values | preview |
| 505 | Structured Concurrency | 5th preview |
| 507 | Primitive Types in Patterns, `instanceof` and `switch` | 3rd preview |
| 508 | Vector API | incubator |

Each was checked against the code as it stands:

- **Scoped Values.** The frontend has four hand-written `ThreadLocal`s: `CorrelationContext`,
  `ActiveSquadronContext`, `ClientIpContext` and `SessionAttributeRepairQueue`. The first three are
  relayed onto Reactor Netty threads by the `ThreadLocalAccessor`s in
  `ReactorContextPropagationConfig`, whose `setValue` / `restore` contract writes a value
  imperatively. A `ScopedValue` can only be bound around a block, so that relay cannot carry it
  and would carry nothing without failing. A scoped value is inherited only by
  `StructuredTaskScope` forks, which are preview on 25, so the virtual-thread executor in
  `ParallelPageLoader` would still need its values copied by hand. The MDC,
  `SecurityContextHolder` and `RequestContextHolder` belong to the frameworks and stay
  `ThreadLocal` in any case.
- **Module import declarations.** Only named modules can be imported, which on a classpath
  application means the JDK's own modules, not Spring or any other library. The declaration is a
  wider wildcard than `.*`, which Google Java Style rejects, and Checkstyle's `AvoidStarImport`
  does not flag it. Importing two JDK modules can make a simple name ambiguous, as `Date` is for
  `java.base` plus `java.sql`.
- **Compact source files.** The repository's scripts are Python and shell, with their own
  `*.test.sh` harnesses and CI steps. None of them is a single-file Java program, and nothing in the
  three Spring Boot applications has an entry point this JEP would simplify.
- **Flexible constructor bodies.** None of the 34 explicit `super(args)` calls in `src/main` has
  argument validation or preparation around it; the other constructors are Lombok-generated or
  record constructors. Two constructors use the pre-25 workaround of a static helper inside
  `this(…)` (`BackendHealthIndicator`, `AssetAwareAuthenticationSuccessHandler`) and read well as
  they are.
- **Tooling.** A sample file with `import module java.base;` and statements before `super(…)`
  compiles with javac 25, is left unchanged by google-java-format 1.36.1, and passes Checkstyle
  14.1.0 with `config/checkstyle/google_checks.xml`.
- **Preview features.** Using one needs `--enable-preview` on every compile task, on the test
  JVMs, in the entrypoint of all three images and in the AOT-cache training run. The API may change
  between rounds: Stable Values were renamed to Lazy Constants in JDK 26. The project stays on LTS
  releases, so a feature that is preview in 25 is never final on the toolchain it runs.
  Startup, the argument for Stable Values, is already covered by the AOT cache (ADR-0209).
- **JDK 26 and 27 add no final language or library feature.** Their final JEPs are runtime,
  garbage-collector, TLS and JFR changes (500, 504, 516, 517, 522 in 26; 523, 527, 534, 536 in 27).
  Lazy Constants, Structured Concurrency and primitive patterns are still preview in 27, and the
  Vector API is still incubating. An upgrade short of the next LTS therefore changes nothing this
  decision covers.

## Decision

1. **Only final Java language and library features are used.** No module, source set, test task
   or image passes `--enable-preview`, and no incubator module (`jdk.incubator.*`) is added.
2. **Flexible constructor bodies (JEP 513) are allowed in new or changed code** where validating or
   preparing an argument before `super(…)` / `this(…)` is clearer than a static helper. Existing
   constructors are not rewritten for it. It does not apply to `keycloak-spi` while that module
   emits Java-21 bytecode.
3. **Module import declarations (JEP 511) are not used** in any source set. Imports stay
   single-type, as Google Java Style requires.
4. **The frontend's request-context holders stay `ThreadLocal`.** Scoped Values (JEP 506) are
   reconsidered only when Structured Concurrency is final on the toolchain the project runs and
   the Reactor relay has a scoped-value-aware counterpart.
5. **Compact source files (JEP 512)** are not a reason to rewrite a script in Java. They are
   available if a single-file Java tool is ever written.

## Consequences

- The build and the images have no preview flag to keep in sync across compile, test, AOT training
  and runtime, and a JDK upgrade cannot break code built on a preview API.
- Stable Values and Structured Concurrency are unavailable until they are final on a toolchain
  the project runs. `ParallelPageLoader` keeps its explicit context copy until then.
- Nothing enforces decisions 1 and 3 in CI yet. A preview flag would show up in a build-script
  diff, and a module import in a source diff. Both fail review against this ADR.
- The next toolchain move re-runs this check against that release's final JEPs.

## Alternatives considered

- **Enable preview features for Stable Values and Structured Concurrency** — rejected. The flag
  has to reach the compiler, every test JVM, three entrypoints and the AOT training run. The APIs
  still change between rounds. The startup gain is already taken by the AOT cache.
- **Move the frontend's request context to Scoped Values** — rejected. The Reactor relay cannot
  bind a scoped value, so the correlation id and the active-squadron pin would silently stop
  reaching outbound calls. Executor threads do not inherit scoped values without Structured
  Concurrency.
- **Allow `import module` for JDK modules** — rejected. It saves lines the IDE writes anyway, hides
  where a type comes from, and brings in name ambiguities between modules.
- **Rewrite the two static-helper constructors with JEP 513** — rejected as churn with no
  behavioural gain.

## Amendment — 2026-10-02: corrections, and `ScopedValue` for the backend's change source

**Corrections** (domain modularisation plan §8.1 and §15; the code is right):

- **JDK 26 does add a final library feature:** JEP 517, HTTP/3 for the HTTP Client API. The Context
  listed it among the runtime changes. The decision is unaffected — the project stays on the JDK 25
  LTS — but the next toolchain check starts from the corrected list.
- **JEP 510, the Key Derivation Function API, is final in 25** and was missing from the table of
  Java 25's developer-facing JEPs. Nothing in the code needs it.
- **Checkstyle already enforces two of the bans, in `main` only.** `config/checkstyle/google_checks.xml`
  carries `AvoidModuleImport` and the `CompactSourceFileNotAllowed` check, which only
  `checkstyleMain` runs; the `test` and `e2e` source sets run `javadoc_position.xml` (ADR-0222) and
  are not covered. "Nothing enforces decision 3 in CI" was therefore wrong for `main`.
- **There are 42 explicit `super(args)` calls in `src/main`, not 34** (recounted on 2026-10-02:
  backend 27, ingest 9, frontend 4, keycloak-spi 2).
- **A `--enable-preview` gate is still missing**: decision 1 is held by review alone.

**`ScopedValue` for `ChangeSource.ON_BEHALF`.** The backend's `ChangeSource` keeps the exchange
client a confirmed write is recorded for in a `ThreadLocal` that `asClient` sets and restores
around one block, and the change-feed attribution (ADR-0224) reads it. That is exactly the binding a
`ScopedValue` (JEP 506, final in 25) expresses, without a value that can leak into a reused thread.
It becomes a `ScopedValue` bound with `ScopedValue.where(…).call(…)`, guarded by the
`ChangeSourceTransactionManager` integration test. Decision 4 is unchanged: it concerns the
frontend's request-context holders, which the Reactor relay still cannot carry as scoped values.
