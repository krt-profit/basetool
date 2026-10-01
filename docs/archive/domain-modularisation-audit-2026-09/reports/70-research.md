# 70 — Web research: platform, framework and tooling facts for the modularisation audit

- **Agent prefix:** `70-research` · **Finding IDs:** `RES-NN`
- **All sources read on 2026-09-29.** Every row names its URL, or the source file with the git tag it
  was read at. Release dates come from the GitHub API (`published_at`) or Maven Central metadata.
- **Repository evidence** is quoted as `path:line` under the worktree
  `$REPO` (HEAD `95e945326`).
- **Reliability note.** WebFetch summaries were checked against raw page text, source code or
  release metadata wherever a claim is load-bearing. Five summaries were wrong and are corrected here:
  - the Spring Modulith 2.1.x and NullAway 0.14.0 release years (given as 2024; the API says 2026);
  - "ChangeType updates YAML/XML" (the page does not say so; the source shows a narrower truth);
  - "an unknown SpEL bean fails at startup" (the source shows it fails per call);
  - "@ApplicationModuleListener has no own attributes" (it has four);
  - the Spring Modulith docs' own compatibility matrix, which is stale on the site and still lists
    "2.0 (snapshot)".

---

## 1. Summary — the ten most important conclusions

1. **Spring Modulith 2.1.1 is the line for Boot 4.1.x.** It was released 2026-08-26 on Boot 4.1.1 and
   Framework 7.0.9, and it supports adopting option A one domain at a time: the `explicitly-annotated`
   detection strategy, nested modules (since 1.3), `Type.OPEN` modules, `@NamedInterface` and
   `allowedDependencies`. Using it in test scope only adds nothing at runtime (Q2, RES-03).
2. **The Modulith event registry is a real transactional outbox with six traps for the Basetool.**
   - The starters put `spring-modulith-core` and ArchUnit 1.4.2 on the production classpath.
   - JDBC schema auto-creation is on by default in 2.1.
   - `serialized_event` and the class name in `event_type` are persisted.
   - Delivery is at-least-once.
   - `@ApplicationModuleListener` runs AFTER_COMMIT in `REQUIRES_NEW`, so it can never carry the
     same-transaction audit that REQ-AUDIT-001 requires (Q2, RES-04).
3. **A package move breaks class names bound in strings, and no refactoring tool rewrites them.**
   - 172 `T(de.greluc…frontend.support.Roles)` references in 22 Thymeleaf templates.
   - The session allow-list prefix `…frontend.model.` (`SessionTypeAllowList.java:86-87`).
   - 47 ArchUnit `"..x.."` package patterns and 54 fully-qualified package strings in tests.
   - `@PreAuthorize` bean names.
   - Jackson type ids already persisted in Redis (RES-17, RES-09).
4. **A `@PreAuthorize("@bean…")` reference is resolved on every call, not at startup.** A renamed bean
   raises `IllegalArgumentException`, which the Basetool maps to HTTP **400**
   (`GlobalExceptionHandler.java:594-604`). No test resolves these names (0 hits, see Appendix
   B-12). This fails closed, but it is a silent functional regression risk for the refactor (Q6,
   RES-08).
5. **Per-domain typed frontend clients are safe only when their `@HttpExchange` proxies run on the
   existing `webClient` bean's filter chain** (`WebClientConfig.java:456-489`).
   - Build the proxies with `HttpServiceProxyFactory` over that bean, or supply `webClient.mutate()`
     through the highest-precedence `forEachClient(InitializingClientCallback)`.
   - A default `@ImportHttpServices` group gets a fresh builder. Boot adds only properties and
     `WebClientCustomizer` beans to it, and the frontend has none, so the OAuth2, org-unit,
     correlation and resilience filters would be missing.
   - `@Cacheable` on such proxies would leak data across users and tenants (Q5, §5 (k)(n), RES-05).
6. **OpenRewrite's Apache-2.0 `ChangePackage` and `ChangeType` rewrite more than Java.** They also
   rewrite class-name values in `application*.yml` and `.properties`, Spring beans XML and
   `META-INF/services`. But:
   - Plugin versions after 7.41.0 and core libraries after 8.90.4 are published only to the
     authenticated "Code Genome Project" repository.
   - The migration recipes (`UpgradeToJava25`, `UpgradeSpringBoot_4_0`, `JUnit5to6Migration`) are
     under the **Moderne Source Available License** (Q7, RES-09).
7. **ADR-0223's JEP numbers are right, but one sentence is wrong.** "JDK 26 and 27 add no final
   language or library feature" is false for libraries: JEP 517 (final in 26) adds
   `HttpClient.Version.HTTP_3` and more. JEP 510 (final in 25) is also missing from its table. The
   decision itself stands:
   - JDK 27 went GA on 2026-09-15 and is not an LTS.
   - Boot 4.1.1 is documented for Java 17–26 only.
   - The next LTS is Java 29 in September 2027 (Q1, RES-01, RES-02).
8. **Error Prone 2.50.0 and NullAway 0.14.2 run on JDK 25 and accept JetBrains `@Nullable` by its
   simple name.** Spring Framework 7.0.9 builds itself with them through `io.spring.nullability`.
   That plugin's default of explicit null marking would contradict ADR-0192, so plain NullAway with
   `AnnotatedPackages` fits better (Q8, RES-10).
9. **Gradle 9.8.0 is current, and the root build blocks Isolated Projects.** The root
   `allprojects {}` and `subprojects {}` blocks (`build.gradle.kts:14`, `:122`) are incompatible
   with Isolated Projects, which is incubating since 9.7.0. Convention plugins in an included
   `build-logic` build are the documented structure and a prerequisite for options B and C (Q9,
   RES-11).
10. **JPMS cannot enforce domain boundaries.** No Spring jar ships a `module-info` (checked:
    spring-core 7.0.9, spring-boot 4.1.1, spring-modulith-core 2.1.1), and spring-framework #18079
    has been open since 2015. Enforcement has to come from ArchUnit, whose Modules API and
    `FreezingArchRule` need no new dependency, or from Spring Modulith or Gradle modules (Q13, Q4,
    RES-15, RES-16).

---

## 2. Answers by question

### Q1 — JDK 24–28, cadence, LTS, JEP status, ADR-0223 check

**Final and non-final JEPs per release** (read from openjdk.org/projects/jdk/NN/)

| Release | GA | Final JEPs | Non-final JEPs |
| --- | --- | --- | --- |
| 24 | 2025-03-18 | 472 Prepare to Restrict JNI · 475 Late Barrier Expansion for G1 · 479 Remove Windows 32-bit x86 Port · 483 AOT Class Loading & Linking · 484 Class-File API · **485 Stream Gatherers** · 486 Permanently Disable the Security Manager · 490 ZGC: Remove Non-Generational Mode · **491 Synchronize Virtual Threads without Pinning** · 493 Linking Run-Time Images without JMODs · 496 ML-KEM · 497 ML-DSA · 498 Warn upon Use of Memory-Access Methods in `sun.misc.Unsafe` · 501 Deprecate the 32-bit x86 Port for Removal | 404 Generational Shenandoah (Experimental) · 450 Compact Object Headers (Experimental) · 478 KDF API (Preview) · 487 Scoped Values (4th Preview) · 488 Primitive Types in Patterns (2nd Preview) · 489 Vector API (9th Incubator) · 492 Flexible Constructor Bodies (3rd Preview) · 494 Module Import Declarations (2nd Preview) · 495 Simple Source Files (4th Preview) · 499 Structured Concurrency (4th Preview) |
| 25 (LTS) | 2025-09-16 | 503 Remove 32-bit x86 Port · **506 Scoped Values** · **510 Key Derivation Function API** · **511 Module Import Declarations** · **512 Compact Source Files and Instance Main Methods** · **513 Flexible Constructor Bodies** · 514 AOT Command-Line Ergonomics · 515 AOT Method Profiling · 518 JFR Cooperative Sampling · 519 Compact Object Headers · 520 JFR Method Timing & Tracing · 521 Generational Shenandoah | 470 PEM Encodings (Preview) · 502 Stable Values (Preview) · 505 Structured Concurrency (5th Preview) · 507 Primitive Types in Patterns (3rd Preview) · 508 Vector API (10th Incubator) · 509 JFR CPU-Time Profiling (Experimental) |
| 26 | 2026-03-17 | **500 Prepare to Make Final Mean Final** · 504 Remove the Applet API · 516 AOT Object Caching with Any GC · **517 HTTP/3 for the HTTP Client API** · 522 G1: Improve Throughput by Reducing Synchronization | 524 PEM (2nd Preview) · 525 Structured Concurrency (6th Preview) · 526 Lazy Constants (2nd Preview) · 529 Vector API (11th Incubator) · 530 Primitive Types (4th Preview) |
| 27 | **2026-09-15 (GA, per its page)** | 523 G1 the Default GC in All Environments · 527 Post-Quantum Hybrid Key Exchange for TLS 1.3 · 534 Compact Object Headers by Default · 536 JFR In-Process Data Redaction | 531 Lazy Constants (3rd Preview) · 532 Primitive Types (5th Preview) · 533 Structured Concurrency (7th Preview) · 537 Vector API (12th Incubator) · 538 PEM (3rd Preview) |
| 28 | in development; no GA date on the page | JEP 542 PEM Encodings is **final** (status *Completed*, release 28). JEP 544 Ahead-of-Time Code Compilation is *Proposed to Target* 28. Also listed: 535 Shenandoah generational by default · 541 Deprecate the macOS/x64 port | 401 Value Objects (Preview) · 539 Strict Field Initialization in the JVM (Preview) · 540 Simple JSON API (Incubator) |

**Status of the JEPs you asked about**

| JEP | Status and content |
| --- | --- |
| 456 Unnamed Variables & Patterns | Delivered, **final in 22**. |
| 467 Markdown Documentation Comments (`///`) | Delivered, **final in 23**. |
| 485 Stream Gatherers | Delivered, **final in 24**. Built-in gatherers: `fold`, `mapConcurrent`, `scan`, `windowFixed`, `windowSliding`. |
| 491 Synchronize Virtual Threads without Pinning | Delivered, **final in 24**. Pinning remains in three cases: while loading a class, in class initialisers, and while waiting for another thread to initialise a class. `jdk.tracePinnedThreads` is removed. |
| 513 Flexible Constructor Bodies | Delivered, **final in 25**. The prologue may assign fields that have no initialiser, but it may not read `this` or call instance methods. |
| 500 Prepare to Make Final Mean Final | Delivered in **26**, component core-libs. The option is `--illegal-final-field-mutation=allow\|warn\|debug\|deny`, default **warn** in 26, and the JEP says **deny** "will become the default in a future release". The per-module opt-in is `--enable-final-field-mutation=M1,…` or `=ALL-UNNAMED` for class-path code. Serialisation libraries should use `sun.reflect.ReflectionFactory`. `sun.misc.Unsafe` is not affected (JEP 498 handles it separately). |

**Cadence and LTS**

- OpenJDK ships "a feature release every six months according to a time-based model"
  (openjdk.org/projects/jdk).
- Oracle's roadmap names 8, 11, 17, 21 and 25 as LTS, with future LTS releases every two years:
  "the next planned LTS release is Java 29 in September 2027". The designations of 27, 28 and 29 are
  marked "subject to change".
- Java 25: Premier Support until September 2030, Extended Support until September 2033.
- Java 27 (non-LTS): Premier Support until March 2027.
- Source: the Oracle page, downloaded with curl because WebFetch received HTTP 403, saved as
  `70-research-oracle-roadmap.html` (lines 658–768).
- **Boot 4.1.1 supports Java 17 "up to and including" Java 26**
  (docs.spring.io/spring-boot/system-requirements.html). JDK 27 is outside Boot 4.1.1's documented
  range.

**ADR-0223 checked point by point** (`docs/adr/0223-only-final-java-features-and-no-preview-flags.md`)

| ADR claim (line) | Verdict |
| --- | --- |
| "Java 25 brought eight developer-facing JEPs. Four are final, four are not" (:17-28) | **Incomplete.** The statuses of the eight listed JEPs are correct. JDK 25 also has **JEP 510 (Key Derivation Function API, final)** and **JEP 470 (PEM Encodings, preview)**, both API-level. No code impact: the only crypto primitive in the code is HMAC in `ExchangeDpopNonces.java:51,141`, and there is no KDF. |
| Final JEPs "500, 504, 516, 517, 522 in 26" (:64) | **Correct and complete** (5 final, 5 non-final). |
| Final JEPs "523, 527, 534, 536 in 27" (:64) | **Correct and complete** (4 final, 5 non-final). |
| "JDK 26 and 27 add no final language or library feature … runtime, garbage-collector, TLS and JFR changes" (:63-64) | **Language: correct. Library: incorrect.** JEP 517 (component core-libs/java.net.http) adds public API: `HttpClient.Version.HTTP_3`, `HttpOption.H3_DISCOVERY` and `Http3DiscoveryMode`. HTTP/3 is opt-in. JEP 504 removes the `java.applet` package, `JApplet` and related API. JEP 500 is a core-libs reflection-integrity change rather than a GC, TLS or JFR change. JEP 527 adds TLS named groups but no new API. |
| "Lazy Constants, Structured Concurrency and primitive patterns are still preview in 27, and the Vector API is still incubating" (:65-66) | **Correct** (531, 533, 532 preview; 537 incubator). PEM is also in its third preview (538) in 27, and is **final in 28** (JEP 542). |
| "Stable Values were renamed to Lazy Constants in JDK 26" (:60) | **Consistent:** JEP 526 is "Lazy Constants (Second Preview)" in 26. |
| Tooling: "passes Checkstyle 14.1.0" (:55-57) | **Stale against the pin.** The catalog pins `checkstyle = "14.3.0"` (`gradle/libs.versions.toml:45`), bumped on 2026-09-27 in `ba80b2a7d` after `49a3dcd4e` (14.2.0). The ADR commit `016567468` is from the same day. The sample check should be re-run on 14.3.0. |
| "An upgrade short of the next LTS therefore changes nothing this decision covers" (:66-67) | **Holds for language features.** Two runtime changes that the next toolchain move must re-check are not mentioned: JEP 500 and Boot's supported JDK range. |

**Implications for the Basetool**

- Stay on 25. The move to 29 (September 2027) needs a Boot line that lists 29.
- Fix ADR-0223's wording (RES-01).
- Before any JDK 26+ trial, run the tests with `--illegal-final-field-mutation=deny`. There are 37
  `ReflectionTestUtils.setField` lines in tests (backend 17, frontend 16, ingest 4). Whether their
  targets are `final` fields is UNKNOWN (RES-02).
- JEP 523 changes nothing here, because every service pins its GC: G1 in
  `quadlet/env.d/backend.env.tmpl:42` and `frontend.env.tmpl:21`, Serial in `ingest.env.tmpl:25`.
- JEP 534 was already taken early through `-XX:+UseCompactObjectHeaders` (ADR-0180).
- JEP 544 (AOT code compilation, 28) extends the AOT cache of ADR-0209 and matters at the move to 29.
- JEP 467 `///` comments are final, but the owner's comment rule allows only Javadoc `/** */` for
  Java. Using them needs an ADR-0214 amendment. Whether Checkstyle 14.3.0 treats `///` as Javadoc is
  UNKNOWN.

### Q2 — Spring Modulith

**Versions and compatibility**

- Latest GA is **2.1.1**, published 2026-08-26. It upgrades to Spring Boot 4.1.1 and Spring Framework
  7.0.9 (GitHub release notes, dates from the API).
- **2.1.0** (2026-06-11) moved the line to "Spring Boot 4.1".
- 2.0.8 is the Boot 4.0.x line and 1.4.13 the Boot 3.5.x line.
- 2.2.0-M1 (2026-08-26) targets Boot 4.2 M1 and Framework 7.1 M1. Maven Central already has
  2.2.0-M2 (metadata `lastUpdated` 2026-09-24).
- The site's appendix matrix is stale: it still shows "2.0 (snapshot)".
- `spring-modulith-core` 2.1.1 depends on **ArchUnit 1.4.2** (compile scope), jspecify 1.0.1 and
  Framework 7.0.9. jMolecules is optional (`jmolecules-ddd` 2.0.1, `jmolecules-archunit` 0.33.0).

**Module detection and declaration** (docs version 2.1.1, fundamentals.html; annotation source at
tag 2.1.1)

| Aspect | Fact |
| --- | --- |
| Default detection | Each **direct sub-package of the main application class's package** is an application module. The base package is the module's API. Sub-packages are internal. |
| Strategies | `spring.modulith.detection-strategy = direct-subpackages` (the default) \| `explicitly-annotated`, which counts only packages carrying `@ApplicationModule` or jMolecules `@Module` \| the fully qualified name of a custom `ApplicationModuleDetectionStrategy`. |
| Nested modules | Supported **since 1.3** by annotating a nested package with `@ApplicationModule`. Only the parent and sibling nested modules may access a nested module. |
| `@ApplicationModule` (source, 2.1.1) | `id`, `displayName`, `allowedDependencies` (defaults to an "open" token, meaning no restriction; syntax `"order"`, `"order :: spi"`, `"order :: *"`), `type` = `CLOSED` (default) \| `OPEN`. An OPEN module exposes its internals and is meant "primarily for legacy codebases". |
| `@NamedInterface` | `value`/`name` (aliases), `propagate` (default `true`). Placed on a `package-info` or a type in a sub-package, it exposes that package to other modules. |
| `@Modulithic` | `systemName`, `useFullyQualifiedModuleNames`, `sharedModules`, `additionalPackages` (extra root packages). |
| Exclusions | `ApplicationModules.of(App.class, JavaClass.Predicates.resideInAPackage("…"))`. |
| Stated limitation | Plain Java visibility cannot hide public types in internal sub-packages. Only verification enforces it. |

**Verification, testing, events, operations**

