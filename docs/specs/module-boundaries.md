> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-10-05.
> **Owner area:** MOD · **Related ADRs:** ADR-0231, ADR-0233

# Backend module boundaries

## Context & goal

The [domain modularisation plan](../DOMAIN_MODULARISATION_PLAN.md) (decisions D-01 and D-02,
§5.1 and §5.7) turns the backend into a modular monolith: one package per domain inside the
`backend` Gradle project, boundaries enforced by tests. Before any class moves, the target module of
every class is written down, the coupling that exists today is frozen in a baseline that may only
shrink, and Spring Modulith is wired in test scope. This is guard G-09 of plan §6.1 and step 0.3 of
§7.2. It stops new cross-domain coupling from the day it lands and measures the progress of every
later move.

The baseline freezes **module coupling only**. A security or structural rule is never frozen
(plan §6, red lines); those stay hard ArchUnit rules that fail on the first violation.

## Requirements

### REQ-MOD-001 — The domain map is the source of truth for every class's target module

[`backend/src/test/resources/architecture/domain-map.txt`](../../backend/src/test/resources/architecture/domain-map.txt)
assigns every top-level backend production type to exactly one module. It is line-oriented:

- `base <package>` names the root package;
- `module <name> <rank>` declares a module and its rank — the 25 modules of plan §5.1 plus the
  transitional `privacy` (rank 13), which exists only until the GDPR classes are dissolved into
  identity-owned SPIs (plan §7.6);
- `allow <from> <to>` permits one dependency between two modules of the same rank;
- the ordered rules `class <SimpleName>`, `package <sub.package>` (the package and everything below
  it), `name <regex>` (matched at the start of the simple name, a trailing `Impl` ignored) and
  `layer <first-segment>` assign a class to a module; **the first matching rule wins**. Each rule
  may carry a free-text reason after the module.

A nested, local or anonymous class belongs to its top-level class, and a generated MapStruct
`XMapperImpl` to the `@Mapper` type `XMapper` it implements. The rules are a faithful port of the
audit's classification (`docs/archive/domain-modularisation-audit-2026-09/scripts/10-backend-domains-classify.py`)
with the behaviour-free re-homings of plan §7.3 applied (the leadership writes into `orgunit` and
the Leitung view into `orgchart` — corrected 2026-10-04, the view was first mapped to `orgunit` —,
the access core into `platform`, the scope kernel into `scope`, the 31 GDPR classes into `privacy`,
`HandleAnonymisation` into `kernel`, `PayoutPreference` into `identity`, the composition root into
`app`). Changing a class's target module is a reviewed edit of this file. Once a module's package
exists, a `package` rule assigns its tree (for example `audit`, `kernel`, `orgunit`) and the
`class` rules of the classes that moved into it are removed.

**Acceptance**

- [x] The map parses: one `base`, every module declared once with a non-negative rank, every rule
      names a declared module, at most one `class` rule per class, every `name` pattern compiles.
