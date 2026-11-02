# Backend module map

Read this before adding a class, a package or a dependency between backend modules.

The backend is being cut by domain (ADR-0231, `docs/archive/DOMAIN_MODULARISATION_PLAN.md`). Until the
domain packages exist, `src/test/resources/architecture/domain-map.txt` assigns every main class to
its target module, first matching rule wins (REQ-MOD-001…006).

- **A new class must match a rule.** `DomainMapTest` fails on an unassigned class, a dead rule, an
  empty module and a duplicate simple name. Add an explicit `class` rule with its reason when no name
  or package rule fits.
- **The coupling baseline only shrinks.** `module-baseline/` freezes today's rank-violating class
  edges. A new upward edge fails `ModuleBaselineTest`; when you remove one, a local test run shrinks
  the file and CI refuses until the shrunk file is committed. Never add a security rule to a
  baseline.
- **Other modules use a module only through its `api` package** (plan §5.2). The first one is
  `audit.api`: record audit rows through `AuditRecorder` (it returns nothing), with `AuditEventType`,
  `AuditDomain` and `AuditDetails` from the same package. `AuditService` and the rest of the audit
  trail are the audit module's internals; the listener and controller audit rules key on all three
  recorder types, so a new recorder type is added there too.
- **A lower module reacts upward only through an observer it owns** (plan §5.3), for example
  `orgunit.api.FirstMembershipObserver` or `catalogue.api.MaterialDeletionObserver`. An
  implementation is `@Transactional(propagation = MANDATORY)`
  and the interface carries `@ObserverSpi` (`ListenerAndObserverRulesTest` fails otherwise); never
  call a higher module's service from `orgunit` directly.
- **There is no `support` package** (split 2026-10-04, plan §7.3 P1-9). A domain-free helper
  goes into the `kernel` base package (`OptimisticLock`, `StringNormalization`, `LikePatterns`,
  `RequestMemo`, `Roles`, `Permissions`, `ProblemResponseFactory`, `HandleAnonymisation`, …;
  the kernel has no sub-packages and depends on no module). The access core and the shared
  request settings go into `platform.api` / `platform.internal`. A helper of one domain goes into
  `<module>.internal`, or `<module>.api` when another module uses it. A helper that reads an entity
  is not domain-free. Every such leaf helper is listed in `ArchitectureTest.LEAF_HELPER_CLASSES`,
  which allows it only the other leaf helpers, the entity model and the repositories — add a new
  one there and raise the floor of `leafHelpersMustStayDependencyLeaves` by the measured count.
- **A domain event goes into its publisher's `<module>.api.events` package** — the module of the
  service that calls `publishEvent` with it; there is no central `event` package. A
  notification-producing event implements `notification.api.events.NotificationEvent`. A new
  module package needs a `package <module> <module>` rule in the domain map. Event payloads are
  data only: `eventLayerShouldNotDependOnServiceLayer` selects every `api.events` package tree.
- **The platform modules (`audit`, `notification`, `livesync`, `platform`) never name a domain
  class.** When they need a domain's data, they own an SPI and the domain implements it (plan
  §5.3): `ActorHandleResolver` and `RetentionParticipant` (audit), the three
  `*RecipientDirectory` interfaces (notification), `ActiveOrgUnitProvider` (platform). A new
  audit trail outside the audit module joins retention as a `RetentionParticipant`. The
  `livesync` module owns no SPI any more: the `LiveSyncTopicAuthorizer` seam went with the app's
  subscribe path (ADR-0274), because the backend publishes into the frontend's rooms and authorises
  no subscriber of its own. A domain announces its own committed write through
  `livesync.api.LiveSyncPublisher`, and a room it publishes into must exist in the **frontend's**
  `LiveSyncTopicClass` with the same prefix, arity and section keys —
  `LiveSyncTopicRegistryParityTest` fails otherwise, and a key only the backend knows is a frame the
  browser clips away on arrival. A platform SPI
  in a layer package sits in a package its implementations already live in (`ActiveOrgUnitProvider`
  in `service`, not `logging`), or the implementation closes a layer cycle.
- **A Spring Data fragment implementation lies in the package of its fragment interface.** Spring Data looks
  the `Impl` up beside the interface; when a move splits them the method is silently derived from its name as a
  query (and auto-flushes). `RepositoryFragmentPlacementTest` pins it.
- **No field is `@Autowired`.** `ArchitectureTest.noFieldIsAutowired` rejects every one, and `ModularityTest`
  separately rejects field injection across modules. A MapStruct abstract mapper takes each collaborator — of
  its own module or another — through a package-private `@Autowired` setter.
- **A test that reads backend sources by path** (the frontend's `NotificationTypeMessageCoverageTest` does) is not found by
  a move's text scan when the path is split over a string concatenation; grep for `../backend` after a move.
- **A new module package gets its Spring Modulith declarations in the same PR** (REQ-MOD-006,
  ADR-0233 amendment 1). Its root `package-info` carries `@ApplicationModule(allowedDependencies =
  {…})`, its `api` package **and every package below `api`** carry `@NamedInterface("api")` (a
  package-level `@NamedInterface` does not cover sub-packages), and the module is added to
  `ModularityTest.DECLARED_MODULES`. `allowedDependencies` is derived, not chosen: the `"<m>::api"`
  of every declared module the domain map's ranks and `allow` rows permit — a bare `"<m>"` admits
  only the unnamed interface. Declaring a module also adds it to the lists of the declared modules
  above it. `ModularityTest` fails until all of this matches. The annotations come from
  `spring-modulith-api`, `compileOnly`; never move Spring Modulith's engine out of test scope.