| Feature | Fact (docs 2.1.1) |
| --- | --- |
| `ApplicationModules.verify()` | Rules: no cycles between modules; references to types in another module's internal packages are rejected, except into OPEN modules; `allowedDependencies` is enforced when declared. If `jmolecules-archunit` is on the classpath, its DDD and architecture rules run automatically. `VerificationOptions.defaults().withAdditionalVerifications(…)`; `detectViolations().filter(…).throwIfPresent()`. |
| Runtime verification | `spring.modulith.runtime.verification-enabled` (needs `spring-modulith-runtime`). It aborts startup on violations. |
| `@ApplicationModuleTest` | Bootstrap modes `STANDALONE` (default), `DIRECT_DEPENDENCIES`, `ALL_DEPENDENCIES`. Beans from other modules come from `@MockitoBean`. `Scenario` and `PublishedEvents` APIs. `@ModuleSlicing` combines with Boot slice tests (new in 2.1). **Stimuli run in a new transaction and are never rolled back**; clean up with `andCleanup(…)`. |
| Change-aware test runs | `spring-modulith-junit`; `spring.modulith.test.reference-commit`, `file-modification-detector`, `on-no-changes`. |
| `@ApplicationModuleListener` (source, 2.1.1) | Meta-annotated with `@Async`, `@Transactional(propagation = REQUIRES_NEW)` and `@TransactionalEventListener` (phase AFTER_COMMIT). Attributes: `readOnlyTransaction`, `id`, `condition`, `propagation`. |
| Event Publication Registry | An entry is written **in the publishing transaction** for each transactional listener, and completion is marked when the listener succeeds. Stores: JPA, JDBC, MongoDB, Neo4j. Status lifecycle since 2.0: PUBLISHED → PROCESSING → COMPLETED / FAILED → RESUBMITTED. APIs: `IncompleteEventPublications`, `FailedEventPublications`, `ResubmissionOptions`. Staleness monitor: `spring.modulith.events.staleness.*`. `republish-outstanding-events-on-restart` defaults to `false`. |
| Completion modes | `spring.modulith.events.completion-mode` = `update` (default) \| `delete` \| `archive` (copies to the archive table, then deletes). |
| JDBC schema (PostgreSQL, v2, source at 2.1.1) | Table `event_publication`: `id UUID PK`, `listener_id`, `event_type` (the class name), **`serialized_event TEXT`**, `publication_date`, `completion_date`, `status`, `completion_attempts`, `last_resubmission_date`. Indexes: a hash index on `serialized_event` and one on `completion_date`. `spring.modulith.events.jdbc.schema-initialization.enabled` **defaults to `true`** (the 2.1.0 notes say "Enable JDBC event publication schema creation by default"). On read, `event_type` is resolved with `ClassUtils.forName`, and a row whose class no longer exists is logged at WARN and **skipped** (`JdbcEventPublicationRepository.loadClass`, source at 2.1.1). |
| Delivery semantics | At-least-once. Listeners must be idempotent (the docs make listeners responsible). |
| Externalisation | `@Externalized` (Modulith's or jMolecules'). Brokers: Kafka, AMQP, JMS, Spring Messaging. `externalization.mode = module-listener` (default) \| `outbox`, which uses Namastack or JobRunr (2.1). |
| Observability | `spring-modulith-observability-core` (split into API and core in 2.1) creates a Micrometer span per module interaction. Metrics `module.events.published` and `module.events.published.$moduleId.$eventType`. Actuator endpoint `/actuator/modulith`. The `spring-modulith-starter-insight` starter bundles both. |
| Documentation | `Documenter`: C4 (default) or UML component diagrams through PlantUML, Application Module Canvas, and an aggregating `all-docs.adoc` written to the build directory. |
| Per-module Flyway | `spring.modulith.runtime.flyway-enabled`. Root migrations live in `db/migration/__root`, per-module ones in `db/migration/<moduleId>` with a **per-module `flyway_schema_history_<moduleId>`**, and version numbers are scoped to the module. Migrations run in module-dependency order with baseline 0 and baseline-on-migrate. |
| Runtime initialisation | `ApplicationModuleInitializer` beans run in module-dependency order. |

**What the starters pull in** (walked with `70-research-pomtree.py`, see Appendix B-9)

- `spring-modulith-starter-jpa` 2.1.1 → `spring-modulith-starter-core` → `spring-modulith-core` →
  **`com.tngtech.archunit:archunit:1.4.2`**, plus `spring-modulith-apt`, `spring-modulith-moments`
  and `jmolecules-events`, all in compile or runtime scope.
- `spring-modulith-events-jpa` + `spring-modulith-events-jackson` alone reach only
  `spring-modulith-events-core`, `-events-api`, Spring and Jackson 3.1.5.

**Implications for the Basetool**

- The backend is package-by-layer, so default detection would turn `controller`, `service`,
  `repository` and the other layers into "modules".
- Use `explicitly-annotated` together with `@Modulithic(additionalPackages=…)` or new top-level
  domain packages. Declare each extracted domain `@ApplicationModule(allowedDependencies=…)`. Keep
  the legacy layer packages outside detection or as OPEN modules until they are empty (RES-03).
- **Do not enable per-module Flyway.** The backend has 256 versioned migrations up to V258 in one
  history table (Appendix B-10), and module-scoped version tables would split REQ-DATA's single
  migration order.
- Keep `/actuator/modulith` on the isolated management port.
- Modulith's metric names do not follow REQ-OBS-011's `basetool_*` scheme. Rename them with a
  `MeterFilter` or leave them off (RES-04).

### Q3 — jMolecules

**Versions** (Maven Central metadata and the BOM POM, read 2026-09-29)

- The current train is **`jmolecules-bom` 2025.0.2** (2025-12-19): jMolecules **2.0.1** (2025-11-20)
  and jmolecules-integrations **0.33.0** (2025-12-19).
- **Pitfall:** Maven Central's `<release>` for `jmolecules-archunit` and the other integrations says
  `1.6.0`, but that artifact is from **2022-06-21** (`Last-Modified` header). 0.33.0 is the current
  one.
- The core README still says 1.9.0.
- The Basetool already resolves the BOM POM (`gradle/verification-metadata.xml:6742`) but no
  jMolecules jar.

**Modules**

| Module | Content |
| --- | --- |
| DDD (`jmolecules-ddd`) | Annotations `@AggregateRoot`, `@Entity`, `@ValueObject`, `@Identity`, `@Repository`, `@Service`, `@Factory`, `@Module`, `@BoundedContext`. Types `AggregateRoot<T, ID>`, `Entity<T, ID>`, `Identifier`, `Identifiable`, `Association<T, ID>`. |
| Events (`jmolecules-events`) | `@DomainEvent` / `DomainEvent`, `@DomainEventHandler`, `@DomainEventPublisher`, `@Externalized`. |
| Architecture | Layered (`@DomainLayer`, `@ApplicationLayer`, `@InfrastructureLayer`, `@InterfaceLayer`). Onion (`@DomainModelRing` …). Hexagonal (`@Application`, `@PrimaryAdapter`, `@SecondaryAdapter`, `@PrimaryPort`, `@SecondaryPort`). CQRS (`@Command`, `@CommandHandler`, `@QueryModel`). |
| `jmolecules-archunit` 0.33.0 (source) | `JMoleculesDddRules.all()`, `entitiesShouldBeDeclaredForUseInSameAggregate()`, **`aggregateReferencesShouldBeViaIdOrAssociation()`**, `annotatedEntitiesAndAggregatesNeedToHaveAnIdentifier()`, `valueObjectsMustNotReferToIdentifiables()`. `JMoleculesArchitectureRules.ensureLayering[Strict]()`, `ensureOnionSimple()`, `ensureOnionClassical()`, `ensureHexagonal(VerificationDepth)`. Every rule also accepts a `StereotypeLookup`. |
| Spring Modulith integration (docs 2.1.1) | jMolecules `@Module` counts for `explicitly-annotated` detection. `verify()` runs the jMolecules rules when they are present. The Canvas lists aggregates "explicitly declared as aggregate via jMolecules", published events marked `@DomainEvent`, and listeners with `@DomainEventHandler`. `@Externalized` is accepted. |

**Implications for the Basetool**

- `aggregateReferencesShouldBeViaIdOrAssociation` is exactly the rule for option A's "replace
  cross-domain entity references with ids". Annotations alone (`@AggregateRoot`) are enough; the
  JPA entities do not need the generic type model.
- Run it wrapped in `FreezingArchRule` (RES-16), because the existing entities reference across
  domains.

### Q4 — ArchUnit

**Versions**

- Latest is **1.5.1** (2026-09-25), which the project already uses (`gradle/libs.versions.toml:28`).
- 1.5.0 (2026-08-04) added:
  - support for Java 27 class files (major version 71);
  - `JavaClass.isSealed()` and `getPermittedSubclasses()`;
  - dependencies from caught exceptions;
  - `archunit-junit6`;
  - a thread-safe `TextFileBasedViolationStore`.
- 1.5.1 "count[s] caught exception types as type dependencies" and adds `java.lang.IO` to
  `ACCESS_STANDARD_STREAMS`.

**Features** (User Guide, archunit.org)

- `layeredArchitecture().consideringAllDependencies().layer(..).definedBy("..controller..")…whereLayer(..).mayOnlyBeAccessedByLayers(..)`.
- `onionArchitecture().domainModels(..).domainServices(..).applicationServices(..).adapter(..)`.
- `slices().matching("..myapp.(*)..").should().beFreeOfCycles()` and
  `notDependOnEachOther()`.
- PlantUML adherence:
  `classes().should(adhereToPlantUmlDiagram(url, consideringAllDependencies() | consideringOnlyDependenciesInDiagram() | consideringOnlyDependenciesInAnyPackage(..)))`.
  It needs component diagrams with stereotype package patterns.
- **Modules API:** `ModuleRuleDefinition.modules().definedByPackages(..)` or
  `definedByAnnotation(AppModule.class)`, then `.should().respectTheirAllowedDependenciesDeclaredIn("allowedDependencies", …)`
  and `.andShould().onlyDependOnEachOtherThroughPackagesDeclaredIn("exposedPackages")`.
- **`FreezingArchRule.freeze(rule)`** records the existing violations in a `ViolationStore`
  (text files by default) and then reports only new ones. Fixed violations shrink the store.
  Refreezing is allowed only with `freeze.refreeze=true`.
- **`archRule.failOnEmptyShould`:** the default is to fail. By default ArchUnit forbids the
  should-part from being evaluated against an empty set of classes. Allowing it takes
  `allowEmptyShould(true)` on the rule or `archRule.failOnEmptyShould=false`.

**Implications for the Basetool**

- The empty-should default protects a rule whose whole `that()` set becomes empty after a move. It
  does **not** protect the 38+ rules keyed on `..service..` and similar patterns while those
  packages still contain some classes. Moving half of a layer weakens those rules without any
  failure (RES-16, RES-17).
- 1.5.1's caught-exception edges can add dependency edges to cycle rules. That is already in effect
  on the current pin.

### Q5 — Spring Framework 7 and Spring Boot 4.0/4.1

**Versions Boot 4.1.1 manages** (`spring-boot-dependencies-4.1.1.pom`, Maven Central)

| Library | Version |
| --- | --- |
| Spring Framework | **7.0.9** |
| Spring Security | 7.1.1 |
| Spring Data BOM | 2026.0.1 (JPA, Commons, JDBC and Redis 4.1.1) |
| Spring Session | 4.1.1 |
| Hibernate | **7.4.5.Final** |
| Hibernate Validator | 9.1.3.Final |
| Jackson 3 / Jackson 2 | 3.1.5 / 2.21.5 |
| Tomcat | 11.0.24 |
| Micrometer / Micrometer Tracing | 1.17.1 / 1.7.1 |
| JUnit Jupiter | 6.0.3 (the project overrides with 6.1.3) |
| Mockito | 5.23.0 (the project uses 5.24.0) |
| Flyway | 12.4.0 (the project overrides with 13.8.0) |
| jspecify | 1.0.1 |
| PostgreSQL JDBC | 42.7.13 |
| Testcontainers | 2.0.5 |

Release dates: Framework 7.0.9 and Boot 4.1.1 on 2026-08-20, Boot 4.1.0 on 2026-06-10. Framework
7.1.0-M2 and Boot 4.2.0-M2 appeared on 2026-09-24.

**HTTP Service Clients** (Framework 7.0.9 reference, rest-clients.html)

- `@HttpExchange` / `@GetExchange` … interfaces. `HttpServiceProxyFactory.builderFor(adapter)` with
  `RestClientAdapter`, `WebClientAdapter` or (deprecated) `RestTemplateAdapter`.
- A `WebClientAdapter` also supports blocking return types, so `Mono`, `Flux` and plain `T` all work.
- `@ImportHttpServices(group, types, basePackageClasses, clientType)` or an
  `AbstractHttpServiceRegistrar` creates **HTTP service groups**. Each group gets its own client
  builder.
- `RestClientHttpServiceGroupConfigurer` / `WebClientHttpServiceGroupConfigurer` beans offer
  `groups.filterByName("x").forEachClient(…)`, `groups.forEachClient((group, builder) -> builder.requestInterceptor(…))`
  and `forEachGroup(…, factoryBuilder)`.
- `HttpServiceProxyRegistry.getClient(group, type)`.
- Also available: `exchangeAdapterDecorator`, custom `HttpServiceArgumentResolver`,
  `@HttpExchange(apiVersion=…)` with `ApiVersionInserter`.

**Boot 4.1.1 support** (reference io/rest-client.html, and source at tag v4.1.1)

- HTTP service interfaces must be imported with `@ImportHttpServices`.
- Properties: `spring.http.serviceclient.<group>.base-url`, headers, API version, redirects,
  timeouts, SSL bundle.
- **`ReactiveHttpServiceClientAutoConfiguration` registers `WebClientCustomizerHttpServiceGroupConfigurer`,
  which applies every `WebClientCustomizer` bean to each group's builder.** It is active only when an
  `HttpServiceProxyRegistry` exists.
- Boot 4.1 adds **`InetAddressFilter`** as SSRF mitigation: `InetAddressFilter.externalAddresses()`
  or `HttpClientSettings.withInetAddressFilter`. Registered as a bean, it applies to all
  auto-configured HTTP client builders.

**API versioning** (webmvc-versioning.html, 7.0.9)

- `ApiVersionStrategy` resolves the version from a header, a query parameter, a path segment
  (`usePathSegment(index)`), a media-type parameter or a custom resolver.
- `SemanticApiVersionParser` is the default parser.
- `@RequestMapping(version = "1.2" | "1.2+")`.
- `ApiVersionConfigurer` offers `addSupportedVersions`, `setDefaultVersion`, `setVersionRequired`
  and `detectSupportedVersions`.
- `InvalidApiVersionException` and `MissingApiVersionException` both map to 400.
- `StandardApiVersionDeprecationHandler` implements RFC 9745 (`Deprecation`) and RFC 8594 (`Sunset`)
  plus `Link`.
- Boot 4.0 auto-configures it through `spring.mvc.apiversion.*` (Boot 4.0 release notes).

**Resilience** (core/resilience.html, 7.0.9)

- `@Retryable(maxRetries = 3 by default, delay = 1s, jitter, multiplier, maxDelay, includes, excludes, predicate)`.
  It supports reactive return types and publishes `MethodRetryEvent`.
- `@ConcurrencyLimit(n)` **blocks** callers above the limit, which suits virtual threads.
- `@EnableResilientMethods` registers `RetryAnnotationBeanPostProcessor` and
  `ConcurrencyLimitBeanPostProcessor`.
- Programmatic `RetryTemplate` and `RetryPolicy.builder()`.
- **Boot does not enable it automatically:** GitHub code search of spring-boot for
  `EnableResilientMethods` and `RetryAnnotationBeanPostProcessor` returned 0 hits on 2026-09-29.
  Code search covers the default branch only.

**JSpecify** (core/null-safety.html, 7.0.9)

- Framework 7 uses JSpecify `@NullMarked`, `@Nullable`, `@NonNull` and `@NullUnmarked`, and
  deprecates `org.springframework.lang`.
- Recommended NullAway options: `OnlyNullMarked=true`,
  `CustomContractAnnotations=org.springframework.lang.Contract`, and optionally
  `JSpecifyMode=true`.

**RestTestClient** (testing/resttestclient.html, 7.0.9)

- A test facade over `RestClient` with `bindToController`, `bindToApplicationContext` (MockMvc),
  `bindToRouterFunction` and `bindToServer`.
- `expectStatus`, `expectBody`, `jsonPath`, and AssertJ `RestTestClientResponse`.
- Boot 4.0 supports it ("Support for the newly introduced RestTestClient").

**Boot 4 modularisation**

- spring.io blog, 2025-10-28: Boot 4 splits `spring-boot-autoconfigure` into per-technology modules,
  each with a starter and a `-test` starter, with packages `org.springframework.boot.<module>`.
- `spring-boot-starter-web` is renamed `spring-boot-starter-webmvc`.
- `spring-boot-starter-classic` and `spring-boot-starter-test-classic` exist as a migration step.
  The migration guide recommends migrating away from them eventually.
- Undertow was removed.
- Jackson 2 remains only in a deprecated form.

**Virtual threads** (Boot reference, spring-application.html, 4.1.1)

- They need Java 21+, and "Java 24 or later is strongly recommended". Pool properties have no effect.
  Virtual threads are daemon threads, so `spring.main.keep-alive=true` is recommended when the JVM
  would otherwise have only daemon threads.
- Boot 4.1.1's `TomcatWebServer` starts a non-daemon `container-N` await thread (source lines
  211–221).
- In the Basetool, all three apps set `spring.threads.virtual.enabled: true`
  (`backend/src/main/resources/application.yml:28-30`, `frontend/…/application.yml:38-40`,
  `ingest/…/application.yml:16-18`) and none sets `keep-alive`. That is correct, because Tomcat keeps
  the JVM alive.

**Implications for the Basetool**

- Prefer HTTP interfaces built over the existing `webClient` bean (RES-05).
- API versioning can replace the custom `DeprecationInterceptor`, which emits `Deprecation: true`
  (`DeprecationInterceptor.java:78`). RFC 9745 defines the value as a structured-field date such as
  `@1688169599` and does not define `true` (RES-06).
- `@ConcurrencyLimit` is the missing bulkhead primitive under virtual threads (RES-07).
- `InetAddressFilter` suits the outbound integration clients (RES-18).

### Q6 — Spring Security 7 method security

Docs are version 7.1.1 (method-security.html); source is at tag 7.1.1.

