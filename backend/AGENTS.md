# AGENTS.md — backend

Backend rules, on top of the [root `AGENTS.md`](../AGENTS.md). Each section summarises a reference
file under [`docs/agents/`](../docs/agents/README.md); **read that file before the work it covers.**

## Module map — every class belongs to one target module

Full rules: [`docs/agents/backend-module-map.md`](../docs/agents/backend-module-map.md) — read it
before adding a class, a package or a dependency between modules (ADR-0231, REQ-MOD-001…006).

- **A new class must match a rule** of `src/test/resources/architecture/domain-map.txt`
  (`DomainMapTest`); **the coupling baseline in `module-baseline/` only shrinks**
  (`ModuleBaselineTest`), and a security rule never goes into a baseline.
- **Other modules use a module only through its `api` package** (the audit trail through
  `audit.api.AuditRecorder`); **a lower module reacts upward only through an observer it owns**
  (`@ObserverSpi`, implementation `@Transactional(propagation = MANDATORY)`).
- **There is no `support` package**: a domain-free helper goes into `kernel`, the access core into
  `platform.api` / `platform.internal`, a domain's helper into `<module>.internal` or `.api`; every
  leaf helper is listed in `ArchitectureTest.LEAF_HELPER_CLASSES`.
- **A domain event goes into its publisher's `<module>.api.events` package**; payloads are data
  only.
- **The platform modules (`audit`, `notification`, `livesync`, `platform`) never name a domain
  class**; they own an SPI the domain implements. A live-sync room the backend publishes into must
  exist in the frontend's `LiveSyncTopicClass` (`LiveSyncTopicRegistryParityTest`).
- **A Spring Data fragment `Impl` lies beside its interface** (`RepositoryFragmentPlacementTest`).
- **No field is `@Autowired`**, except a MapStruct abstract mapper's package-private setter.
- **A new module package gets its Spring Modulith declarations in the same PR** (REQ-MOD-006):
  `@ApplicationModule(allowedDependencies = …)`, `@NamedInterface("api")` on `api` and every package
  below it, the entry in `ModularityTest.DECLARED_MODULES`.
- After a move, grep for `../backend`: a test that reads backend sources by a concatenated path is
  not found by a text scan.

## Concurrency — read this before touching multi-step transactions

Full rules: [`docs/agents/backend-concurrency.md`](../docs/agents/backend-concurrency.md). They
exist because of real bugs that shipped; **read the file before any multi-step transaction, version
check, bulk update or find-or-create.** The rules it holds:

- **Version checks go through the `kernel.OptimisticLock` family** (`check`,
  `checkOptionalClient`, `checkRequired`, picked by null-semantics) — never a hand-rolled guard.
  **Exception:** `Mission`'s manual section counters are bumped by one DB-enforced conditional
  `UPDATE` through `MissionSectionVersions.enforceSectionVersion(...)`, safe only because every
  mutable `Mission` field is `@OptimisticLock(excluded = true)` and the entity `@DynamicUpdate`.
- **Mission and job-order edits go through their edit module** (`MissionEditor`,
  `JobOrderEditor`; ADR-0287, REQ-SEC-085) — never open-code load, access check, version check and
  audit.
- **Fine-grained locking, backend side**: per-section counters independent of `@Version`
  (`Mission`, `Ship.owningOrgUnitVersion`, ADR-0258); each form writes the smallest entity.
- **Pessimistic locking for bulk reorders** (`PESSIMISTIC_WRITE` or atomic SQL).
- **Exchange stock lots take their advisory locks first**, all up front in ascending key order,
  through `StockCommands.lockLots` / `PoolEntryCommands.lockLots` (ADR-0229).
- **`…WithinTransaction` pattern** for an intra-transaction call on the same entity
  (`@Transactional(propagation = MANDATORY)`, no own `save()`/`flush()`).
- **Never run a `clearAutomatically` bulk update inside a loop** over items of one aggregate;
  collect the ids and run it once after the loop.
- **Find-or-create races retry in a fresh `REQUIRES_NEW` transaction** through a self-proxy, with a
  bounded attempt count and an explicit 409 branch in the frontend proxy.

The frontend half is in [`frontend/AGENTS.md`](../frontend/AGENTS.md).

## Tests

Full rules: [`docs/agents/backend-testing.md`](../docs/agents/backend-testing.md).

- **Wire a real collaborator through the constructor** (`new X(...)` in `@BeforeEach`), never with
  `@InjectMocks` plus `ReflectionTestUtils.setField` / `Field.set` on a final field (JEP 500).
- **Reuse a Spring test context, do not open one** (REQ-OPS-041, `TestContextBudgetTest`, budget
  36): plain `@SpringBootTest` without a mock, `@LeafServiceMockTest` for a leaf-service mock, and
  the field name the other classes already use.