- [x] Today's 1,390 source types are assigned to 26 modules: the 1,389 of the audit with the sizes
      of the [evidence appendix](../modularisation/evidence.md#target-modules-and-ranks), plus
      `InventoryMergeCandidatesDto`, which the `name ^(Inventory|…)` rule assigns to `inventory`.

**Enforced by:** `DomainMapTest`, `DomainMapChecksTest` · **Code:** `DomainMap`, `ModuleSubjects`
(test source set, package `…backend.architecture`)

### REQ-MOD-002 — Every class belongs to exactly one module; no dead rule; unique simple names

Guard G-09: a backend production class that no rule assigns fails the build, with a message naming
the class and the file to add a `class` or `name` rule to. A rule that assigns no class — because
nothing matches it or an earlier rule takes every class it matches — fails the build, and so does a
declared module that owns no class. The simple names of all top-level backend production classes,
generated MapStruct implementations included, are unique: Spring derives bean names and springdoc
derives schema names from them, and the module baseline names classes by simple name.

**Acceptance**

- [x] At least 1,389 source types and at least 1,438 top-level classes (1,389 plus 49 MapStruct
      implementations) are checked — a selection floor, so an emptied import fails.
- [x] A planted unassigned class, a planted dead rule, a planted empty module and two planted
      classes sharing a simple name are each reported (`architecturefixture.domainmap`).

**Enforced by:** `DomainMapTest`, `DomainMapChecksTest`

### REQ-MOD-003 — A module depends only on lower ranks and on its allow rows

An ArchUnit `modules()` rule over the domain map checks every class dependency between two modules:
module A may depend on itself, on every module of lower rank, and on a same-rank module named by an
`allow` row. Allow rows exist only between two different modules of the same rank, never in both
directions; today they are `platform → kernel`, `joborder → materialexchange` and
`refinery → joborder`. A dependency upward is inverted through an SPI or an observer (plan §5.3)
rather than allowed.

A violation is reported once per class edge, folded onto source types, as
`<from-module> -> <to-module>: <FromClass> -> <ToClass>`. Simple names keep a reported edge stable
across a package move; there are no line numbers.

**Acceptance**

- [x] A planted low-ranked fixture class that depends on a high-ranked one is reported as exactly one
      violation.
- [x] Ranks and allow rows decide the verdict in both directions.

**Enforced by:** `ModuleBaselineTest`, `DomainMapChecksTest` · **Code:** `ModuleDependencyRule`

### REQ-MOD-004 — Today's violations are frozen and may only shrink

The rule of REQ-MOD-003 runs wrapped in ArchUnit's `FreezingArchRule`. Its violations are stored in
`backend/src/test/resources/architecture/module-baseline/` (the backend's rule:
[`backend-modules-respect-the-ranks-of-the-domain-map.txt`](../../backend/src/test/resources/architecture/module-baseline/backend-modules-respect-the-ranks-of-the-domain-map.txt)),
one file per rule, one violation per line, sorted, UTF-8, LF, with no header and no index file
(`ModuleBaselineStore`, configured in `backend/src/test/resources/archunit.properties`:
`freeze.store.default.allowStoreCreation=false`, `freeze.refreeze=false`). Lines are matched exactly.

- A violation that is not in the baseline fails the build.
- A violation that no longer occurs shrinks the baseline file on a local run; the change commits the
  shrunk file. In CI (environment variable `CI=true`) the store refuses the rewrite and the build
  fails until the shrunk file is committed, so the committed baseline always equals today's
  violations.
- A missing baseline is never created silently.
- Only module coupling is frozen. A security rule never enters a baseline.

Measured on 2026-10-02: **138 class edges in 42 module pairs**. The plan's 150 edges in 48 pairs
were measured with `jdeps` at `95e945326`; the difference is the tool, not the code (the only
source type added since, `InventoryMergeCandidatesDto`, adds no violation): `jdeps` also counts the six permitted subclasses of the
sealed `AppException` (ArchUnit does not import a `PermittedSubclasses` attribute as a dependency)
and nine types that appear only in the descriptor of a called member or in an inlined constant,
while ArchUnit additionally sees one annotation class value (`@Mapper(uses = SquadronMapper.class)`
in `UserMapper`) and the two `kernel → platform` edges of `AppException` and `AppExceptionKind` to
`ErrorDisclosurePolicy`, which the plan's count left out as same-rank edges. Those two left on
2026-10-04, when the domain map assigned `ErrorDisclosurePolicy` to the kernel with the rest of the
exception contract (ADR-0235): **136 edges**.

Shrunk the same day to **127 class edges in 37 module pairs** by the Phase 1 re-homings of plan
§7.3: the exchange row records nested in the repositories that produce them (−5), the catalogue
`ShipTypeMapper` (−1), the org chart behind the org-unit module's `MembershipChangeObserver` (−2)
and `AuthHelperService` without its delegations to `OwnerScopeService` (−1).

After the platform SPIs (plan §7.3, P1-8, 2026-10-04): **112 class edges in 30 module pairs**.

After the `support` split (plan §7.3, P1-9, 2026-10-04): **110 class edges in 29 module pairs**.
`ClientAttribution` asks the platform's `ClientDirectory` SPI, which the exchange implements,
instead of reading `IngestGatewayProperties` and `KnownExchangeClients` itself (−2, the
`platform -> exchange` pair). The split moved `RequestMemo`, `Roles`, `Permissions`,
`ProblemResponseFactory` and `AppProblemProperties` from `platform` to `kernel`; that added no
edge, since every module may depend on either.

**Acceptance**

- [x] The backend's frozen baseline equals the rule's current violations.
- [x] A violation missing from the baseline fails; a frozen one passes.
- [x] A fixed violation shrinks the file when updates are allowed and fails when they are refused.
- [x] A missing baseline fails when creation is disabled; a created one is sorted and LF-terminated.

**Enforced by:** `ModuleBaselineTest` · **Code:** `ModuleBaselineStore`

### REQ-MOD-005 — Spring Modulith verifies the declared modules, in test scope only

Spring Modulith 2.1.1 (`spring-modulith-core` and `spring-modulith-docs`, Apache-2.0) is a
`testImplementation` dependency of the backend. It runs on the project's ArchUnit 1.5.1, against
which it was not compiled (it declares 1.4.2). Module detection is `explicitly-annotated`
(`spring.modulith.detection-strategy` in the backend's `application-test.yml`): only a package whose
`package-info` carries `@ApplicationModule` is a Modulith module. `ModularityTest` asserts that the
detected module set equals its declared list (REQ-MOD-006) and that `ApplicationModules.verify()`
passes. The `Documenter` output is written to `backend/build/spring-modulith-docs` and is not
committed.

The annotation types come from `spring-modulith-api`, a `compileOnly` dependency of the backend's
main source set (ADR-0233 amendment 1); the engine (`core`, `docs`) stays `testImplementation`.
Nothing of Spring Modulith reaches the backend's `runtimeClasspath`, its boot jar, its image or its
SBOM. Its event publication registry is not used (plan §10).

**Acceptance**

- [x] The backend's detected module set equals the declared list and `verify()` passes.
- [x] A planted fixture with two `@ApplicationModule` packages, one reaching into the other's
      `internal` package, is reported by `detectViolations()` and fails `verify()`; an unannotated
      sibling package is not detected as a module.
- [x] `:backend:dependencies --configuration runtimeClasspath` lists no Spring Modulith, Structurizr
      or ArchUnit artefact, and the boot jar holds no `spring-modulith-*` jar.

**Enforced by:** `ModularityTest`

### REQ-MOD-006 — Every module package declares itself, its `api` and its allowed dependencies

A first-level backend package named after a module of the domain map is a module package, and it is
declared in the same pull request that creates it (plan §5.2, §5.7, step P1-13):

- its `package-info` carries `@ApplicationModule` — closed, never `OPEN`;
- its `api` package and **every package below `api`** carry `@NamedInterface("api")`; Spring
  Modulith merges them into the module's one named interface, because a package-level
  `@NamedInterface` covers its own package only. Everything outside `api` is internal to the
  module;
- its `allowedDependencies` lists exactly the `<module>::api` of every **declared** module the domain
  map lets it depend on (lower rank, or an `allow` row): `audit` and `notification` (rank 1) allow
  nothing. The list is derived, not chosen: a module that becomes declared enters the lists of the
  modules above it in the same pull request, since Spring Modulith rejects an allowed dependency on
  an undeclared module.

A module without an `api` package keeps all its types in its base package, which is its unnamed
interface: `kernel` today. A module that publishes nothing yet keeps every type below its base
package and outside an `api` package, so it exposes no type at all; no declaration allows it,
and Spring Modulith reports any module that reaches into it: `mission` today, until its module
API exists (plan §7.5), and `dashboard` and `orgchart`, which no other module uses. Its dependents allow it by bare name (`"kernel"`, not
`"kernel::api"`), it publishes no named interface, and no type lies below its base package. A
module that may depend on no declared module says so with `allowedDependencies = {}`: the
annotation's default is Spring Modulith's "everything allowed" sentinel, not an empty list.

Declared on `main`: `admin`, `audit`, `bank`, `catalogue`, `dashboard`, `exchange`, `identity`, `inventory`,
`joborder`, `kernel`, `livesync`, `materialexchange`, `mission`, `notification`, `orgchart`,
`orgunit`, `personalinventory`, `platform`, `privacy`, `refinery`, `scope` (floor 21). Every `<module>.web` and
`<module>.internal` package is internal to its module.

A domain whose services return its REST DTOs keeps those DTOs in `<module>.internal`, beside the
services, so `web` depends on `internal` and never the other way: the layers inside a module stay
acyclic (ADR-0047) until the module API returns its own records (plan §5.2). `orgchart`, `admin` and
`personalinventory` are laid out this way.

**Acceptance**

- [x] The module packages found in the compiled backend equal `ModularityTest.DECLARED_MODULES`,
      which equals the detected Modulith modules, with a floor of 21.
- [x] Each declared module's only named interface is `api`, and it contains every top-level type
      of the module's `api` package tree and nothing outside it — except `kernel`, whose types all
      lie in its base package, and a module that publishes nothing, which has no named interface
      and no type in its base package or an `api` package (a check run against the kernel proves
      it fails).
- [x] Each declaration's `allowedDependencies` equals the set the domain map derives.
- [x] A planted fixture shaped like a backend module proves that a module reaching past another's
      `api` into its `internal` package, and a module depending on an `api` its declaration does not
      allow, are both reported and fail `verify()`, while a module using the `api` and a
      sub-package of it annotated into the same interface is not reported.

**Enforced by:** `ModularityTest` · **Fixture:** `architecturefixture.modulithapi`

## Out of scope

- Moving classes into module packages — the later phases of the plan; each move adds its module's
  declarations under REQ-MOD-006.
- The re-keyed security and structural ArchUnit rules (G-01) and the other guards of plan §6.1.
- Table ownership and native SQL across modules (G-10).

## Open questions

- Plan §5.1 leaves open whether `inventory` and `mission` swap ranks or the inventory → mission edges
  (12 today) are inverted through the earmark SPI; either is an edit of the domain map and shrinks the
  baseline.