| Topic | Fact |
| --- | --- |
| Meta-annotations | `@PreAuthorize` works as a meta-annotation, for example `@IsAdmin`. **Templated** meta-annotations need an `AnnotationTemplateExpressionDefaults` bean; `{value}`-style placeholders are substituted, e.g. `@PreAuthorize("hasRole('{value}')") @interface HasRole`. |
| Custom logic | A bean in SpEL (`@PreAuthorize("@authz.decide(#root)")`) may return `Boolean`, an `AuthorizationDecision`/`AuthorizationResult`, or an `AuthorizationManager`. A custom `AuthorizationManager<MethodInvocation>` bean can replace an annotation's manager. |
| `@HandleAuthorizationDenied(handlerClass=…)` | A `MethodAuthorizationDeniedHandler` returns a fallback value, e.g. `null` or a masked value, instead of throwing. It applies to `@PreAuthorize` and `@PostAuthorize` denials and to `AuthorizationDeniedException` thrown by the method. It pairs with `@AuthorizeReturnObject` for field-level protection of returned objects. |
| Inheritance | Method-level annotations override class-level ones. Interface annotations are inherited. If the same annotation is inherited from two interfaces, **startup fails** with `AnnotationConfigurationException`. |
| **Unknown bean in SpEL** | The docs say nothing. The source shows: `BeanFactoryResolver.resolve` wraps `BeansException` in `AccessException` (spring-context, v7.0.9). SpEL `BeanReference` wraps that in `SpelEvaluationException(EXCEPTION_DURING_BEAN_RESOLUTION)`. `authorization.method.ExpressionUtils.evaluate` (spring-security 7.1.1) catches `EvaluationException` and throws **`IllegalArgumentException("Failed to evaluate expression '…'")`**, unless the cause chain holds an `AuthorizationDeniedException`. This happens **on each invocation**. The method body does not run, so the check fails closed, and nothing is detected at startup. The HTTP status depends on the app's exception handling: **the Basetool maps `IllegalArgumentException` to 400 `invalid-argument`** (`GlobalExceptionHandler.java:594-604`), and a default Boot app would answer 500. |

**Implications for the Basetool**

- The refactor will rename or move services that SpEL references, such as `@ownerScopeService` and
  `@exchangeGate` (the latter is in the ArchitectureTest rules at `ArchitectureTest.java:483` and
  `:1073`).
- A move alone keeps the default bean name, which is the decapitalised simple name. A rename breaks
  endpoints into 400s. A duplicate simple name across two new packages fails at startup with a bean
  definition conflict, which is the benign case.
- Add the guard in RES-08.
- Templated meta-annotations such as `@MissionGate("read")` can replace duplicated SpEL strings.

### Q7 — OpenRewrite

**Distribution and versions**

- The **Gradle plugin, the core libraries and the recipe modules are "distributed through the Code
  Genome Project"**, an authenticated Maven repository at `artifacts.codegenomeproject.org` that
  needs a user name and a download token (docs.openrewrite.org gradle-plugin-configuration, raw
  text).
- The Gradle Plugin Portal's newest `org.openrewrite.rewrite` is **7.41.0** (2026-08-26). GitHub
  shows **7.42.0** (2026-09-09) and **7.43.0** (2026-09-23), which are therefore not on the Portal.
- Maven Central's newest `rewrite-java` and **`rewrite-java-25`** are **8.90.4** (2026-08-24),
  which gives Java 25 parser support. The docs reference `rewrite-java:8.92.11`.
- `rewrite-recipe-bom` 3.38.0 is on Central.

**Recipes**

| Recipe | Licence | Notes |
| --- | --- | --- |
| `org.openrewrite.java.ChangePackage(oldPackageName, newPackageName, recursive)` | Apache 2.0 | Covers the package statement, imports and fully-qualified types. **Source (main):** it also accepts any `SourceFileWithReferences`. |
| `org.openrewrite.java.ChangeType(old, new, ignoreDefinition)` | Apache 2.0 | Same reference handling as `ChangePackage`. |
| Which files carry references (source) | — | `YamlApplicationConfigReference` covers **values** in `application(-x).yml/.yaml` that look like fully-qualified names. `PropertiesReference` covers values in `application(-x).properties`. `SpringXmlReference` covers `class`/`type` attributes in files that use the Spring beans schema. `ServiceProviderReference` covers `META-INF/services/*`, content and file name. **Not covered:** YAML/properties *keys*, Java string literals (there is a separate `ChangePackageInStringLiteral`), SpEL, Thymeleaf templates, logback XML, and persisted type ids. |
| `ShortenFullyQualifiedTypeReferences` | Apache 2.0 | Relevant to Sept-audit BE-SIMP-10 (about 970 inline fully-qualified names). |
| `UpgradeToJava25` (newest; the pages for 26, 27 and 29 return 404) | **Moderne Source Available License** | Composes `UpgradeToJava21`, build and plugin upgrades, `ReplaceSystemOutWithIOPrint`, and **`DanglingDocCommentToBlockComment`**, which *adds* block comments and conflicts with ADR-0214. Recipe artifact `rewrite-migrate-java:3.45.0`. |
| `UpgradeSpringBoot_4_0` (Community Edition) | **MSAL** | Composes Boot 3.5, Framework 7, Security 7, Hibernate 7.1, Testcontainers 2, SpringDoc 3, the modular starters and more. There is no 4.1 page, but the navigation lists "Migrate Spring Boot properties to 4.1 (Community Edition)". Artifact `rewrite-spring:6.40.0`. |
| `JUnit5to6Migration` | **MSAL** | Artifact `rewrite-testing-frameworks:3.47.0`. |

The MSAL pages read: "Moderne customers can download precompiled artifacts … For non-commercial use
you can build the artifact from source locally."

**Implications for the Basetool**

- The project is already on Java 25, Boot 4.1.1 and JUnit 6.1.3, so the migration recipes are moot.
- The Apache-2.0 core recipes help with the package moves, but only for Java and the listed file
  types. Every string-bound name (RES-17) needs its own check.
- Adding the plugin to the committed build would need a credentialed repository in
  `settings.gradle.kts` (which uses `FAIL_ON_PROJECT_REPOS`), a CI secret and
  verification-metadata entries. The alternative is a one-off init script with the last public
  versions: plugin 7.41.0 and rewrite-java 8.90.4 (RES-09).

### Q8 — Error Prone and NullAway

| Item | Fact |
| --- | --- |
| Error Prone | Latest **2.50.0** (2026-06-10). "Error Prone must be run on JDK 21 or newer" (installation docs). Minimum JDK 21 since 2.43.0. 2.45.0 "improved compatibility with latest JDK 26 EA". 2.46.0 requires `-XDaddTypeAnnotationsToSymbol=true` on JDK 21. It needs `-XDcompilePolicy=simple`, `--should-stop=ifError=FLOW`, and `--add-exports`/`--add-opens` for `jdk.compiler/com.sun.tools.javac.*` (10 flags). |
| NullAway | Latest **0.14.2** (2026-09-25). 0.14.0 (2026-08-21) removed `LegacyAnnotationLocations` and added more JSpecify support behind `JSpecifyExperimental`. |
| JSpecify mode | `-XepOpt:NullAway:JSpecifyMode=true`. Full support needs **JDK 22+**, or 17.0.19+ / 21.0.8+ with `-XDaddTypeAnnotationsToSymbol=true`. It adds generics, arrays, wildcards and type-use placement. It is "still under development" and may raise false positives. Known gaps: full generic method inference and checking generic class implementations. Wiki last edited 2026-09-05. |
| Annotations | Any `@Nullable` by simple name, so JetBrains' annotation counts. `@NotNull`/`@NonNull` by simple name mark explicit non-null. Non-null is assumed in annotated code, via `AnnotatedPackages` or `@NullMarked`. JetBrains `@Contract` is partly supported and trusted unless `CheckContracts` is set. |
| Gradle plugins | `net.ltgt.errorprone` **5.1.1** (2026-08-25) and `net.ltgt.nullaway` **3.2.0** (2026-08-25). |
| Spring's own use | spring-framework v7.0.9 applies `io.spring.nullability` 0.0.14 (`build.gradle` plugins block) to every module (`gradle/spring-module.gradle:9`). The plugin configures Error Prone and NullAway and defaults to **`requireExplicitNullMarking` enabled**. Latest is **0.0.16** (2026-09-01). The spring.io blog of 2025-11-12 (Sébastien Deleuze) lists Framework 7, Boot 4, Data 4, Security 7, Modulith 2.0 and others as null-safe with JSpecify. |

**Implications for the Basetool**

- A compile-time-only gate with no runtime or image change. It fits ADR-0192's JetBrains
  annotations if NullAway runs with `AnnotatedPackages=de.greluc.krt.profit.basetool` instead of
  requiring `@NullMarked` (RES-10).

### Q9 — Gradle 9.x

| Item | Fact |
| --- | --- |
| Current | **9.8.0** (2026-09-24), which the project already uses (`gradle/wrapper/gradle-wrapper.properties:4`). 9.7.1 came out on 2026-08-19. |
| Configuration cache | "Since Gradle 9.0.0, the Configuration Cache is the preferred mode of execution". It "will be enabled by default in Gradle 10". |
| Isolated Projects | **Incubating since 9.7.0**, promoted from experimental. The property is `org.gradle.isolated-projects=true`; the `.unsafe` name is deprecated. It forbids reading or mutating other projects' state, and **`allprojects {}` and `subprojects {}` are incompatible**. Replacements: convention plugins, and `gradle.lifecycle.beforeProject` in settings. |
| Build logic | "The preferred location for build logic is an included build (typically named build-logic), not in buildSrc". The docs also say "Use Convention Plugins for Common Build Logic" and warn that `allprojects`/`subprojects` also configure empty projects. |

**Implications for the Basetool**

- The root build uses `allprojects {` (`build.gradle.kts:14`) and `subprojects {` (`:122`). The
  frontend reads `rootProject.extra[...]` (`frontend/build.gradle.kts:169-172`).
- Option B or C multiplies the subprojects, and with them this cross-project configuration.
- Move to `build-logic` convention plugins first (RES-11).

### Q10 — Hibernate ORM 7.x and Spring Data JPA 4.x

**Versions**

- Boot 4.1.1 manages **Hibernate 7.4.5.Final**. The latest 7.4 patch is **7.4.11.Final**
  (2026-09-27, hibernate.org/orm/releases/7.4).
- 7.4 supports Java 17, 21, 25 and 26, Jakarta Persistence 3.2 and Jakarta Data 1.0.

**Hibernate features**

| Feature | Fact |
| --- | --- |
| 7.4: `@Temporal` and `@Audited` (incubating StateManagement SPI) | Temporal tables can be native, one table with effectivity columns, or two tables. `@Audited` keeps an audit-log table that is backward compatible with Envers, optionally with a `@Changelog` entity, and can be queried through `AuditLog` (What's New 7.4, docs version 7.4.11). |
| 7.4: limits with fetch joins | "now perfectly safe to combine" `setMaxResults()` or pagination with a collection fetch join, via subqueries. In-memory limiting is gone; the hint `org.hibernate.limitInMemory` restores it. |
| 7.0 baseline | Java 17 and Jakarta Persistence 3.2 ("fairly disruptive"). `@SoftDelete` gains a TIMESTAMP strategy. `Session.findMultiple()` and `StatelessSession.getMultiple()`. `QuerySpecification` and `Restriction`. StatelessSession gets `insertMultiple`/`updateMultiple`/`deleteMultiple` (incubating), uses the second-level cache by default, and changes JDBC batching. |
| Jakarta Data (Hibernate Data Repositories) | Repositories are backed by a **`StatelessSession`**: no persistence context, no dirty checking, no cascades, lazy loading only through explicit `fetch()`. Queries are validated **at compile time** by `HibernateProcessor` (`hibernate-processor`, formerly `hibernate-jpamodelgen`), including `@Query`, `@Find` and `@OrderBy`. With Spring on the classpath it generates `@Component` classes that use `ObjectProvider<StatelessSession>`. |
| UUID generation | `@UuidGenerator(style = VERSION_7)` is available; the styles are AUTO/RANDOM/TIME/VERSION_6/VERSION_7 (Javadoc 7.4). |

**Spring Data JPA 4.x**

- **Spring Data 2025.1 (JPA 4.0):**
  - AOT repositories, on by default;
  - JSpecify;
  - `PredicateSpecification`, `UpdateSpecification` and `DeleteSpecification`;
  - derived queries now use string JPQL instead of Criteria;
  - a JPA 3.2 and Hibernate 7.1/7.2 baseline;
  - removal of `ListenableFuture`, relocation of `PropertyPath` and `TypeInformation`, and
    `QueryEnhancerSelector`.
- **Spring Data 2026.0 (JPA 4.1):**
  - type-safe property paths such as `PropertyPath.of(Person::getName)` and
    `Sort.by(Person::getFirstName, …)`;
  - `@ProjectedPayload` becomes mandatory for projection parameters, enforced from 2026.1.

**Implications for the Basetool**

- `@Audited` is incubating and snapshots whole rows. That can include free text and personal data,
  and it is not the explicit REQ-AUDIT-001 event model, so it should be rejected for audit.
- Jakarta Data's stateless repositories bypass the `@Version` and dirty-checking patterns. The
  backend has 70 `@Version` lines in main code (Appendix B-7), so they are not for existing
  aggregates.
- 7.4's SQL-side fetch-join pagination affects REQ-DATA-003. The backend sets
  `hibernate.query.fail_on_pagination_over_collection_fetch: true` (`application.yml:72`), and
  `PagedFindersNoCollectionFetchTest` guards it. Whether 7.4.5 still throws under that flag is
  UNKNOWN (RES-12).

### Q11 — PostgreSQL 18

- Released 2025-09-25. The current minor is **18.6**, with final release on **2030-11-14**.
- PostgreSQL 19 is at Beta 4 (2026-09-24) (postgresql.org/support/versioning).

| Feature | Fact (release notes and 18 docs) |
| --- | --- |
| Asynchronous I/O | `io_method` (`worker`, `io_uring`, `sync`) with `io_combine_limit` and `io_max_combine_limit`. It helps sequential scans, bitmap heap scans and vacuum. |
| B-tree skip scan | Multicolumn B-tree indexes become usable without restrictions on the leading column(s). |
| `uuidv7([shift interval])` | Timestamp-ordered UUIDs, with the new alias `uuidv4()`. `uuid_extract_timestamp()` and `uuid_extract_version()`. The docs say nothing about monotonicity within a session. |
| Virtual generated columns | Now the **default** kind, computed on read; `STORED` stays available. They must not use user-defined functions or types. Logical replication supports only stored ones. Index support for virtual columns: **UNKNOWN** — the 18 docs pages for generated columns and `CREATE INDEX` do not say. |
| `RETURNING OLD/NEW` | Available in INSERT, UPDATE, DELETE and MERGE; the aliases can be renamed. |
| Temporal constraints | `PRIMARY KEY`/`UNIQUE (…, valid_at WITHOUT OVERLAPS)` backed by a GiST index. The last column must be a range or multirange; scalar columns need `btree_gist`. `FOREIGN KEY (…, PERIOD valid_at)` requires the referenced key to use `WITHOUT OVERLAPS`. |
| Constraints | `NOT NULL … NOT VALID` through `ALTER TABLE`. `NOT ENFORCED` for CHECK and foreign-key constraints. |
| Other | OAuth authentication. Data checksums on by default in initdb. `pg_upgrade` keeps planner statistics. **MD5 password authentication is deprecated** in favour of SCRAM. |

**Implications for the Basetool**

- The backend generates random v4 ids: 104 `GenerationType.UUID` lines and 44 `gen_random_uuid`
  lines in migrations (Appendix B-8).
- v7 ids improve index locality but **encode the creation time**, which becomes readable from ids in
  URLs. That is a privacy consideration (RES-13).
- `RETURNING OLD/NEW` fits bulk version bumps that also need the before value for audit.

### Q12 — Web platform Baseline (web-features **3.40.0**, npm, published 2026-09-24)

| Feature (web-features id) | Status | Newly available | Widely available |
| --- | --- | --- | --- |
| ES2023 array by copy — `toSorted`/`toReversed`/`toSpliced`/`with` (`array-by-copy`) | **Widely** | 2023-07-04 | 2026-01-04 |
| `findLast`/`findLastIndex` (`array-findlast`) | **Widely** | 2022-08-23 | 2025-02-23 |
| `Object.groupBy`/`Map.groupBy` (`array-group`) | **Widely** | 2024-03-05 | 2026-09-05 |
| `Promise.withResolvers` (`promise-withresolvers`) | **Widely** | 2024-03-05 | 2026-09-05 |
| `Array.fromAsync` (`array-fromasync`) | **Widely** | 2024-01-25 | 2026-07-25 |
| Set methods (`set-methods`) | Newly | 2024-06-11 | (projected 2026-12-11) |
| Iterator helpers (`iterator-methods`) | Newly | 2025-03-31 | (projected 2027-09-30) |
| CSS nesting (`nesting`) | **Widely** | 2023-12-11 | 2026-06-11 |
| Container size queries (`container-queries`) | **Widely** | 2023-02-14 | 2025-08-14 |
| Container style queries (`container-style-queries`) | Newly | 2026-05-19 | — |
| `:has()` (`has`) | **Widely** | 2023-12-19 | 2026-06-19 |
| `@scope` (`scope`) | Newly | 2026-03-24 | — |
| `color-mix()` (`color-mix`) | **Widely** | 2023-05-09 | 2025-11-09 |
| Same-document view transitions (`view-transitions`) | Newly | 2025-10-14 | — |
| Cross-document view transitions | **Not Baseline** (no Firefox) | — | — |
| Trusted Types (`trusted-types`) | Newly | 2026-02-24 | — |
| `<dialog>` (`dialog`) | **Widely** | 2022-03-14 | 2024-09-14 |
| `<dialog closedby>` | **Not Baseline** (no Safari) | — | — |
| Popover (`popover`) | Newly | 2025-01-27 | (projected 2027-07-27) |
| `Promise.try`, `RegExp.escape`, `Float16Array` | Newly | 2025-01-07 / 2025-05-01 / 2025-04-04 | — |

"Projected" dates add 30 months to the newly-available date. That gap matches the dataset's own
pairs (for example 2023-07-04 → 2026-01-04). The package README does not state the rule.

**Implications for the Basetool**

- `frontend/tsconfig.json:24-29` pins `target`/`lib` to **ES2023**, and ESLint uses
  `ecmaVersion: 2023` (`eslint.config.mjs:26,78`). `Object.groupBy` and `Promise.withResolvers`
  (ES2024) are widely available but will not type-check until `lib` is raised.
- Whether the TypeScript 7 in use accepts `ES2024`/`ES2025` lib names is UNKNOWN. Settle it with
  `:frontend:typecheckJs` after the bump.
- Trusted Types is newly available and would harden against DOM XSS through CSP
  `require-trusted-types-for 'script'`. That is a large change across roughly 100 JS files; start
  report-only (RES-14).

### Q13 — JPMS with Spring Boot 4

- **No `module-info.class`** is in `spring-core-7.0.9.jar`, `spring-boot-4.1.1.jar` or
  `spring-modulith-core-2.1.1.jar`. They carry only `Automatic-Module-Name` (`spring.core`,
  `spring.boot`, `spring.modulith.core`), checked by unzipping the Maven Central jars.
- The Framework 7.0.9 reference:
  - `overview.adoc:38-43`: the jars "allow for deployment to the module path" as automatic modules
    with stable names, and "work fine on the classpath".
  - `core/aop/proxying.adoc:26-29`: CGLIB proxies have module-system limitations.
  - `core/beans/classpath-scanning.adoc:319-323`: components must be exported, and non-public
    members `opens`.
- spring-framework **#18079** "Declare Spring modules with JDK 9 module metadata" has been **open
  since 2015-09-24** (General Backlog). #32671 (closed 2024-05-08) documented the CGLIB limitation;
  Jürgen Höller called it a consequence of the module system's design.
- The Boot reference sources have no module-path guidance (code search: 0 hits). Boot issues on JPMS
  were closed as declined, invalid or external. An official Boot support statement is **UNKNOWN**;
  none was found.

**Implications for the Basetool**

- JPMS is not a practical way to enforce domain boundaries, even with the JDK 25 module-import
  syntax, which ADR-0223 rejects anyway.
- Use ArchUnit, Spring Modulith or Gradle modules (RES-15).

---

## 3. Findings

### RES-01 — ADR-0223's "no final library feature" sentence is wrong; the JDK 25 list omits JEP 510

- **Evidence.**
  - ADR-0223 lines 63–64 against JEP 517: final in 26, component core-libs/java.net.http, adds
    `HttpClient.Version.HTTP_3`, `HttpOption.H3_DISCOVERY` and `Http3DiscoveryMode`
    (openjdk.org/jeps/517).
  - JEP 504 removes `java.applet`.
  - JDK 25 has JEP 510 final and JEP 470 preview (openjdk.org/projects/jdk/25).
  - The tooling line names Checkstyle 14.1.0 while the pin is 14.3.0
    (`gradle/libs.versions.toml:45`, commit `ba80b2a7d`).
- **Impact.** Documentation accuracy only. The decision — no preview flags, stay on the LTS — is
  unaffected, because no *language* feature became final in 26 or 27, and HTTP/3 is opt-in and
  unused.
- **Proposed change.**
  - Reword to "no final language feature; the only library additions are JEP 517 (opt-in HTTP/3)
    and the removal of the Applet API".
  - Add JEP 510 (final) and JEP 470 (preview) to the JDK 25 table.
  - Add JEP 500 to the list of runtime changes the next toolchain move re-checks.
  - Re-run the tooling sample on Checkstyle 14.3.0.
- **Pros.** The ADR stays authoritative, and the vault copy stays true.
- **Cons.** None.
- **Risks, security and guard.** No code change and no security impact. The only guard is review.
- **Effort.** S.
- **Prerequisites.** Edit the ADR and the corresponding vault decision note, dated.

### RES-02 — Stay on JDK 25 until 29; prepare for JEP 500 before any JDK 26+ trial

- **Evidence.**
  - JDK 27 GA was 2026-09-15; it is non-LTS, with Premier Support until March 2027.
  - The next LTS is Java 29 in September 2027 (Oracle roadmap, `70-research-oracle-roadmap.html:658,763-767`).
  - Boot 4.1.1 supports Java up to 26.
  - JEP 500 defaults to `warn` in 26, and deny "will become the default in a future release".
  - Tests have 37 `ReflectionTestUtils.setField` lines (Appendix B-6).
- **Impact.** No modern *language* feature is lost by staying on 25. The final features
  available now are 456, 467, 485, 506, 510, 511, 512 and 513.
- **Proposed change.**
  1. Keep the toolchain on 25.
  2. When a JDK 26+ or 29 trial starts, run `:backend:test`, `:frontend:test` and `:ingest:test`
     with `--illegal-final-field-mutation=deny` on the test JVMs. Replace each failing reflective
     final-field write with constructor injection or a package-private setter.
  3. At the move to 29, re-evaluate JEP 544 (AOT code compilation) for the ADR-0209 image training.
- **Pros.**
  - An LTS with support until 2030 or 2033.
  - The JEP 500 exposure is found early, and only in tests.
- **Cons.** HTTP/3 and G1-everywhere wait, and the Basetool needs neither.
- **Risks, security and guard.**
  - Security: none. JEP 500 *strengthens* integrity, and the Basetool's main code has no
    `setAccessible(true)` (Appendix B-6).
  - Guard: the deny-mode test run above.
- **Effort.** S (checking) plus S–M (fixing the failures, if any).
- **Prerequisites.** None now. At the move, amend ADR-0223 and bump the Boot line.

### RES-03 — Adopt Spring Modulith 2.1.1 for verification in test scope first

- **Evidence.**
  - Modulith 2.1.1 is on Boot 4.1.1 and Framework 7.0.9 (GitHub release, 2026-08-26).
  - Detection strategies, nested modules, `@ApplicationModule(id, displayName, allowedDependencies, type)`,
    `@NamedInterface(propagate)` and `verify()` rules (docs 2.1.1, annotation source at 2.1.1).
  - `spring-modulith-starter-test` pulls `-docs`, `-test` and `-core`, which brings ArchUnit
    1.4.2 in test scope only (Appendix B-9).
- **Impact.** The core enforcement tool for option A:
  - `verify()` rejects module cycles and access to internal types.
  - `@ApplicationModuleTest(STANDALONE)` shows how coupled a domain is by the mocks it needs.
  - `Documenter` generates C4 and Canvas documentation for arc42 §5.
- **Proposed change.**
  1. Add `spring-modulith-starter-test` to the backend and frontend test classpath.
  2. Set `spring.modulith.detection-strategy=explicitly-annotated`, so the layer packages are not
     treated as modules.
  3. Annotate each domain package as it is extracted:
     `@ApplicationModule(allowedDependencies = "catalogue, orgunit :: api")`.
  4. Add a `ModularityTest` that calls `ApplicationModules.of(BackendApplication.class).verify()`.
  5. Generate the Documenter output in CI as an artifact.
  6. Keep ArchitectureTest's security rules; Modulith does not replace them.
- **Pros.**
  - Zero runtime footprint.
  - Incremental adoption.
  - Maintained by the Spring team on the Boot release train.
  - Generated documentation.
- **Cons.**
  - A second architecture-testing vocabulary next to 38+ ArchUnit rules.
  - The dependency is compiled against ArchUnit 1.4.2 while the project pins 1.5.1; binary
    compatibility is UNKNOWN until `verify()` runs.
  - Verification-metadata must be regenerated (ADR-0208).
- **Risks, security and guard.**
  - Test-only, so no runtime surface.
  - An OPEN module or a loose `allowedDependencies` can hide a boundary break. Keep OPEN only for
    legacy layer packages, and fail the build if a *domain* module is declared OPEN (an ArchUnit
    rule over `package-info`).
  - It does not check authorization. Keep `ArchitectureTest`'s `@PreAuthorize`, OwnerScope and
    exchange-gate rules.
  - Guard: `ModularityTest`, and `./gradlew help --configuration-cache` run twice.
- **Effort.** S (setup) plus ongoing per-domain work.
- **Prerequisites.** A new ADR (module-verification tooling); arc42 §8 and §5; the vault Backend
  note.

### RES-04 — Modulith's event registry is usable for cross-domain reactions, but only with six safeguards

- **Evidence.**
  - `@ApplicationModuleListener` = `@Async` + `@Transactional(REQUIRES_NEW)` +
    `@TransactionalEventListener` (source at 2.1.1).
  - Registry entries are written in the publishing transaction, with at-least-once delivery (events
    docs 2.1.1).
  - The PostgreSQL schema has `event_type`, `serialized_event TEXT`, `status` and more (source,
    `schemas/v2/schema-postgresql.sql` at 2.1.1).
  - JDBC schema creation is on by default (2.1.0 notes; appendix property default `true`).
  - `spring-modulith-starter-jpa` pulls `spring-modulith-core` and ArchUnit 1.4.2 into runtime
    (Appendix B-9).
  - The backend currently has 19 `ApplicationEventPublisher` lines, 4
    `@TransactionalEventListener` and 16 `@Async` (Appendix B-7).
- **Impact.** Option A's "domain events after commit" needs a reliable outbox, and
  `@TransactionalEventListener` + `@Async` alone lose events on a crash. The registry provides the
  outbox.
- **Proposed change** — adopt it only with all six safeguards:
  1. Depend on **`spring-modulith-events-jdbc` + `spring-modulith-events-jackson`**, not on a
     starter. This keeps ArchUnit and the APT jar out of the image.
  2. Set `spring.modulith.events.jdbc.schema-initialization.enabled=false`. Create
     `event_publication`, and `event_publication_archive` if the archive mode is used, **by a
     Flyway migration** copied from the v2 PostgreSQL DDL (REQ-DATA: Flyway owns the schema,
     `ddl-auto: validate` at `application.yml:76`).
  3. Event records carry **ids and enums only**: no user free text and no personal data. The
     payload is stored in plain text and would otherwise escape the GDPR deletion flow. Use
     `completion-mode=delete` or `archive` with a retention job.
  4. Keep event classes in a stable `…<domain>.api.event` package, because `event_type` stores the
     class name. On read, `JdbcEventPublicationRepository` (2.1.1) resolves it with
     `ClassUtils.forName`. On `ClassNotFoundException` it logs a WARN, "Event '{}' of unknown type
     '{}' found", and **drops the publication**. So a later move silently loses every incomplete
     event of the moved type, unless stored rows are migrated first.
  5. Make every listener idempotent: at-least-once delivery plus `republish-outstanding-events-on-restart`.
  6. **Never move `auditService.record(...)` into an `@ApplicationModuleListener`.** It runs after
     commit in a new transaction, and REQ-AUDIT-001 requires the audit event in the mutating
     transaction. Audit stays synchronous.
- **Pros.**
  - Reliable after-commit reactions, such as notifications or live sync triggered by another
    domain's change.
  - Failed and stale publications become visible and can be resubmitted.
- **Cons.**
  - A new table and a new write per event and listener.
  - Operational duties: purge or retention, and monitoring of incomplete publications.
  - Modulith's metric names do not follow `basetool_*` (REQ-OBS-011).
- **Risks, security and guard.**
  - *Audit completeness:* violated if safeguard 6 is ignored. Guard with an ArchUnit rule that no
    method annotated `@ApplicationModuleListener` or `@TransactionalEventListener` calls
    `AuditService.record`.
  - *Personal data at rest:* guard with a test that reflects over the event records and rejects
    `String` fields outside an allow-list.
  - *Tenancy:* a listener runs without the request's `SecurityContext` or org-unit MDC, so it must
    scope by ids carried in the event and never through `OwnerScopeService`'s request-bound
    helpers. Guard with a rule forbidding `AuthHelperService` in listener classes.
  - *Deserialisation:* Jackson 3 rehydrates into the stored class name. Keep event classes as
    records without polymorphic fields.
  - *Monitoring:* add a `basetool_event_publications_incomplete` gauge and an alert
    (REQ-OBS-005…011).
- **Effort.** M for the infrastructure, then per-event work.
- **Prerequisites.** RES-03; a new ADR; REQ-DATA and REQ-AUDIT-001 wording ("same transaction");
  REQ-OBS additions; a Data Protection note in the vault.

### RES-05 — Frontend per-domain clients as `@HttpExchange` proxies over the existing `webClient` bean

- **Evidence.**
  - The `webClient` bean chains the OAuth2 relay, correlation, active-squadron, locale, client-IP,
    logging and Resilience4j filters (`WebClientConfig.java:456-489`).
  - `@ImportHttpServices` groups get their own builders; Boot 4.1.1 applies only
    `spring.http.serviceclient.*` properties and `WebClientCustomizer` beans to them (source:
    `WebClientCustomizerHttpServiceGroupConfigurer`, `ReactiveHttpServiceClientAutoConfiguration`
    at v4.1.1).
  - The frontend defines no `WebClientCustomizer` (grep, Appendix B-11).
  - Sept-audit FE-SIMP-02 already recommended "typisierte `@HttpExchange`-Interfaces über den
    vorhandenen WebClient".
- **Impact.** Per-domain typed clients (`MissionBackendApi`, `BankBackendApi`, …) give the frontend
  compile-checked URIs and parameter encoding, which removes the 290 concatenated URIs of
  FE-SIMP-02. They also give each domain a small frontend-side API.
- **Proposed change.**
  - In one `BackendClientsConfig`, create each proxy as
    `HttpServiceProxyFactory.builderFor(WebClientAdapter.create(webClient)).build().createClient(X.class)`,
    reusing the one `webClient` bean.
  - If `@ImportHttpServices` groups are wanted, register one `WebClientHttpServiceGroupConfigurer`
    with the **highest precedence** that uses
    `groups.forEachClient((InitializingClientCallback<WebClient.Builder>) g -> webClient.mutate())`.
    That is Framework 7.0.9's "Callback to supply the client builder". Because `mutate()` copies the
    filter list, every group keeps the identical chain (§5 (n)).
  - Keep `BackendApiClient` as the façade for caching (`CachedCatalog`) and error translation
    during the transition.
  - **Never** put `@Cacheable` on the proxy interfaces. The default key is the method arguments,
    while the response depends on the implicit bearer token and `X-Active-Org-Unit-Id` (§5 (k)).
- **Pros.**
  - Exactly one filter chain, so ADR-0032's single resilience pass holds.
  - Encoding is correct by construction.
  - The domain split carries over to the frontend.
- **Cons.**
  - It changes the "single seam" statement of ADR-0032 and arc42 §4.1. The July audit rejected
    splitting `BackendApiClient`, so the ADR must be amended.
  - Declarative interfaces hide per-call options such as `getCached`.
- **Risks, security and guard.**
  - A proxy built on the wrong `WebClient` bean would silently drop:
    - the OAuth2 bearer relay (`sseWebClient` and `liveSyncAuthWebClient` deliberately have none,
      `WebClientConfig.java:547-579`), giving 401s or worse, unauthenticated behaviour;
    - `X-Active-Org-Unit-Id`, which changes tenancy scope;
    - the resilience chain, which violates ADR-0032.
  - Guards:
    - a MockWebServer test per client interface asserting `Authorization`, `X-Correlation-Id`,
      `X-Active-Org-Unit-Id` and the circuit-breaker name;
    - an ArchUnit rule that `HttpServiceProxyFactory` is used only in `BackendClientsConfig`;
    - an ArchUnit rule that `@ImportHttpServices` appears only next to the initializing configurer;
    - an ArchUnit rule that no `@HttpExchange` interface carries `@Cacheable`.
- **Effort.** M, done incrementally per domain.
- **Prerequisites.** Amend ADR-0032 and arc42 §4.1 ("single filter chain" instead of "single
  class"); the vault Frontend note; FE-SIMP-02.

### RES-06 — Spring 7 API versioning with RFC 9745 deprecation headers instead of the custom interceptor

- **Evidence.**
  - `DeprecationInterceptor.java:78,84,96` emits `Deprecation: true`, `Sunset` and
    `Link …; rel="alternate"`.
  - RFC 9745 (Standards Track, March 2025) defines `Deprecation` as a structured-field date
    (`@<epoch>`) and the `deprecation` link relation.
  - Spring 7.0.9's `StandardApiVersionDeprecationHandler` implements RFC 9745 and RFC 8594.
  - Boot 4.0 has `spring.mvc.apiversion.*`.
  - Sept-audit BE-SIMP-02: 17 legacy mission endpoints with sunset 2026-10-20.
- **Impact.** Modern, standard API lifecycle signalling. Path versioning (`/api/v1`) can stay via
  `usePathSegment`.
- **Proposed change.**
  - Configure `ApiVersionConfigurer` with a path segment and supported versions.
  - Replace `@ApiDeprecation` and `DeprecationInterceptor` with the standard deprecation handler.
  - Keep the OpenAPI deprecation output (`OpenApiDeprecationConfig`).
- **Pros.** Standard header semantics; less custom code.
- **Cons.**
  - The header value changes from `true` to a date, which is client-visible to the Android app, the
    SC extractor and VerseKit.
  - `InvalidApiVersionException` returns 400 for unknown versions.
- **Risks, security and guard.**
  - No authorization impact; version resolution happens before handler mapping.
  - Guard: a MockMvc test asserting the RFC 9745 header format on a deprecated endpoint, plus the
    OpenAPI contract tests.
- **Effort.** S–M.
- **Prerequisites.** Check the header consumers; REQ-API wording; an ADR if the header contract
  changes.

### RES-07 — Framework 7 resilience annotations: use `@ConcurrencyLimit` for virtual-thread fan-out; keep retries explicit

- **Evidence.**
  - `@Retryable`, `@ConcurrencyLimit` (blocking) and `@EnableResilientMethods` in Framework 7.0.9.
  - Boot does not enable them (code search: 0 hits).
  - Boot docs: under virtual threads "properties which configure thread pools don't have an effect".
  - The backend has 0 retry abstractions today (Appendix B-7).
- **Impact.** Modern-feature adoption. `@ConcurrencyLimit` bounds imports and outbound fan-out
  (UEX, SC Wiki) that virtual threads no longer bound by pool size.
- **Proposed change.**
  - `@EnableResilientMethods` in the backend.
  - `@ConcurrencyLimit` on the external-integration clients.
  - Keep the documented find-or-create `REQUIRES_NEW` retry explicit: advice ordering between
    `@Retryable` and `@Transactional` is not documented, and a retry *inside* the failed
    transaction would reuse a rollback-only transaction.
- **Pros.** A declarative bulkhead with no new dependency.
- **Cons.** A second resilience vocabulary next to Resilience4j in the frontend (ADR-0032 keeps
  Resilience4j there).
- **Risks, security and guard.**
  - A blocking limit can stall request threads if applied to user-facing paths. Apply it only to
    background or import paths.
  - Guard: a test that the limit is enforced (concurrent invocations counted).
  - Security: positive; it is a DoS bound on outbound calls.
- **Effort.** S.
- **Prerequisites.** backend/CLAUDE.md note; REQ-OBS metric for throttled calls if exposed.

### RES-08 — Guard `@PreAuthorize` SpEL bean references; they fail per call as HTTP 400

- **Evidence.**
  - The call chain `BeanFactoryResolver` → `BeanReference` → spring-security
    `authorization.method.ExpressionUtils.evaluate` throws `IllegalArgumentException` (source,
    v7.0.9 and 7.1.1).
  - `GlobalExceptionHandler.java:594-604` maps it to 400.
  - No test parses or resolves the SpEL bean names: the only `SpelExpressionParser` use is in
    `JacksonRecordTest.java:38` (Appendix B-12).
- **Impact.** The domain refactor will rename and move security beans. A rename would turn gated
  endpoints into 400s that only an end-to-end call reveals.
- **Proposed change.**
  - Add `PreAuthorizeBeanReferenceTest` in backend tests:
    1. Import all classes with ArchUnit's `ClassFileImporter`.
    2. Collect `@PreAuthorize`, `@PostAuthorize` and meta-annotated values.
    3. Parse them with `SpelExpressionParser`.
    4. Walk the AST for `org.springframework.expression.spel.ast.BeanReference` and read
       `getName()`.
    5. Assert each name is the default bean name (the decapitalised simple name) of a
       `@Component`, `@Service` or `@Configuration` class, or an explicit `@Component("…")` value.
  - Optionally introduce templated meta-annotations (`AnnotationTemplateExpressionDefaults`) for the
    recurring domain gates.
- **Pros.**
  - It turns a runtime 400 into a build failure.
  - It needs no Spring context.
  - Templated annotations reduce copies of SpEL strings.
- **Cons.**
  - SpEL built by concatenation cannot be checked. Forbid it: every value must be a compile-time
    constant.
- **Risks, security and guard.**
  - Security: positive. The failure mode is already closed, but the guard prevents availability
    regressions and makes a bean swap visible in review.
  - Templated annotations change the expression text, so keep ArchitectureTest's textual rules
    (`ArchitectureTest.java:1073`, the `@ownerScopeService` gate) working on the *resolved*
    expression, or extend them to read meta-annotations.
- **Effort.** S.
- **Prerequisites.** A REQ-SEC addition (bean-reference integrity); a note in the vault's Request
  Authorization note.

### RES-09 — OpenRewrite for package moves: core recipes only, outside the committed build

- **Evidence.**
  - Code Genome Project distribution with credentials (raw docs text).
  - Plugin Portal ends at 7.41.0 and Central `rewrite-java` at 8.90.4 (Appendix B-3).
  - `ChangePackage`/`ChangeType` are Apache 2.0 and handle `SourceFileWithReferences` (source,
    main).
  - Migration recipes are under MSAL.
  - `UpgradeToJava25` includes `DanglingDocCommentToBlockComment`.
- **Impact.** A mechanical, reviewable rename of hundreds of classes into domain packages.
- **Proposed change.**
  - Run `ChangePackage`/`ChangeType` from a **throwaway Gradle init script** that is not committed.
    Use plugin 7.41.0 and rewrite-java 8.90.4 from the Plugin Portal and Maven Central (no
    credentials), with the declarative recipe list in a local `rewrite.yml`.
  - Afterwards run `spotlessApply` and the full lint gate, and check every string-bound name by
    hand (RES-17).
  - Do not run the MSAL migration recipes, which are also moot for this project.
- **Pros.** Correct import and reference updates, including `application*.yml` and properties
  *values* and `META-INF/services`.
- **Cons.**
  - No YAML keys, string literals, SpEL, Thymeleaf or persisted ids (RES-17).
  - The distribution change means future versions need credentials.
- **Risks, security and guard.**
  - A committed plugin plus repository would add a credentialed repository to
    `settings.gradle.kts` (which uses `FAIL_ON_PROJECT_REPOS`), a CI secret and unverifiable
    artifacts. That affects the supply chain (ADR-0208, REQ-OPS-034), so it must stay local.
  - `DanglingDocCommentToBlockComment` would violate ADR-0214.
  - Guards: the lint gate, Checkstyle, and the RES-17 inventory checks.
- **Effort.** S per move.
- **Prerequisites.** A tooling note in CONTRIBUTING, or an ADR if made standard.

### RES-10 — Error Prone + NullAway as a compile-time null gate with the JetBrains annotations

- **Evidence.**
  - Error Prone 2.50.0 runs on JDK 21 or newer.
  - NullAway 0.14.2; JSpecify mode needs JDK 22+.
  - NullAway recognises any `@Nullable` and `@NotNull` by simple name (wiki).
  - The Spring Framework 7.0.9 build applies `io.spring.nullability` (Error Prone + NullAway),
    whose default requires explicit null marking.
  - ADR-0192 chose JetBrains annotations and rejected `lombok.addNullAnnotations = jspecify` (ADR
    line 225).
- **Impact.** Turns the "`@NotNull` is a lie nothing will catch" note in CLAUDE.md into a checked
  contract. That matters for the domain APIs: public façades need reliable nullness.
- **Proposed change.**
  - Apply `net.ltgt.errorprone` 5.1.1 and `net.ltgt.nullaway` 3.2.0 through the (future)
    `build-logic` convention plugin.
  - NullAway options: `AnnotatedPackages=de.greluc.krt.profit.basetool`, `JSpecifyMode=true` (on
    25) and `CheckContracts`.
  - Start with the new domain API packages. Scope it per source set, and keep the Error Prone
    checks limited to NullAway at first.
- **Pros.** Build-time NullPointerException prevention, aligned with the Spring 7 JSpecify APIs.
- **Cons.**
  - Ten `--add-exports`/`--add-opens` javac flags.
  - Compile time rises.
  - Lombok-generated code needs NullAway's Lombok handling. Whether it works with the project's
    `lombok.addNullAnnotations = jetbrains` is UNKNOWN; settle it with a trial on one module.
  - Configuration-cache compatibility of the plugins is UNKNOWN; settle it with
    `./gradlew help --configuration-cache` run twice.
- **Risks, security and guard.**
  - Compile-time only, so no runtime or image change and no security weakening.
  - Suppressions via `@SuppressWarnings("NullAway")` must follow the "no suppression without
    reason" rule, with the reason in the commit or PR.
  - Guard: CI `compileJava` failing on NullAway errors.
- **Effort.** M.
- **Prerequisites.** An ADR (and possibly an ADR-0192 amendment); regenerate verification-metadata
  (ADR-0208); RES-11 helps.

### RES-11 — Move root-build cross-project configuration into `build-logic` convention plugins before any Gradle split

- **Evidence.**
  - `allprojects {` at `build.gradle.kts:14` and `subprojects {` at `:122`.
  - **22** `rootProject.(file|fileTree|layout|extra)` accesses in subproject scripts (backend 7,
    frontend 13, test-support 2), including `rootProject.extra[...]` at
    `frontend/build.gradle.kts:169-172`.
  - Gradle 9.8.0 names `ext`, `extensions` and `layout` of another project as mutable state, and
    says to assume every method call on another `Project` is forbidden (§4 (d)).
  - Isolated Projects is incubating and incompatible with `allprojects` and `subprojects`; "The
    preferred location for build logic is an included build … not in buildSrc"; the configuration
    cache becomes the default in Gradle 10.
  - A `build-logic` included build is covered by the root `verification-metadata.xml` (§4 (a)). The
    catalog is reachable in precompiled plugins only through the `VersionCatalogsExtension` string
    API (§4 (b)).
- **Impact.** It is a prerequisite for options B and C. Each new Gradle subproject per domain would
  otherwise inherit the root's cross-project configuration. It also unlocks parallel configuration.
- **Proposed change.**
  - Create `build-logic/` (an included build) with convention plugins `basetool.java-conventions`,
    `basetool.spring-app`, `basetool.quality` and `basetool.sbom`.
  - Apply them explicitly in each module.
  - Replace `rootProject.extra` with a shared build service or a convention-plugin extension.
- **Pros.** Isolated-projects readiness, explicit per-module configuration, and faster IDE sync.
- **Cons.**
  - A large mechanical change to the build.
  - The build-script rules in CLAUDE.md (the `subprojects { plugins.withId(...) }` description)
    must be rewritten.
- **Risks, security and guard.**
  - Any lost configuration silently drops a gate, such as SpotBugs `spotbugsMain`, the Mockito
    agent, the SBOM, OWASP suppressions or the SEC-17 jar check.
  - Guards:
    - diff `./gradlew tasks --all` and `./gradlew build -m --configuration-cache` before and after;
    - `./gradlew help --configuration-cache` run twice;
    - a verification-metadata regeneration dry run showing no new artifacts.
- **Effort.** M.
- **Prerequisites.** An ADR; CONTRIBUTING; the root CLAUDE.md build paragraph.

### RES-12 — Hibernate 7.4: reject `@Audited` for audit; re-check REQ-DATA-003 against SQL-side fetch-join pagination

- **Evidence.**
  - What's New 7.4: `@Audited`/`@Temporal` are incubating, and limits with collection fetch joins
    are "now perfectly safe" via subqueries.
  - Boot manages 7.4.5.Final.
  - `application.yml:72` sets `fail_on_pagination_over_collection_fetch: true`, guarded by
    `PagedFindersNoCollectionFetchTest`.
  - Sept-audit BE-PERF-02 found seven paged queries fetching collections.
  - The backend has 70 `@Version` lines.
- **Impact.**
  - Audit must stay explicit (REQ-AUDIT-001, no free text and no personal data in details).
  - Paging over collection graphs might become acceptable, but also changes semantics.
  - Jakarta Data stateless repositories do not fit the `@Version` and managed-entity concurrency
    rules.
- **Proposed change.**
  1. Record "not adopted" for `@Audited` and Jakarta Data repositories in the ADR set.
  2. Write a characterisation test: a paged finder with a collection fetch under 7.4.5 with the flag
     on. Whether it throws is UNKNOWN until run. Then decide whether REQ-DATA-003 keeps the flag.
  3. Consider `@UuidGenerator(style = VERSION_7)` only together with RES-13's privacy decision.
- **Pros.** Avoids an incubating audit mechanism that snapshots personal data. Keeps concurrency
  rules intact.
- **Cons.** None; this is a decision record.
- **Risks, security and guard.**
  - `@Audited` would persist full row history, including free text and personal data, outside the
    GDPR deletion flow. It is rejected on security and privacy grounds.
  - Guard: `PagedFindersNoCollectionFetchTest`.
- **Effort.** S.
- **Prerequisites.** REQ-DATA-003 review.

### RES-13 — PostgreSQL 18 features: `RETURNING OLD/NEW` and skip scan now; `uuidv7` only with a privacy decision

- **Evidence.**
  - PG 18.6 is current, supported until 2030-11-14.
  - `uuidv7()` and `uuid_extract_timestamp()`.
  - `RETURNING OLD/NEW`; skip scan; virtual generated columns by default; `WITHOUT OVERLAPS` and
    `PERIOD`; MD5 deprecated.
  - Repo counts (Appendix B-8): 104 `GenerationType.UUID` lines and 44 `gen_random_uuid` lines.
- **Impact.** Modern SQL:
  - `RETURNING OLD/NEW` removes read-then-write pairs in bulk updates that also need the old value
    for an audit record.
  - Skip scan may make some single-column indexes redundant.
  - `WITHOUT OVERLAPS` can express non-overlapping validity for appointments (Leitung) or memberships
    in the database.
- **Proposed change.**
  - Use `RETURNING OLD/NEW` in native bulk updates where applicable.
  - Review multicolumn indexes with `EXPLAIN` on PG 18.
  - Evaluate temporal constraints only with the leadership/orgunit domain redesign.
  - **Do not switch to v7 ids for tables whose ids appear in URLs or exports without a privacy
    decision.**
- **Pros.** Fewer round trips; the database enforces invariants.
- **Cons.**
  - `WITHOUT OVERLAPS` on scalar ids needs `btree_gist`, a new extension in the migration set.
  - Virtual generated columns cannot use user-defined functions.
- **Risks, security and guard.**
  - v7 ids reveal creation times, for example of accounts or bank transactions, to anyone who sees
    an id.
  - Confirm SCRAM for all roles, since MD5 is deprecated (infrastructure check, UNKNOWN here).
  - Guard: Testcontainers PG 18 tests for any changed statement.
- **Effort.** S (review) to M (temporal constraints).
- **Prerequisites.** REQ-DATA; Data Protection note for v7 ids.

### RES-14 — Frontend language level: raise `lib` to ES2024 for widely-available APIs; plan Trusted Types

- **Evidence.**
  - web-features 3.40.0: `Object.groupBy`, `Promise.withResolvers` and `Array.fromAsync` are widely
    available, as are CSS nesting, container size queries, `:has()`, `color-mix()` and `<dialog>`.
  - Set methods, iterator helpers, popover, view transitions (same-document), Trusted Types and
    `@scope` are newly available.
  - `frontend/tsconfig.json:24-29` sets ES2023; `eslint.config.mjs:26,78` sets `ecmaVersion: 2023`.
- **Impact.** Modern JavaScript use is blocked by the type-check `lib` rather than by browser
  support.
- **Proposed change.**
  - Raise `lib`/`target` to ES2024 now: all its APIs are widely available.
  - Keep newly-available APIs (Set methods, iterator helpers) behind the design-system policy until
    they are widely available, or accept "newly available" explicitly in `ui-design-system.md`.
  - Trusted Types: start a `Content-Security-Policy-Report-Only: require-trusted-types-for 'script'`
    experiment to count sinks.
- **Pros.** Less hand-written grouping and deferred code. A path to DOM-XSS hardening.
- **Cons.**
  - Whether TypeScript 7 accepts the lib name is UNKNOWN (settle with `:frontend:typecheckJs`).
  - Trusted Types enforcement is a large refactor of `innerHTML` sinks.
- **Risks, security and guard.**
  - A `lib` bump has no security impact.
  - Trusted Types in report-only mode has none either. Enforcement would *strengthen* CSP, but a
    permissive default policy would nullify it, so forbid `trustedTypes.createPolicy('default', …)`
    with pass-through.
  - Guards: `:frontend:typecheckJs`, `:frontend:lintJs` and the e2e suite.
- **Effort.** S (lib) / L (Trusted Types).
- **Prerequisites.** REQ-FE-018; the CSP spec (REQ-SEC).

### RES-15 — JPMS is not a boundary mechanism for the Basetool

- **Evidence.**
  - No `module-info.class` in spring-core 7.0.9, spring-boot 4.1.1 or spring-modulith-core 2.1.1;
    only `Automatic-Module-Name`.
  - #18079 open since 2015.
  - The Framework reference documents CGLIB limits and `opens` requirements; the Boot reference is
    silent.
  - Hibernate 7.4.5 and Spring Security, Data and Modulith are also automatic modules only (§4 (j)).
  - JEP 483 forbids `--add-opens` and `--add-exports` in runs that use the AOT cache. A module-path
    deployment that needs them would lose ADR-0209's cache.
- **Impact.** Removes JPMS from the option space for A, B and C.
- **Proposed change.** None. Record it in the modularisation ADR as "rejected: JPMS".
- **Pros and cons.** Not applicable.
- **Risks, security and guard.** None.
- **Effort.** S.
- **Prerequisites.** None.

### RES-16 — Use ArchUnit's Modules API and `FreezingArchRule` for incremental boundary enforcement

- **Evidence.**
  - ArchUnit User Guide: `modules().definedByAnnotation(...)`,
    `respectTheirAllowedDependenciesDeclaredIn`, `onlyDependOnEachOtherThroughPackagesDeclaredIn`,
    and `FreezingArchRule` with a text-file `ViolationStore` and line-number-insensitive matching.
  - The empty-should default is to fail.
  - The project is already on ArchUnit 1.5.1.
- **Impact.** Option A enforcement without any new dependency. Existing violations can be frozen and
  only new ones fail, which is the realistic path for 1389 backend classes.
- **Proposed change.**
  - Define `@DomainModule(allowedDependencies=…, exposedPackages=…)` on domain `package-info`
    files and add the modules rule.
  - Wrap the cross-domain rules, including jMolecules' `aggregateReferencesShouldBeViaIdOrAssociation`
    if RES-03 adds it, in `FreezingArchRule`. Commit the store under
    `backend/src/test/resources/archunit_store`.
  - Set `freeze.refreeze=false`; that is the default.
- **Pros.** No new dependency, fine-grained control, and a shrinking violation count as a metric.
- **Cons.**
  - It overlaps with Spring Modulith; choose one as primary to avoid two vocabularies.
  - The freeze store is a file that needs review discipline, because a refreeze hides regressions.
- **Risks, security and guard.**
  - Freezing a *security* rule would hide new violations of that rule: never freeze the
    `@PreAuthorize`, OwnerScope or support-leaf rules.
  - Guard: a test that lists the frozen rules against an allow-list.
- **Effort.** S–M.
- **Prerequisites.** RES-03 decision (Modulith or plain ArchUnit as the primary tool); an ADR.

### RES-17 — Inventory of string-bound class names every package move must update by hand

- **Evidence** (counts in Appendix B-13):
  - **172** `T(de.greluc.krt.profit.basetool.frontend.support.Roles)` references in **22** templates.
  - The session allow-list prefix `"de.greluc.krt.profit.basetool.frontend.model."`
    (`SessionTypeAllowList.java:86-87`, ADR-0206). Existing Redis sessions store `@class` type ids.
  - **47** ArchUnit `"..x.."` package patterns in tests.
  - **54** fully-qualified package string literals in tests, 37 of them in backend
    `ArchitectureTest.java`.
  - `@PreAuthorize` bean names (RES-08).
  - Future `event_publication.event_type` rows (RES-04).
  - 0 hits in YAML, XML or properties for sub-packages.
- **Impact.** These are exactly the references OpenRewrite (RES-09) and IDE refactorings do not
  update.
  - A missed Thymeleaf `T()` fails the render of 22 role-gated pages.
  - A missed allow-list prefix drops session attributes in ENFORCE mode.
  - A missed ArchUnit pattern silently weakens a rule.
- **Proposed change.** Treat these as a checklist in every move PR:
  - Keep `frontend.support.Roles` in a stable shared-kernel package, or expose it to templates as a
    bean or dialect (`@roles.…`) so templates no longer name a class.
  - Keep session-bound form and DTO classes in `frontend.model` or a dedicated `frontend.session`
    package. If they must move, add the **exact** new prefix to `ALLOWED_PREFIXES` and keep the old
    one for one session lifetime.
  - Replace package-string ArchUnit patterns with class-literal anchors
    (`resideInAPackage(X.class.getPackageName() + "..")`) where a class anchor exists.
- **Pros.** Prevents the class of regressions that compile but fail at runtime.
- **Cons.** A little indirection (template bean instead of `T()`).
- **Risks, security and guard.**
  - *Session allow-list:* widening the prefix to `de.greluc.krt.profit.basetool.frontend.` would
    admit config and security classes into session deserialisation. That would weaken ADR-0206 /
    REQ-SEC-067 and must be forbidden. Guard with a test asserting `ALLOWED_PREFIXES` entries end in
    a model or session package.
  - *Templates:* a `T()` that fails to resolve throws at render time, so it fails closed, but a
    hasty "fix" that removes the role check would open the UI gate. Guard with a test that renders
    or greps the templates for `T(` and resolves each class with `Class.forName`.
  - *ArchUnit:* the empty-should default helps only when a rule's whole `that()` set becomes empty.
    Guard with RES-16 class-literal anchors.
- **Effort.** S per move.
- **Prerequisites.** None; this is input to the move plan (arc42 §8, backend and frontend
  CLAUDE.md).

### RES-18 — Boot 4.1 `InetAddressFilter` for the external-integration HTTP clients

- **Evidence.**
  - Boot 4.1 reference: `InetAddressFilter.externalAddresses()` or
    `HttpClientSettings.withInetAddressFilter(...)`. A bean applies to *auto-configured* client
    builders.
  - The backend builds its `RestClient` manually (`backend/config/RestClientConfig.java:76`), and
    ingest does too (`ingest/config/RestClientConfig.java:104,126`).
- **Impact.** A modern SSRF defence for outbound calls whose URLs are built from external data
  (UEX, SC Wiki, Discord).
- **Proposed change.**
  - Apply `HttpClientSettings.withInetAddressFilter(InetAddressFilter.externalAddresses())` only to
    the external-integration clients.
  - Keep the internal clients (Keycloak Admin, ingest → backend) unfiltered.
- **Pros.** Defence in depth against DNS rebinding and redirects to internal segments.
- **Cons.** The filter must not hit the internal clients, or Keycloak and backend calls break.
- **Risks, security and guard.**
  - Security: positive.
  - Regression risk: a misapplied filter blocks internal calls. Guard with a unit test per client
    bean asserting the filter's presence or absence, plus MockWebServer tests on loopback expecting
    rejection for external-integration clients.
- **Effort.** S.
- **Prerequisites.** REQ-SEC addition; the vault Topology and Security notes.

### RES-19 — Make the unknown-JSON-property policy an explicit part of each API contract

- **Evidence.**
  - Jackson 3.1.5 has `FAIL_ON_UNKNOWN_PROPERTIES(false)`, "disabled by default as of Jackson 3.0".
  - Boot 4.1.1 does not change it (§4 (i)).
  - The repository has no global override. One strict local mapper exists
    (`MissionWriteController.java:148-149`).
- **Impact.** Every backend request DTO silently ignores unknown fields. That is tolerant-reader
  behaviour, good for rolling compatibility. It also hides client bugs, such as a renamed field that
  the server never receives, which matters once domains talk only through "complete and stable"
  APIs.
- **Proposed change.**
  - Keep tolerant reading for internal frontend-to-backend DTOs, since they are versioned together
    and checked by `FrontendDtoContractTest` and `GeneratedDtoAgreementTest`.
  - Make **external** inputs strict with a dedicated mapper or per-endpoint configuration: the
    Exchange API (VerseKit, SC extractor) and ingest.
  - Record the policy in REQ-API.
- **Pros.** Unexpected fields become explicit 400s at the external edge.
- **Cons.** Strictness breaks clients that send extra fields. Roll it out with a deprecation window.
- **Risks, security and guard.**
  - Strict parsing reduces the attack surface of unexpected fields, a positive effect.
  - Tolerant parsing is not a mass-assignment risk here, because DTOs are records with explicit
    components.
  - Guard: MockMvc tests posting an unknown field to one internal and one external endpoint, each
    asserting the chosen behaviour.
- **Effort.** S.
- **Prerequisites.** REQ-API; the Exchange API documentation (`docs/exchange`).

### RES-20 — Replace `ParallelPageLoader`'s hand-copied context with `ContextSnapshotFactory`

- **Evidence.**
  - `ParallelPageLoader.java:54-110` captures and re-installs six holders by hand:
    ActiveSquadron, Correlation, ClientIp, the `SecurityContext`, the `RequestAttributes` and the MDC.
  - context-propagation 1.2.1's `ContextSnapshotFactory.captureAll()` and
    `ContextSnapshot.wrapExecutor(Executor)` capture every registered `ThreadLocalAccessor`
    (§5 (p)).
  - The global registry already loads Spring Security's `SecurityContextHolderThreadLocalAccessor`
    through ServiceLoader. The frontend registers the Correlation, ActiveSquadron, ClientIp and
    locale accessors (`ReactorContextPropagationConfig.java:80`).
- **Impact.** Modern API use and less custom code. ADR-0223 point 4 keeps `ThreadLocal`, but this
  uses exactly the relay that ADR-0223 relies on.
- **Proposed change.**
  - Wrap the virtual-thread executor: `snapshotFactory.captureAll().wrapExecutor(executor)` per
    page load.
  - Register the Framework's `RequestAttributesThreadLocalAccessor` in
    `ReactorContextPropagationConfig`.
  - Keep an explicit MDC copy, since there is no MDC accessor, or add a small MDC accessor.
- **Pros.** A new accessor automatically reaches the parallel loader, which closes the "forgot to
  copy it" failure class that frontend/CLAUDE.md warns about.
- **Cons.**
  - `RequestAttributes` on another thread keep the servlet-request lifetime caveats of today's
    code.
  - `clearMissing` semantics need a test.
- **Risks, security and guard.**
  - If the `SecurityContext` were not captured, the parallel calls would lose the OAuth2 relay and
    fail with 401, which fails closed.
  - If a value leaked into a later task, that would be a cross-request leak. The executor is
    thread-per-task on virtual threads, so no thread is reused.
  - Guard: extend the existing `ParallelPageLoaderTest` (`frontend/src/test/…/service/`) to assert
    that all six values arrive and that none survives after the task.
- **Effort.** S.
- **Prerequisites.** Note it in ADR-0223's consequences; frontend/CLAUDE.md "context propagation"
  paragraph.

### RES-21 — CI configuration-cache reuse needs `cache-encryption-key`; decide deliberately

- **Evidence.**
  - setup-gradle's `cache-encryption-key` input: "Configuration-cache data will not be
    saved/restored without an encryption key being provided".
  - CI pins setup-gradle v6.3.0 without the key (`.github/actions/setup-jdk-gradle/action.yml:33`),
    so `--configuration-cache` in `ci.yml:63,76` and `e2e.yml:154` never reuses entries across runs.
  - The repository is public: 4 vCPU / 16 GB runners and free "enhanced" caching (§4 (e)(f)).
- **Impact.** Build time only. The configuration phase is paid on every CI run.
- **Proposed change.**
  - Either add a `GRADLE_ENCRYPTION_KEY` secret and pass `cache-encryption-key`, keeping PR runs
    `cache-read-only`,
  - or record that configuration-cache reuse in CI is deliberately off.
  - Bump to v6.4.0 (2026-09-28) through the normal Dependabot path.
- **Pros.** Faster CI once the configuration is stable.
- **Cons.** One more secret to rotate. Configuration-cache entries can contain values the build
  captured.
- **Risks, security and guard.**
  - Cached configuration can carry build-time secrets, hence the encryption.
  - Cache poisoning is mitigated by writing only on `main` (`cache-read-only` elsewhere, `ci.yml:32`)
    and `cache-disabled` for release builds (zizmor `cache-poisoning`).
  - Guard: keep `cache-read-only` on PRs, and check zizmor findings on the workflow change.
- **Effort.** S.
- **Prerequisites.** A workflow change and a secret, following REQ-OPS; decided by the owner.

### RES-22 — Option B's persistence and verification mechanics hold across domain jars

- **Evidence.**
  - Flyway 13.8.0 merges one `classpath:db/migration` location across all jars, with checksums
    independent of the jar and duplicate versions rejected (§4 (g)).
  - Boot scans entities and repositories in all jars under the auto-configuration package (§4 (h)).
  - The root `verification-metadata.xml` also covers included builds (§4 (a)).
  - Test fixtures stay out of `bootJar` unless declared in a main configuration (§4 (c)).
  - The project's jars contain directory entries (§4 (g)).
- **Impact.** It removes several unknowns from option B and option C: a Gradle module per domain can
  keep one Flyway history and one JPA persistence unit.
- **Proposed change.** If option B or C is chosen:
  - keep all domain packages under `de.greluc.krt.profit.basetool.backend`;
  - keep one global `V<n>` sequence;
  - move each migration file with its domain module (the checksum is unchanged);
  - put shared test helpers in `java-test-fixtures`. The 9.8.0 testing guide does not mark test
    fixtures as incubating, but it does mark `jvm-test-suite` as incubating.
- **Pros.** No change to production schema history or to the persistence unit.
- **Cons.** Version numbers stay globally coordinated across modules; the "claim at push time"
  practice continues.
- **Risks, security and guard.**
  - A domain module outside the root package would silently lose entity scanning and fail
    `ddl-auto: validate` at startup, which fails closed.
  - A `testFixtures` dependency in main scope would ship test code in the image. Guard with a Gradle
    check on main-scope `testFixtures` capabilities.
  - A duplicate migration version fails at startup.
  - Guard: the existing Flyway migration workflow plus the Gradle check.
- **Effort.** S (as rules for option B).
- **Prerequisites.** RES-11; the option B/C decision ADR.

---

## 4. Build and runtime details (coordinator follow-up a–j; all read 2026-09-29)

### (a) Dependency verification and an included `build-logic` build

- **Gradle 9.8.0** (dependency_verification.html): verification "is global; a single file is used to
  verify the entire build". It covers all subprojects, the root project and `buildSrc`.
- For an **included build**: "The configuration file of the current build is used for
  verification. If an included build has its own verification metadata, that configuration is
  ignored". Including a new build "may require you to update your current verification metadata".
- `--write-verification-metadata` resolves the "Included builds configurations" as well.
- **Implication.** A `build-logic` included build, with `kotlin-dsl` and its Kotlin toolchain
  artifacts, needs **no second file**. Its artifacts go into the root
  `gradle/verification-metadata.xml`, regenerated with the ADR-0208 command in the same commit.

### (b) Version catalog inside precompiled script plugins

- **Supported mechanism** (version_catalogs.html, 9.8.0): the catalog is not inherited. Import it in
  the included build's `settings.gradle.kts`:
  `dependencyResolutionManagement { versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } } }`.
  Type-safe `libs.…` then works in that build's **own** build script.
- **Inside a precompiled script plugin** only the string API is documented:
  `extensions.getByType(VersionCatalogsExtension::class.java).named("libs").findLibrary("x").get()`.
  Also, "the plugins block in the precompiled script plugin cannot access the version catalog".
- **Type-safe accessors** in precompiled plugins are **not supported**. gradle/gradle **#15383** has
  been open since 2020-12-01 (389 reactions, last updated 2026-06-22). The common workaround from
  that thread, `implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))`
  (comment of 2021-02-16), depends on generated-class internals and is unsupported. Whether it is
  compatible with the configuration cache or Isolated Projects is UNKNOWN.
- **Implication.** Use the `VersionCatalogsExtension` string API in convention plugins, and put plugin
  versions in `build-logic/build.gradle.kts` dependencies, where type-safe `libs` works.

### (c) `java-test-fixtures` and `jvm-test-suite`

- **Test fixtures** (java_testing.html, 9.8.0) are configured so that they "can see the main source
  set classes" and "test sources can see the test fixtures classes". Main does not depend on them.
  With `maven-publish` they are published as extra variants with the `test-fixtures` classifier.
- The docs contain no explicit "never on `runtimeClasspath`" sentence.
- **bootJar** (spring-boot-gradle-plugin source, `JavaPluginAction.java:163-181`, tag v4.1.1): the
  classpath is `mainSourceSet.getRuntimeClasspath()` minus `developmentOnly` and
  `testAndDevelopmentOnly`. Test fixtures therefore reach the jar **only** if a main-scope
  configuration declares `testFixtures(project(":x"))`.
- **Guard:** a build check that no `implementation`, `runtimeOnly` or `api` dependency is a
  `testFixtures(...)` capability.
- **`jvm-test-suite`:** "The JVM Test Suite plugin is an incubating API and is subject to change in a
  future release" (jvm_test_suite_plugin.html, 9.8.0).

### (d) Isolated Projects and cross-project configuration

**Yes, both patterns are rejected** (isolated_projects.html, 9.8.0).

- Applying plugins or configuration to other projects from `allprojects {}` or `subprojects {}` "is
  not allowed".
- The mutable state of another project explicitly includes `extensions`, **`ext`**, **`layout`**,
  `version`, `group`, `tasks` and `configurations`. The page adds that "it is easier to assume calling
  any method on another Project is forbidden", except for immutable getters: `name`, `path`,
  `projectDir`, `buildFile`, `rootDir` and the `isolated` view.
- Since 9.7.0 the build "fails immediately on the first violation". Diagnostics mode is
  `org.gradle.isolated-projects.diagnostics=true`.

**Repository count**, via `grep -cE "rootProject\.(file|fileTree|layout|extra)"`:

- root `allprojects {` (`build.gradle.kts:14`) and `subprojects {` (`:122`);
- subproject scripts: backend 7, frontend 13, test-support 2 (**22**);
- 5 more in the root script, several of them inside `subprojects {}`;
- `rootProject.extra[...]` in `frontend/build.gradle.kts:169-172`.

The replacements are `isolated.rootProject.projectDirectory`, `rootDir` and convention plugins.

### (e) `gradle/actions/setup-gradle`: configuration-cache reuse

- The `cache-encryption-key` input description (`setup-gradle/action.yml`, main) reads:
  "Configuration-cache data will not be saved/restored without an encryption key being provided".
  - The key is a base64 AES key (`openssl rand -base64 16`) and is exported as
    `GRADLE_ENCRYPTION_KEY`.
  - docs/setup-gradle.md explains the encryption: configuration-cache entries can contain sensitive
    information.
- Latest version: **v6.4.0** (2026-09-28).
- The default cache provider "enhanced" is proprietary and "free for all public repositories".
  `cache-provider: basic` is MIT-licensed, built on `actions/cache`, and has no cleanup, no
  deduplication and no restore keys.
- **Repository.** CI uses setup-gradle **v6.3.0** (SHA `9c971963…`, in
  `.github/actions/setup-jdk-gradle/action.yml:33`) with **no** `cache-encryption-key`.
  - Configuration-cache entries from `ci.yml:63,76` and `e2e.yml:154` are therefore never restored
    in a later run.
  - PR runs are `cache-read-only` (`ci.yml:32`), which is correct against cache poisoning.

### (f) GitHub-hosted runner size

- docs.github.com, github-hosted-runners reference: **public repositories** get `ubuntu-latest`
  (also 24.04, 22.04, 26.04) with **4 CPU, 16 GB RAM, 14 GB SSD, x64**. Linux arm64 is 4 CPU / 16 GB.
  Private repositories get 2 CPU / 8 GB.
- `krt-profit/basetool` is **PUBLIC** (`gh repo view --json visibility`), so the 4 vCPU / 16 GB
  tier applies, including `ubuntu-24.04-arm` in `promote.yml:73`.

### (g) Flyway 13: one `classpath:` location across several jars; checksums

Source at tag `flyway-13.8.0`; the latest release is 13.8.1 (2026-09-29).

- **Multi-jar scanning.** `ClassPathScanner.getLocationUrlsForPath` calls
  `classLoader.getResources(location.getRootPath())` and scans every URL it returns. One
  `classpath:db/migration` location is therefore merged across all jars and directories that
  contain it.
- **Checksum.** `ChecksumCalculator` computes a CRC32 over the UTF-8 bytes of each **line**, with
  the BOM filtered and line terminators excluded. Path and jar are not inputs, so **moving a
  migration between jars with identical content keeps its checksum**.
- **Validation.** `MigrationInfoImpl` checks description, type and checksum mismatches. No script
  path comparison was found in its validate logic.
- **Duplicates.** The same version in two jars fails with "Found more than one migration with
  version %s" (`CompositeMigrationResolver.java:107`).
- **Caveat.** `getResources("db/migration")` needs directory entries in the jars. The project's
  Gradle jars have them: `logging-support-0.0.1-SNAPSHOT.jar` holds 7 directory entries out of 12.

### (h) Boot 4.1: entity scanning and `AutoConfigurationPackages` across jars

- Boot data/sql.html (4.1.1): "By default the auto-configuration packages are scanned". That covers
  `@Entity`, `@Embeddable`, `@MappedSuperclass` and Spring Data repositories. `@EntityScan` and
  `@EnableJpaRepositories` customise it.
- Package scanning resolves through `ClassLoader.getResources()` over every classpath entry
  (Framework 7.0.9 `resources.adoc:840-843,855`). **Entities in several jars under the same root
  package are found.**
- Two conditions:
  1. The domain packages must lie under the auto-configuration package, which is the
     `@SpringBootApplication` class's package. A sibling root package needs `@EntityScan`,
     `@EnableJpaRepositories` and `scanBasePackages`.
  2. The jars need directory entries (`classpath-scanning.adoc:311-314`). Gradle jars have them, as
     shown in (g).

### (i) `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES` in Boot 4.1

- **Jackson 3.1.5** (the version Boot 4.1.1 manages) declares `FAIL_ON_UNKNOWN_PROPERTIES(false)`.
  The Javadoc says it is "disabled by default as of Jackson 3.0 (in 2.x it was enabled)"
  (`DeserializationFeature.java:136-150`, tag jackson-databind-3.1.5).
- Other changed defaults: `FAIL_ON_NULL_FOR_PRIMITIVES(true)` and `FAIL_ON_TRAILING_TOKENS(true)`
  (lines 161 and 293).
- Boot 4.1.1's `JacksonAutoConfiguration` does not change the feature. Only
  `spring.jackson.use-jackson2-defaults=true` disables it explicitly (lines 515–519), which gives the
  same value.
- **The effective default is `false`: unknown properties are ignored.** It can be overridden with
  `spring.jackson.deserialization.fail-on-unknown-properties=true`.
- Repository: no global override. One local strict mapper for form carriers
  (`frontend/…/MissionWriteController.java:148-149`).

### (j) AOT cache and the module path; `module-info` in the stack

- **JEP 483:**
  - "Only classes loaded from the class path, the module path, and the JDK itself, by the JDK's
    built-in class loaders, can be cached". Classes from user-defined loaders are not.
  - Class paths must contain only JAR files.
  - `-m`, `--module`, `-p`, `--module-path`, `--add-modules` and `--enable-native-access` must be
    **identical** across runs.
  - `--add-exports`, **`--add-opens`**, `--add-reads`, `--illegal-native-access`,
    `--limit-modules`, `--patch-module` and `--upgrade-module-path` **"must not be used"**.
  - No class-file-rewriting JVMTI agents.
- **JEP 514** only adds the one-step `-XX:AOTCacheOutput` workflow, which needs double the heap
  during training.
- So the module path works in principle. A Spring or Hibernate application on the module path that
  needs `--add-opens` would lose the AOT cache.
- The image extracts the Boot jar and runs class-path based (`docker/app/Dockerfile:46`;
  `ENTRYPOINT ["java", "-XX:AOTCache=/app/app.aot", "-jar", "/app/app.jar"]` at `:125`), which
  satisfies all constraints.

**`module-info.class` presence** (`70-research-modinfo.py`, jars from Maven Central):

| Artifact | `module-info.class` | `Automatic-Module-Name` |
| --- | --- | --- |
| spring-core / spring-context / spring-webmvc 7.0.9 | none | `spring.core` / `spring.context` / `spring.webmvc` |
| spring-boot / spring-boot-autoconfigure 4.1.1 | none | `spring.boot` / `spring.boot.autoconfigure` |
| spring-security-core 7.1.1 | none | `spring.security.core` |
| spring-data-jpa 4.1.1 | none | `spring.data.jpa` |
| spring-modulith-core 2.1.1 | none | `spring.modulith.core` |
| hibernate-core 7.4.5.Final | none | `org.hibernate.orm.core` |
| jackson-databind 3.1.5 | **present** | — |
| jspecify 1.0.1 | **present** | — |

---

## 5. Frontend client details (coordinator follow-up k–s; all read 2026-09-29)

### (k) `@Cacheable` on an `HttpServiceProxyFactory` proxy bean

- The proxy is an interface-only Spring AOP proxy.
- spring-framework **#29782** (closed 2023-01-27, milestone 6.0.5) fixed advisor ordering for such
  proxies, so the `@HttpExchange` advisor stays last. The maintainer's closing comment says the
  change helps "any other AOP advice applied to an HTTP interface client"; the concrete case was
  method validation.
- No documentation or issue confirming **`@Cacheable`** specifically was found, so it is
  **UNKNOWN**. Settle it with a test: `@EnableCaching`, `@Cacheable` on an interface method, and a
  count of MockWebServer requests.
- **Security:** a cache key made of method arguments ignores the implicit bearer token and
  `X-Active-Org-Unit-Id`. That would serve one user's or tenant's data to another. Keep caching in
  the façade behind the compile-time allow-list (`CachedCatalog.java:26`).

### (l) `MultipartFile` `@RequestPart` with `WebClientAdapter`

- `RequestPartArgumentResolver` (7.0.9) turns a `MultipartFile` into
  `new HttpEntity<>(multipartFile.getResource(), headers)` with the filename and content type.
- WebClient's `MultipartHttpMessageWriter` writes `Resource` parts with `ResourceHttpMessageWriter`.
  `ResourceEncoder.encode` returns `DataBufferUtils.read(resource, bufferFactory, bufferSize)` with
  `StreamUtils.BUFFER_SIZE`, so **the part is read in chunks** from the file's `InputStream`. The
  codec does not buffer it whole.
- Two things were not examined:
  - where the servlet `MultipartFile` itself lives, which depends on `spring.servlet.multipart.*`;
  - retrying filters, which re-subscribe and re-read the resource.

### (m) Per-call timeout through `@RequestAttribute` and a filter

The building blocks are documented:

- the `@RequestAttribute` interface parameter ("Only supported by RestClient and WebClient");
- WebClient attributes "influence the behavior of filters for a given request";
- a per-request response timeout through
  `httpRequest(r -> ((HttpClientRequest) r.getNativeRequest()).responseTimeout(d))`;
- `ClientRequest.attribute(name)` and `ClientRequest.Builder.httpRequest(Consumer)` in
  `ClientRequest.java:89,261` (7.0.9).

Combining them into a per-call timeout is **not a documented recipe**. In the frontend the
Resilience4j TimeLimiter filter already bounds each call (`WebClientConfig.java:483-485`). A per-call
attribute must be read *by that filter* to take effect.

### (n) Boot 4.1 auto-configuration for HTTP service groups

**Modules** (tag v4.1.1):

- `spring-boot-http-client`: `HttpServiceClientProperties` and
  `HttpServiceClientPropertiesAutoConfiguration`, which bind `spring.http.serviceclient.*`.
- `spring-boot-restclient`: `HttpServiceClientAutoConfiguration`,
  `PropertiesRestClientHttpServiceGroupConfigurer`, `RestClientCustomizerHttpServiceGroupConfigurer`.
- `spring-boot-webclient`: `ReactiveHttpServiceClientAutoConfiguration`,
  `PropertiesWebClientHttpServiceGroupConfigurer`, `WebClientCustomizerHttpServiceGroupConfigurer`.

**Per group:**

- The Framework registry creates **its own client builder** (`groupAdapter.createClientBuilder()`)
  and proxy factory, then builds the adapter from that builder in `createProxies()`
  (`HttpServiceProxyRegistryFactoryBean.java:222-233`).
- **To supply a pre-configured client**, use `Groups.forEachClient(InitializingClientCallback<CB>)`,
  a "Callback to supply the client builder" (`HttpServiceGroupConfigurer.java`, 7.0.9). For example,
  return the existing `webClient.mutate()`.
- It must run before any callback that touches the builder, because the registry asserts "Client
  builder already initialized" (line 209). Give that configurer precedence over Boot's configurers,
  which use order 0.
- The alternative without the registry is
  `HttpServiceProxyFactory.builderFor(WebClientAdapter.create(webClient))`.

### (o) openapi-generator 7.25 `java` generator

- Release v7.25.0 was published 2026-08-24.
- `useJackson3`: "Use Jackson 3 instead of Jackson 2. Supported for 'native', 'apache-httpclient',
  and 'jersey3' libraries (requires Java 17+) and for Spring 'resttemplate', 'webclient', and
  'restclient' libraries (require useSpringBoot4=true)". There is also `useSpringBoot4`
  (`docs/generators/java.md` at v7.25.0).
- **Records:** no record option in the `java` generator docs (grep for "record": 0 hits), so
  records are not supported as far as documented.
- **Annotations:** Jackson 3 keeps `jackson-annotations` in the `com.fasterxml.jackson.annotation`
  package (Boot 4.0 migration guide). The generated annotation imports are therefore the same either
  way; `useJackson3` switches databind usage to `tools.jackson`.
- Repository: `generatorName=java`, `library=native`, `serializationLibrary=jackson`, no
  `useJackson3`, models only, test source set only (`frontend/build.gradle.kts:25-58`).

### (p) Micrometer context propagation: capture and restore all accessors

- **context-propagation 1.2.1** is resolved by the project (`gradle/verification-metadata.xml:2424`)
  and is the latest on Central.
- Capture:
  `ContextSnapshotFactory.builder()[.contextRegistry(r).clearMissing(b).captureKeyPredicate(p)].build().captureAll(Object... contexts)`
  returns a `ContextSnapshot`.
- Restore with `snapshot.wrap(Runnable | Callable | Consumer)`, `snapshot.wrapExecutor(Executor)`, or
  `try (Scope s = snapshot.setThreadLocals()) {…}`.
- The static `ContextSnapshot.captureAll`, `captureFrom` and `setAllThreadLocalsFrom` are
  **`@Deprecated`** (source at tag v1.2.1).
- The global `ContextRegistry.getInstance()` loads `ThreadLocalAccessor`s via `ServiceLoader`
  (`ContextRegistry.java:42-43`):
  - Spring Security 7.1.1 registers **`SecurityContextHolderThreadLocalAccessor`** that way.
  - Framework 7.0.9 ships `LocaleContextThreadLocalAccessor` and `RequestAttributesThreadLocalAccessor`
    **without** a ServiceLoader entry, so they must be registered manually.
  - No MDC accessor is present.

### (q) `docker/metadata-action` and `org.opencontainers.image.revision`

- **Yes, it is a default label.** `getOCIAnnotationsWithCustoms` in `src/meta.ts:540-549` (v6.2.0)
  always emits:
  - `org.opencontainers.image.title`, `description`, `url`, `source` and `version`;
  - `created`;
  - **`revision=${this.context.sha}`**;
  - `licenses`.
- A custom `labels` input can override them.
- The repository pins **v6.2.0** (SHA `dc802804…`, the current `v6`) in `release-images.yml` and
  `sandbox-images.yml`.

### (r) `skopeo inspect` on a multi-arch index

- skopeo-inspect(1) (main; latest release v1.24.1, 2026-09-16): the default output combines `Name`,
  `RepoTags`, the **top-level manifest's `Digest`** (for a multi-arch image, the index digest), and
  "a per-architecture/OS image matching the current run-time environment (most other values)".
- So **`Labels` come from the image for the host's own OS and architecture**.
- Select another platform with the global `--override-os`, `--override-arch` and
  `--override-variant` options (skopeo(1)).
- `--raw` prints the raw manifest. For an index that is the index JSON, which carries no config
  Labels. With `--config` it prints the raw config. `--format` is not supported with `--raw`.

### (s) Several unnamed patterns in one case label (Java 22+)

- **Allowed.** JLS SE 25 §14.11.1: "It is a compile-time error for a case label to have more than one
  case pattern and declare any pattern variables" (except variables a guard declares).
- `case A _, B _ ->` declares no variables, so it is legal. It came with **JEP 456** (final in 22):
  multiple patterns act as alternatives, and one guard applies to the whole label.
- Not usable in `keycloak-spi` (`--release 21`).
- google-java-format 1.36.1 and Checkstyle 14.3.0 support: UNKNOWN. Settle it with `spotlessApply`
  and `checkstyleMain` on a sample.

---

## 6. Browser platform details (coordinator follow-up 1–6; all read 2026-09-29)

Support data comes from **web-features 3.40.0** (npm, published 2026-09-24; script
`70-research-browserfloor.py`). Versions are the first supporting release. For single BCD keys the
per-key values in `status.by_compat_key` are used.

### (1) Features the app already ships: the implied floor

| Feature (web-features id or BCD key) | Baseline | Chrome/Edge | Firefox | Safari / iOS Safari |
| --- | --- | --- | --- | --- |
| CSS `@layer` (`cascade-layers`) | widely (2024-09-14) | 99 | 97 | 15.4 |
| `:has()` (`has`) | widely (2026-06-19) | **105** | **121** | 15.4 |
| Media-query range syntax (`media-query-range-syntax`) | widely (2025-09-27) | 104 | 102 | **16.4** |
| `<dialog>` / `showModal()` (`dialog`) | widely (2024-09-14) | 37 (Edge 79) | 98 | 15.4 |
| `:focus-visible` (`focus-visible`) | widely (2024-09-14) | 86 | 85 | 15.4 |
| `inert` (`inert`) | widely (2025-10-11) | 102 | 112 | 15.5 |
| `Element.replaceChildren` (`api.Element.replaceChildren`) | widely (2023-04-20) | 86 | 78 | 14 |
| `AbortController` (`api.AbortController`) | widely (2021-09-25) | 66 (Edge 16) | 57 | 12.1 / 12.2 |

**The implied de-facto floor is Chrome/Edge 105, Firefox 121, and Safari/iOS Safari 16.4.** It is
set by `:has()` for Chromium and Firefox, and by the range syntax for Safari. Android builds have the
same numbers (Chrome Android 105, Firefox Android 121).

### (2) JavaScript API support

| API | Baseline | Newly available | Widely available | Chrome | Firefox | Safari |
| --- | --- | --- | --- | --- | --- | --- |
| `Array.prototype.at` (`array-at`) | widely | 2022-03-14 | 2024-09-14 | 92 | 90 | 15.4 |
| `Object.hasOwn` (`object-hasown`) | widely | 2022-03-14 | 2024-09-14 | 93 | 92 | 15.4 |
| `structuredClone` (`structured-clone`) | widely | 2022-03-14 | 2024-09-14 | 98 | 94 | 15.4 |
| `toSorted`/`toReversed`/`toSpliced`/`with` (`array-by-copy`) | widely | 2023-07-04 | 2026-01-04 | 110 | 115 | 16 |
| `Object.groupBy`/`Map.groupBy` (`array-group`) | widely | 2024-03-05 | 2026-09-05 | 117 | 119 | 17.4 |
| `Promise.withResolvers` | widely | 2024-03-05 | 2026-09-05 | 119 | 121 | 17.4 |
| Set methods (`set-methods`) | newly | 2024-06-11 | — | 122 | 127 | 17 |
| Iterator helpers (`iterator-methods`) | newly | 2025-03-31 | — | 122 | 131 | 18.4 |
| `AbortSignal.timeout()` (`api.AbortSignal.timeout_static`) | newly | 2024-04-18 | — | 124 | 100 | 16 |
| `AbortSignal.any()` (`api.AbortSignal.any_static`) | widely | 2024-03-19 | 2026-09-19 | 116 | 124 | 17.4 |
| RegExp `v` flag (`javascript.builtins.RegExp.unicodeSets`) | widely | 2023-09-18 | 2026-03-18 | 112 | 116 | 17 |

Against the floor from (1):

- `at`, `hasOwn` and `structuredClone` are covered.
- Array-by-copy, `groupBy`, `withResolvers`, `AbortSignal.any` and the `v` flag need higher Safari
  versions (16–17.4) than the floor of 16.4 for some rows.
- **The typecheck `lib` is ES2023 (`frontend/tsconfig.json:24-29`) in any case.**

### (3) Trusted Types

- **Support:** newly available since **2026-02-24**: Chrome/Edge 83, **Firefox 148**, **Safari and
  iOS Safari 26**. It is not widely available until about 2028-08 (projected with the 30-month
  rule).
- **`innerHTML = ''` under enforcement.** The W3C spec's "Get Trusted Type compliant string" (§3.4)
  has **no empty-string exemption**.
  - A plain string goes to the default policy. With no default policy, or when it returns null,
    that is a violation: in **enforce** mode a `TypeError` is thrown. In report-only mode the value
    passes and the violation is reported.
  - The spec offers `trustedTypes.emptyHTML` for exactly this case ("no need to create a policy").
    `replaceChildren()` is not a Trusted Types sink.
- **Report-only and the in-page event.** CSP3 (editor's draft) §5.5 "Report a violation" fires
  `securitypolicyviolation` (a `SecurityPolicyViolationEvent`) at the element or document for every
  violation, with `disposition` set to the violation's disposition. MDN documents
  `disposition = "report"` as "reported but the resource request is not blocked". **So yes,
  report-only violations fire the event.**

### (4) Import maps, nonces and script order

- **Support:** `import-maps` has been widely available since 2025-09-27 (Chrome 89, Firefox 108,
  Safari 16.4).
- **Inline import map with a nonce.** The HTML standard's "prepare the script element" runs *"Should
  element's inline behavior be blocked by Content Security Policy?"* for every `<script>` without
  `src` (cspType `"script"`), including `type="importmap"`. An inline import map therefore needs the
  nonce, or a hash, under a strict `script-src`.
- **Nonce propagation.**
  - `HostLoadImportedModule` takes `originalFetchOptions` from the **referencing script's fetch
    options**, for static imports in a module graph and for dynamic `import()` alike.
  - "get the descendant script fetch options" copies them, including the cryptographic nonce, and
    only resets integrity (from the import map's `integrity`) and priority.
  - So imports reached from a nonced module inherit its nonce under a plain nonce policy, with or
    without `'strict-dynamic'`.
  - Source: html.spec.whatwg.org/multipage/webappapis.html.
- **Order.** In "prepare the script element", a parser-inserted script that "has a defer attribute
  or … type is 'module'" (and is not async) is appended to the **same** "list of scripts that will
  execute when the document has finished parsing". Classic `defer` scripts and non-async module
  scripts therefore run **interleaved in document order**.

### (5) CSS and HTML features

| Feature | Baseline | Chrome | Firefox | Safari |
| --- | --- | --- | --- | --- |
| Nesting (`nesting`) | widely (2026-06-11) | 120 | 117 | 17.2 |
| Container size queries | widely (2025-08-14) | 105 | 110 | 16 |
| Container style queries | newly (2026-05-19) | 111 | 151 | 18 |
| `@scope` | newly (2026-03-24) | 143 | 146 | 26.4 |
| `color-mix()` | widely (2025-11-09) | 111 | 113 | 16.2 |
| `@starting-style` | newly (2024-08-06) | 117 | 129 | 17.5 |
| Same-document view transitions | newly (2025-10-14) | 111 | 144 | 18 |
| Cross-document view transitions | **not Baseline** | 126 | — | 18.2 |
| Popover | newly (2025-01-27) | 116 | 125 | 17 (iOS 18.3) |
| `<dialog closedby>` | **not Baseline** | 134 | 141 | — |
| `dvh`/`svh`/`lvh` (`viewport-unit-variants`) | widely (2025-06-05) | 108 | 101 | 15.4 |

- **"Relaxed" nesting.** web-features 3.40.0 has only the `css.selectors.nesting` key, with no
  separate key for the relaxed (bare type selector) syntax. Whether 120/117/17.2 are the relaxed
  versions is **UNKNOWN**; the BCD notes would settle it.
- **Popover with a modal `<dialog>`.** HTML §6.3.1: while a document is blocked by the topmost modal
  dialog, "every node that is connected to document, with the exception of the subject element and
  its flat tree descendants, must become inert". A popover **outside** the open dialog is therefore
  inert even when shown. A popover **inside** the dialog works. This matches the repository's rule of
  appending into `window.krtModal.layerRoot()` (frontend/CLAUDE.md).

### (6) Tooling

- **Stylelint.** `no-unknown-custom-properties` is a **core rule** ("Disallow unknown custom
  properties"). It was added in **15.4.0** (2023-04-01) and is present at **17.15.0** (2026-09-04;
  the frontend uses `^17.15.0`, `frontend/package.json:26`).
  - It treats as known the properties defined in the same source, or in files listed in the
    experimental **`referenceFiles`** configuration property (added in **17.9.0**, 2026-04-23).
    That property lets page CSS resolve the tokens from `styles.css`.
- **Spring Framework 7.0.9** `spring-webmvc` has only `ResourceTransformer`,
  `CachingResourceTransformer` and **`CssLinkResourceTransformer`**, which rewrites CSS `@import` and
  `url()` links, plus resolvers such as `VersionResourceResolver`.
  - **No transformer rewrites JavaScript module import specifiers** (source tree at v7.0.9).
  - A server-generated import map that maps specifiers to `ResourceUrlProvider`-versioned URLs,
    inlined with the CSP nonce, is the usual alternative. That is an inference; Spring does not
    document it.

---

## 7. Data appendix

### A. Sources (all read 2026-09-29)

| # | URL or source | Used for |
| --- | --- | --- |
| 1 | https://openjdk.org/projects/jdk/24/ · /25/ · /26/ · /27/ · /28/ · https://openjdk.org/projects/jdk/ | JEP lists, GA dates, cadence |
| 2 | https://openjdk.org/jeps/456 · 467 · 485 · 491 · 500 · 504 · 513 · 516 · 517 · 522 · 523 · 527 · 534 · 536 · 542 · 544 | JEP status and content |
| 3 | https://www.oracle.com/java/technologies/java-se-support-roadmap.html (curl with a browser UA; WebFetch got 403) → `70-research-oracle-roadmap.html` lines 658–768 | LTS cadence, next LTS, support dates |
| 4 | https://github.com/spring-projects/spring-modulith/releases, `gh api repos/spring-projects/spring-modulith/releases`, https://spring.io/projects/spring-modulith | Modulith versions and dates |
| 5 | https://docs.spring.io/spring-modulith/reference/{fundamentals,verification,testing,events,documentation,production-ready,runtime,appendix}.html (docs 2.1.1) | Modulith features |
| 6 | spring-modulith source at tag 2.1.1: `spring-modulith-api/.../ApplicationModule.java`, `NamedInterface.java`, `Modulithic.java`; `spring-modulith-events-api/.../ApplicationModuleListener.java`; `spring-modulith-events-jdbc/.../schemas/v2/schema-postgresql.sql`; `spring-modulith-events-jdbc/.../JdbcEventPublicationRepository.java` (`loadClass`) | Attributes, listener semantics, DDL, handling of unknown event types |
| 7 | Maven Central POMs: `spring-modulith-core/2.1.1`, starters `-core`, `-jpa`, `-test` 2.1.1; jars for manifests | Dependency tree, JPMS check |
| 8 | https://github.com/xmolecules/jmolecules, https://github.com/xmolecules/jmolecules-integrations (source `JMoleculesDddRules`, `JMoleculesArchitectureRules` at tag 0.33.0), Maven Central metadata, `jmolecules-bom-2025.0.2.pom` | jMolecules |
| 9 | https://www.archunit.org/userguide/html/000_Index.html; `gh api repos/TNG/ArchUnit/releases/tags/v1.5.0`, `v1.5.1` | ArchUnit |
| 10 | `spring-boot-dependencies-4.1.1.pom`, `spring-data-bom-2026.0.1.pom` (Maven Central) | Managed versions |
| 11 | https://docs.spring.io/spring-boot/system-requirements.html; https://docs.spring.io/spring-boot/reference/io/rest-client.html; https://docs.spring.io/spring-boot/reference/features/spring-application.html | Boot 4.1.1 docs |
| 12 | https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Release-Notes, …/Spring-Boot-4.1-Release-Notes, …/Spring-Boot-4.0-Migration-Guide; https://spring.io/blog/2025/10/28/modularizing-spring-boot | Boot release notes and modular starters |
| 13 | spring-boot source at tag v4.1.1: `WebClientCustomizerHttpServiceGroupConfigurer.java`, `ReactiveHttpServiceClientAutoConfiguration.java`, `TomcatWebServer.java` | HTTP service groups, keep-alive |
| 14 | https://docs.spring.io/spring-framework/reference/{integration/rest-clients,web/webmvc-versioning,core/resilience,core/null-safety,testing/resttestclient}.html (7.0.9) | Framework 7 features |
| 15 | spring-framework source at tag v7.0.9: `BeanReference.java`, `BeanFactoryResolver.java`, `build.gradle`, `gradle/spring-module.gradle`, `framework-docs/.../overview.adoc`, `core/aop/proxying.adoc`, `core/beans/classpath-scanning.adoc`, `core/resources.adoc` | SpEL failure chain, NullAway use, JPMS docs |
| 16 | https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html (7.1.1); spring-security source at tag 7.1.1 `core/.../authorization/method/ExpressionUtils.java` and `access/expression/ExpressionUtils.java` | Method security |
| 17 | https://docs.openrewrite.org/reference/gradle-plugin-configuration · /recipes/java/changepackage · /changetype · /shortenfullyqualifiedtypereferences · /migrate/upgradetojava25 · /spring/boot4/upgradespringboot_4_0-community-edition · /testing/junit6/junit5to6migration (raw text in `70-research-or-*.txt`); openrewrite/rewrite source (main): `ChangeType.java`, `ChangePackage.java`, `YamlApplicationConfigReference`, `PropertiesReference`, `SpringXmlReference`, `ServiceProviderReference` | OpenRewrite |
| 18 | https://errorprone.info/docs/installation; `gh api repos/google/error-prone/releases`; https://github.com/uber/NullAway/wiki/JSpecify-Support; https://github.com/uber/NullAway/wiki/Supported-Annotations; https://github.com/uber/NullAway/releases/tag/v0.14.0; https://github.com/spring-gradle-plugins/nullability-plugin; https://spring.io/blog/2025/11/12/null-safe-applications-with-spring-boot-4/ | Error Prone and NullAway |
| 19 | https://docs.gradle.org/current/userguide/configuration_cache.html · isolated_projects.html · best_practices_structuring_builds.html (9.8.0) | Gradle |
| 20 | https://hibernate.org/orm/releases/7.4/; https://docs.hibernate.org/orm/7.4/whats-new/whats-new.html; https://docs.hibernate.org/orm/7.0/whats-new/whats-new.html; https://docs.hibernate.org/orm/7.4/repositories/html_single/; https://docs.hibernate.org/orm/7.4/javadocs/org/hibernate/annotations/UuidGenerator.Style.html | Hibernate |
| 21 | https://github.com/spring-projects/spring-data-commons/wiki/Spring-Data-2025.1-Release-Notes · Spring-Data-2026.0-Release-Notes | Spring Data |
| 22 | https://www.postgresql.org/about/news/postgresql-18-released-3142/; https://www.postgresql.org/docs/18/release-18.html · ddl-generated-columns.html · functions-uuid.html · sql-createtable.html · sql-createindex.html; https://www.postgresql.org/support/versioning/ | PostgreSQL 18 |
| 23 | npm `web-features` 3.40.0 (`https://registry.npmjs.org/web-features`, tarball `data.json`) | Baseline |
| 24 | https://www.rfc-editor.org/rfc/rfc9745.html | Deprecation header format |
| 25 | GitHub issues: spring-framework #18079, #26159, #30276, #32671; spring-boot #26578, #41203, #41204, #41218 | JPMS position |
| 26 | https://docs.gradle.org/current/userguide/dependency_verification.html · version_catalogs.html · java_testing.html · jvm_test_suite_plugin.html (9.8.0); gradle/gradle #15383 | §4 (a)–(d) |
| 27 | spring-boot source at tag v4.1.1: `build-plugin/spring-boot-gradle-plugin/.../JavaPluginAction.java`; `module/spring-boot-jackson/.../JacksonAutoConfiguration.java`; the tree listing of `*HttpService*`/`*ServiceClient*` classes | §4 (c)(i), §5 (n) |
| 28 | gradle/actions: `setup-gradle/action.yml`, `docs/setup-gradle.md`, `sources/src/cache-service.ts` (main); releases v6.3.0 / v6.4.0; `gh repo view krt-profit/basetool --json visibility` | §4 (e) |
| 29 | https://docs.github.com/en/actions/reference/runners/github-hosted-runners | §4 (f) |
| 30 | flyway/flyway source at tag flyway-13.8.0: `ClassPathScanner.java`, `ChecksumCalculator.java`, `MigrationInfoImpl.java`, `CompositeMigrationResolver.java`; releases list | §4 (g) |
| 31 | https://docs.spring.io/spring-boot/reference/data/sql.html; spring-framework v7.0.9 `framework-docs/.../core/resources.adoc`, `core/beans/classpath-scanning.adoc` | §4 (h) |
| 32 | FasterXML/jackson-databind tag jackson-databind-3.1.5 `src/main/java/tools/jackson/databind/DeserializationFeature.java` | §4 (i) |
| 33 | https://openjdk.org/jeps/483 · https://openjdk.org/jeps/514; Maven Central jars (script `70-research-modinfo.py`) | §4 (j) |
| 34 | spring-framework #29782; v7.0.9 source: `web/service/invoker/RequestPartArgumentResolver.java`, `core/codec/ResourceEncoder.java`, `http/codec/multipart/MultipartHttpMessageWriter.java`, `web/reactive/function/client/ClientRequest.java`, `web/service/registry/HttpServiceProxyRegistryFactoryBean.java`, `HttpServiceGroupConfigurer.java`, `context/i18n/LocaleContextThreadLocalAccessor.java`, `web/context/request/RequestAttributesThreadLocalAccessor.java`; https://docs.spring.io/spring-framework/reference/web/webflux-webclient/client-attributes.html · client-builder.html | §5 (k)–(n) |
| 35 | OpenAPITools/openapi-generator tag v7.25.0 `docs/generators/java.md`, `docs/generators/spring.md` | §5 (o) |
| 36 | micrometer-metrics/context-propagation tag v1.2.1 `ContextSnapshotFactory.java`, `ContextSnapshot.java`, `ContextRegistry.java`; spring-security 7.1.1 `core/src/main/resources/META-INF/services/io.micrometer.context.ThreadLocalAccessor` | §5 (p) |
| 37 | docker/metadata-action `README.md`, `src/meta.ts` at v6.2.0 | §5 (q) |
| 38 | containers/skopeo `docs/skopeo-inspect.1.md`, `docs/skopeo.1.md` (main); release v1.24.1 | §5 (r) |
| 39 | https://docs.oracle.com/javase/specs/jls/se25/html/jls-14.html (§14.11.1); https://openjdk.org/jeps/456 | §5 (s) |
| 40 | web-features 3.40.0 `data.json` (script `70-research-browserfloor.py`) | §6 (1)(2)(5) |
| 41 | https://w3c.github.io/trusted-types/dist/spec/ (§3.4); https://w3c.github.io/webappsec-csp/ (§5.5); https://developer.mozilla.org/en-US/docs/Web/API/SecurityPolicyViolationEvent/disposition | §6 (3) |
| 42 | https://html.spec.whatwg.org/multipage/scripting.html · webappapis.html · interaction.html (§6.3.1) | §6 (4)(5) |
| 43 | stylelint/stylelint `lib/rules/no-unknown-custom-properties` and `CHANGELOG.md` at 17.15.0; spring-framework v7.0.9 `spring-webmvc/.../web/servlet/resource/` tree | §6 (6) |

### B. Commands and scripts (run 2026-09-29; the scripts are in the scratchpad with prefix `70-research`)

| # | What | Command or script → result |
| --- | --- | --- |
| B-1 | Release dates | `gh api "repos/<owner>/<repo>/releases?per_page=8" --jq '.[] \| "\(.tag_name)\t\(.published_at)"'` for spring-modulith, jmolecules, jmolecules-integrations, ArchUnit, error-prone, NullAway, rewrite-gradle-plugin, gradle, spring-boot, spring-framework, spring-security, tbroyer plugins, nullability-plugin |
| B-2 | Boot-managed versions | `curl …/spring-boot-dependencies/4.1.1/…pom` then `grep -nE "<(spring-framework\|hibernate\|…)\.version>"` → `70-research-boot-4.1.1-deps.pom` |
| B-3 | OpenRewrite availability | `curl` Maven Central `rewrite-java`, `rewrite-java-25`, `rewrite-recipe-bom` and Plugin Portal `org.openrewrite.rewrite.gradle.plugin` `maven-metadata.xml` → 8.90.4 / 8.90.4 / 3.38.0 / 7.41.0 |
| B-4 | Raw page text | `python 70-research-fetch.py <url> <name> <regex…>` → `70-research-<name>.txt` |
| B-5 | Baseline table | `70-research-baseline.py 70-research-wf/package/data.json` (web-features 3.40.0) |
| B-6 | JEP 500 exposure | `grep -rF "ReflectionTestUtils.setField" --include=*.java <module>/src \| wc -l` → backend 17, frontend 16, ingest 4, others 0. `grep -rnE "\.setAccessible\(true\)\|Field\.set\(" --include=*.java */src/main` → 0 |
| B-7 | Framework-usage counts (main code) | `grep -rF "<token>" --include=*.java <module>/src/main \| wc -l`: backend `ApplicationEventPublisher` 19, `@TransactionalEventListener` 4, `@EventListener` 6, `@Async` 16, `@Version` 70, `@Retryable`/`RetryTemplate`/`@ConcurrencyLimit`/`@EnableResilientMethods`/`HttpServiceProxyFactory`/`@HttpExchange`/`@SoftDelete`/`StatelessSession` 0; frontend `@Version` 4, `@EventListener` 5 |
| B-8 | UUID generation | `grep -rF "<p>" --include=*.java --include=*.sql backend/src/main \| wc -l`: `GenerationType.UUID` 104, `GenerationType.IDENTITY` 1, `@UuidGenerator` 0, `gen_random_uuid` 44, `uuidv7` 0 |
| B-9 | Modulith runtime footprint | `python 70-research-pomtree.py org.springframework.modulith:spring-modulith-starter-jpa:2.1.1` (also `-starter-core` and `-starter-test`) → ArchUnit 1.4.2 reached through `starter-core` → `spring-modulith-core` |
| B-10 | Flyway migrations | `ls backend/src/main/resources/db/migration/V*.sql \| wc -l` → 256; highest `V258` |
| B-11 | WebClient construction | `Grep "WebClientCustomizer\|WebClient\.builder\(\)\|…"` over `*/src/main/java` → 4 `WebClient.builder()` in `WebClientConfig.java` (473, 521, 549, 571), no `WebClientCustomizer` |
| B-12 | SpEL bean-name tests | `Grep "BeanReference\|parseExpression\|SpelExpressionParser\|BeanFactoryResolver"` over `*/src/**/*.java` → 4 hits, all in `JacksonRecordTest.java` (not security) |
| B-13 | String-bound names | `grep -rhoE "T\(de\.greluc[^)]*\)" --include=*.html frontend/src \| sort \| uniq -c` → 172 × `frontend.support.Roles` in 22 files. `grep -rhoE "\"\.\.[a-z]+(\.[a-z]+)*\.\.\"" --include=*.java */src/test \| wc -l` → 47. `grep -rcE "\"de\.greluc\.krt\.profit\.basetool\.[a-zA-Z.]*\"" --include=*.java …` → 54 (37 in backend `ArchitectureTest.java`). The same package pattern in `*.yml`/`*.xml`/`*.properties` → 0 |
| B-14 | GC and JVM flags | `grep -rnoE "UseG1GC\|UseSerialGC\|UseCompactObjectHeaders\|…"` → backend and frontend G1 (`quadlet/env.d/backend.env.tmpl:42`, `frontend.env.tmpl:21`), ingest Serial (`ingest.env.tmpl:25`), compact headers everywhere |
| B-15 | Checkstyle bump date | `git log -S'checkstyle = "14.3.0"' --format='%h %ad %s' --date=short -- gradle/libs.versions.toml` → `ba80b2a7d 2026-09-27`; the ADR-0223 commit `016567468` is from 2026-09-27 |
| B-16 | JPMS manifests | Download `spring-core-7.0.9.jar`, `spring-boot-4.1.1.jar` and `spring-modulith-core-2.1.1.jar`, then list `module-info.class` and read `MANIFEST.MF` with Python `zipfile` → no `module-info`; `Automatic-Module-Name` present |
| B-17 | Sept-audit cross-reference | `python 70-research-septgrep.py sept_audit_findings.json` → related findings FE-SIMP-02 (HTTP interfaces), BE-SIMP-02 (sunset), BE-PERF-02 (paged collection fetch), BE-SIMP-10 (fully-qualified names; OpenRewrite), BLD-PERF-04 (configuration cache). None mention Modulith, jMolecules, Error Prone/NullAway, JPMS, PG 18 or Baseline |
| B-18 | Isolated Projects inventory | `grep -cE "rootProject\.(file\|fileTree\|layout\|extra)"` per build script → root 5, backend 7, frontend 13, test-support 2. `grep -rnE "allprojects\|subprojects\|evaluationDependsOn"` → `build.gradle.kts:14,122` only |
| B-19 | CI Gradle action | `grep -rnE "gradle/actions/setup-gradle\|cache-encryption-key"` → `.github/actions/setup-jdk-gradle/action.yml:33` (`9c971963…` = v6.3.0), no encryption key. `gh api repos/gradle/actions/tags` → tag mapping |
| B-20 | Jar directory entries | Python `zipfile` on `logging-support/build/libs/logging-support-0.0.1-SNAPSHOT.jar` → 12 entries, 7 directories |
| B-21 | Jackson override | `grep -rnE -i "fail-on-unknown-properties\|FAIL_ON_UNKNOWN_PROPERTIES\|use-jackson2-defaults"` over `*/src/main` → only `MissionWriteController.java:149` |
| B-22 | Image JVM line | `grep -nE "add-opens\|add-exports\|javaagent\|AOTCache\|extract\|ENTRYPOINT" docker/app/Dockerfile` → extract at :46, `ENTRYPOINT` at :125, no `--add-opens` |
| B-23 | `module-info` survey | `python 70-research-modinfo.py` → the table in §4 (j) |
| B-24 | Browser floor | `python 70-research-browserfloor.py 70-research-wf/package/data.json` → the tables in §6 |

### C. Items left UNKNOWN, and what would settle them

| Item | Settles it |
| --- | --- |
| Binary compatibility of Spring Modulith 2.1.1 (built on ArchUnit 1.4.2) with ArchUnit 1.5.1 | Run `ApplicationModules.of(...).verify()` in a backend test |
| Whether `fail_on_pagination_over_collection_fetch` still throws on Hibernate 7.4.5 | A characterisation test with one paged collection fetch |
| Whether TypeScript 7 accepts `lib: ES2024/ES2025` | Bump `frontend/tsconfig.json` and run `:frontend:typecheckJs` |
| Configuration-cache compatibility of `net.ltgt.errorprone`/`nullaway` | `./gradlew help --configuration-cache` run twice on a trial branch |
| NullAway and Lombok with `lombok.addNullAnnotations = jetbrains` | Trial on `logging-support` or `ingest` |
| Whether Checkstyle 14.3.0 treats JEP 467 `///` comments as Javadoc | Run Checkstyle on a sample file |
| Which of the 37 `ReflectionTestUtils.setField` targets are `final` | Test run with `--illegal-final-field-mutation=deny` on JDK 26+ |
| Whether PG 18 virtual generated columns can be indexed | `CREATE INDEX` on a PG 18 Testcontainer |
| An official Spring Boot statement on JPMS | None found; the Boot team would have to state it |
| Whether `@Cacheable` is applied to an `HttpServiceProxyFactory` proxy bean (§5 (k)) | A test with `@EnableCaching` and a MockWebServer request count |
| Whether the #15383 catalog workaround is compatible with the configuration cache or Isolated Projects (§4 (b)) | Trial in `build-logic`, then `--configuration-cache --isolated-projects` |
| Whether web-features' nesting versions reflect the relaxed syntax (§6 (5)) | BCD `css.selectors.nesting` notes |
| Whether google-java-format and Checkstyle accept `case A _, B _ ->` (§5 (s)) | `spotlessApply` and `checkstyleMain` on a sample |
| Whether production PostgreSQL roles use SCRAM (MD5 is deprecated in 18) | Read-only `SELECT rolname, rolpassword LIKE 'SCRAM%' FROM pg_authid` needs superuser; instead check `password_encryption` and `pg_hba.conf` read-only on the host |
