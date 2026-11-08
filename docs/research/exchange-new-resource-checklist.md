# What a new Exchange resource needs beyond its endpoints

> **Doc type:** Research note (historical) — the answer to issue
> [#3131](https://github.com/krt-profit/basetool/issues/3131), part of map
> [#3128](https://github.com/krt-profit/basetool/issues/3128) (direct refinery-order exchange).
> Read on `main` at `1834223b3` (2026-11-08). The living truth stays in
> [`docs/specs/external-exchange.md`](../specs/external-exchange.md) and the code; this note is a
> map into them, not a second copy.

## Question

What does the existing Exchange framework give a new resource for free, and what must the
refinery-order resource (`exchange.refinery.read` / `.write`, a cursor feed with tombstones, change
writes with an expected version, a client key per order, a client marker, cancel and hard delete)
add itself?

## Short answer

The **request pipeline** is generic: DPoP, the registry and scope gate, client attribution in the
audit, the idempotency cache, the daily write quota, the Redis byte budget, the batch cap, the
large-set bulkhead, schema validation of request and answer, problem+json, the mass-change staging
and confirmation flow, the nightly purge of feed and journal, and every gateway metric. A resource
inherits all of that by being **one row in `ExchangeRoutes`** plus one relay method.

Everything that knows *what* a resource is, is **per resource and closed by enums, CHECK
constraints and pinned counts**, so it must be added: the capability enum and its DB CHECK, the
Keycloak scopes and consent texts, the `ExchangeResource` constant (with its mass-change name,
write capability and metric tag), the feed triggers on the domain tables, the change-set adapter
(plan, removal rule, execution), the typed journal state and its undo restorer, the journal action
values, the confirm and undo dispatch, the schemas and fixtures, the OpenAPI documents, the seam
declaration, the golden answers, the developer pages, the specs and companion documents, and the
monitoring's `resource` label value. The closest precedent is the org stock (REQ-XCH-042…046,
ADR-0257), added in four PRs: read #2623 (`a67c39463`), feed #2624 (`7d8104911`), write #2626
(`8f855ace0`), undo and guard #2631 (`f4fc34b2d`). Their file lists are the empirical checklist
below; a new resource of the same shape touched 28–132 files per step.

Four findings constrain the refinery design directly:

1. **Only `GET` and `POST`, only literal paths.** The ingest bot filter admits nothing else
   (REQ-XCH-001), and every acting path is a literal (REQ-SEC-080; `ActingMemberFilter` lists 18
   exact paths). Cancel and hard delete are therefore **ops in a change set**
   (`POST …/refinery-orders/changes`), not `DELETE /…/{id}`. A selector goes into a query parameter,
   as `org-stock/members?unit=` does.
2. **The refinery module has no hard delete today.** Cancel is a soft delete to `CANCELED`
   (`REFINERY_ORDER_CANCELED`); there is no `REFINERY_ORDER_DELETED` audit event, so a hard delete
   is a new domain capability with its own audit event (REQ-AUDIT-001), not only an exchange op.
3. **No refinery live-sync topic exists in the backend** (`LiveSyncTopicClass` has none, and
   `RefineryOrderService` publishes only `RefineryNotices`). REQ-XCH-030 requires exchange writes
   to appear live; the refinery pages need a topic first or an explicit decision how they follow.
4. **No refinery table carries an `exchange_change` trigger.** The feed must be sequenced in the
   database (REQ-XCH-013, ADR-0224), so the triggers on `refinery_order` and its goods are new
   Flyway work, including the bulk paths that bypass the service (owner reassignment on user
   deletion, `REFINERY_ORDERS_REASSIGNED`; a location or unit change re-announcing its entries).

## A. Reused as is

Each item works for a new resource without code changes, once the route row and relay method of
section B exist.

| Mechanism | Where it lives | Requirement |
| --- | --- | --- |
| DPoP proof, `cnf.jkt` binding, `jti` replay cache, nonce | ingest `ExchangeTokenGateFilter`, DPoP gate | REQ-XCH-006 |
| `basetool-ingest` audience enforced in code | ingest `ExchangeDpopGateTest` | REQ-XCH-004 |
| Registry gate: switch, client `ACTIVE`, scope in token **and** granted, installation and client revocation, minimum client version, extension `Origin` binding | ingest `ExchangeGateFilter`; backend `ExchangeGate` (`@exchangeGate.allows('<scope>', …)`) | REQ-XCH-003, -004, -008, -024, -027 |
| Registry mirror into Redis, fail-closed read, 60 s reconcile | backend `ExchangeRegistryMirrorSync`, ingest `ExchangeRegistryReader` | REQ-XCH-003 |
| Acting member, reduced authentication, own data only | backend `ActingMemberFilter` (needs the new paths, see B) | REQ-XCH-009 |
| Client named in every audit row (`audit_event.client_id`) | `platform.api.ClientAttribution` | REQ-XCH-010, REQ-AUDIT-005 |
| Change-source attribution of every writing transaction (`basetool.change_source` = `client\|<id>\|<installation>`) | backend `ChangeSourceTransactionManager` | REQ-XCH-013 |
| `Idempotency-Key`: 1 h cache per (client, member, key), in-flight lock, replay, `422` on reuse, cached statuses | ingest `ExchangeIdempotencyFilter` | REQ-XCH-020, ADR-0221 |
| Per-minute buckets, daily write quota (500/day, registry override), `RateLimit` headers | ingest `ExchangeLimitFilter`, `ExchangeQuotas` (a route marked `write(...)` counts) | REQ-XCH-023 |
| Optional "every op counts" quota rule | `ExchangeController#changes(…, everyOpCounts, …)` and `ExchangeQuotas#countFurtherOps` | REQ-XCH-023, -045 |
| Redis byte budget (4 MiB member / 64 MiB client / 128 MiB total), `503 EXCHANGE_BUDGET_EXHAUSTED` with computed `Retry-After` | ingest store scripts, `app.exchange.store.*` | REQ-XCH-023, ADR-0221 |
| Batch cap of 500 ops (`413 BATCH_TOO_LARGE`), bulkhead for > 100 ops (`503 RELAY_BUSY`) | `ExchangeController#changes`, `ExchangeRelay#forwardLarge` | REQ-XCH-023 |
| Request and answer schema validation, `SCHEMA_INVALID` with `errors[]`, `UNKNOWN_FIELD` warnings, `502` for an answer breaking its schema | ingest `ExchangeSchemas` | REQ-XCH-011, -026 |
| problem+json, fixed English detail per relayed code, gate-code pass-through | ingest `Problems`, `ExchangeRelay.DETAILS` / `GATE_STATUSES` | REQ-XCH-025 |
| Mass-change staging at the gateway, resend digest, `confirmationUrl`, browser confirmation flow and its re-checks | ingest `HeldMassChanges`, `HandoffStagingService` (keyed by the resource's path segment); `/connected-apps/confirm` | REQ-XCH-021, ADR-0281 |
| Counting rule of the guard (> 25, or ≥ 5 and > 20 % in 24 h) | backend `ExchangeMassChangeGuard` | REQ-XCH-021 |
| Change-set runner: dry run, held set, per-op outcomes, result shape, `basetool_exchange_writes_total` counting | backend `ExchangeChangeSetRunner` (one adapter per resource, ADR-0285) | REQ-XCH-015, ADR-0285 |
| Feed cursor format (`s1.…`/`f1.…`), watermark, horizon, `410 CURSOR_EXPIRED` | backend `ExchangeFeedCursor`, `ExchangeFeedPosition`, `ExchangeFeedHorizon` | REQ-XCH-013, ADR-0224 |
| Tombstone semantics (`removedBy`, `removedAt`, `installationId`), live-tombstone `REMOVED_ELSEWHERE` and `override` | `exchange_change` read by `ExchangeFeedReader` | REQ-XCH-013, -014 |
| Nightly purge of feed and journal after 90 days, horizon, `ScheduledJobStale` | `ExchangeChangeRetentionService` (resource-agnostic) | REQ-XCH-013, -022 |
| Journal table and its retention, Art. 15 export of the journal, erasure coverage of `before_state`/`after_state` | `exchange_journal`, `ExchangeJournalService`, `HandleErasureCoverage` | REQ-XCH-022 |
| Member undo endpoint and admin bulk undo run (executor, suspension first, notification, `exchange_bulk_undo` job metrics) | `ExchangeUndoService`, `ExchangeBulkUndoService` (need a restorer, see B) | REQ-XCH-022, -034 |
| Connected-apps page: clients, installations, disconnect, "Neu" highlight | frontend `/connected-apps` | REQ-XCH-032 |
| Installation retention (90 days), departure handling | `ExchangeConnectionRetentionService`, `ExchangeDepartureService` | REQ-XCH-035, -008 |
| Gateway metrics per client and route (`basetool_ingest_exchange_refused_total`, relay outcome, budget gauges), Loki `client_id`/`route` metadata, blackbox `GET /exchange/v1` → `401`, sign-in surge alerts | ingest `ExchangeRefusals`, `CorrelationIdFilter`; `monitoring/` | REQ-XCH-028, REQ-OBS-011 |
| Third-party Keycloak template: every capability scope optional, consent required, 30/90-day sessions | `scripts/provision-keycloak-realm.py` (reads `EXCHANGE_SCOPES`) | REQ-XCH-005, ADR-0217 |
| `checkExchangeDocs` gate (links, no German, markdownlint, reference preparation) | `frontend/build.gradle.kts` (`:frontend:checkExchangeDocs`), `.github/workflows/exchange-docs.yml` | REQ-XCH-002 |
| Schema-compatibility guard against the previous release | `ExchangeContractTest.theSchemasOnlyGrewSinceThePreviousRelease`, `SchemaCompatibility` | REQ-XCH-026, ADR-0219 |
| The `exchange` module may already depend on `refinery::api` | `backend/…/exchange/package-info.java` | module boundaries |
| Refinery audit domain and its create / update / cancel / store / reassign events | `AuditDomain.REFINERY`, `AuditEventType.REFINERY_ORDER_*` | REQ-AUDIT-001 |

## B. Must be added

Grouped by layer; within each, the order is roughly the order a PR series would take. "Pinned" means
a test holds a count or an exact list and fails until it is updated.

### B1. Capabilities, Keycloak, registry, approval

- [ ] `ExchangeCapability` gains `REFINERY_READ("exchange.refinery.read")` and
  `REFINERY_WRITE("exchange.refinery.write")` —
  `backend/src/main/java/de/greluc/krt/profit/basetool/backend/exchange/internal/ExchangeCapability.java`
  (REQ-XCH-004). They are distinct from the existing `exchange.drafts.refinery`.
- [ ] Flyway migration widening `ck_exchange_client_capability` (precedent `V295`, `V298`), so the
  admin can grant the scopes — `backend/src/main/resources/db/migration/` (REQ-XCH-003). Claim the
  `V` number at push time (latest on `main`: `V359`).
- [ ] `EXCHANGE_SCOPES` in `scripts/provision-keycloak-realm.py` with a consent key, its self-test
  `scripts/provision-keycloak-realm.test.sh`, and the consent texts `xchConsentRefinery…` in
  `keycloak-theme/krt-theme/login/messages/messages_de.properties` and `messages_en.properties`
  (REQ-XCH-005: consent in German, per capability).
- [ ] The generated realms that carry the scopes: `docker/sandbox/keycloak/realm-basetool.json`
  (and `realm-iri.json`) via `scripts/build-sandbox-realm.py`, and
  `frontend/src/e2e/resources/realm-export.e2e.json`; `repo-lint.yml` fails a stale committed realm
  (REQ-XCH-029).
- [ ] Production: a provisioner run creates the scopes, and an `ADMIN` grants them to `versekit` on
  *Administration → Verbundene Anwendungen*. Both are **production writes** and need the owner's
  per-action yes (`docs/agents/production-host-access.md`).
- [ ] `docs/legal/approved-clients.md`: VerseKit's row amended with the new capabilities by an
  amendment PR (precedent #2647); the merge is the approval (REQ-XCH-002). The application template
  `.github/ISSUE_TEMPLATE/exchange-client-application.yml` lists the capability.
- [ ] Capability labels in the frontend: `AdminExchangeClientsPageController` (the checkbox list) and
  the `messages*.properties` keys for the admin form and the connected-apps chips (REQ-XCH-032).

### B2. Gateway (ingest)

- [ ] Route rows in `ingest/src/main/java/de/greluc/krt/profit/basetool/ingest/contract/ExchangeRoutes.java`:
  `route(GET, "/exchange/v1/me/refinery-orders", "exchange.refinery.read")` and
  `write(POST, "/exchange/v1/me/refinery-orders/changes", "exchange.refinery.write")` — `write`
  is what makes a route count against the quota (REQ-XCH-001, -023). Literal paths only.
- [ ] Relay methods in `ingest/src/main/java/de/greluc/krt/profit/basetool/ingest/web/ExchangeController.java`:
  the read relays to the backend and validates `page.schema.json#/$defs/<refineryPage>`; the write
  calls `changes("refinery-orders", "<refineryChangeSet>", <everyOpCounts>, …)`. Decide
  `everyOpCounts` (the org stock counts every op; member resources count once per set).
- [ ] Schemas under `ingest/src/main/resources/exchange/v1/schemas/`: a `refinery-order` entity
  schema, a `$defs` page in `page.schema.json`, a `$defs` change set in `change-set.schema.json`;
  each `$id` permanent once published (REQ-XCH-011). The existing `refinery-draft.schema.json`
  stays for the draft route.
- [ ] The public OpenAPI document `ingest/src/main/resources/api/exchange-v1.openapi.json`: both
  operations, the two scopes in the security scheme, the schema references (REQ-XCH-011);
  `ExchangeRoutesContractTest` holds it equal to the route table.
- [ ] Gateway tests: `IngestEndpointSurfaceTest`, `ExchangeRouteBotCompatibilityTest` (every route
  against the bot filter), a route test like `ExchangeOrgStockRouteTest`, `ExchangeChangeRouteTest`
  (quota and mass-change staging for the new segment), `ExchangeContractTest`.
- [ ] Golden answers: new goldens `route-get-refinery-orders.golden`,
  `route-post-refinery-order-changes.golden` and any refusal worth pinning under
  `ingest/src/test/resources/exchange/golden/`; **every existing golden that embeds the OpenAPI
  document or the service document changes too** (the org-stock read touched about 25 of them),
  and `ExchangeGoldenAnswerTest` pins the relay-operation count (REQ-XCH-036).

### B3. Backend exchange layer

- [ ] `ExchangeResource.REFINERY_ORDER` with `massChangeName()` (the gateway's path segment,
  `refinery-orders`), `writeCapability()` and `metricTag()` — exhaustive switches, so javac lists
  every decision site (ADR-0238) —
  `backend/src/main/java/de/greluc/krt/profit/basetool/backend/exchange/internal/ExchangeResource.java`.
- [ ] `ActingMemberFilter`'s exact path set gains the two backend paths
  `/api/v1/exchange/me/refinery-orders` and `…/changes` (REQ-XCH-009, REQ-SEC-080); its tests
  `ActingMemberFilterPathMatchingTest` and `FilterExemptionSetsTest`.
- [ ] A controller in `backend/…/exchange/web/` with `@PreAuthorize("@exchangeGate.allows('exchange.refinery.read', authentication)")`
  / `.write` on each method; `ArchitectureTest.everyExchangeControllerMethodCarriesTheExchangeGate`
  enforces it. Wire DTOs in `exchange.internal.dto` (ArchUnit keeps them in the exchange layer).
- [ ] A feed service (precedent `ExchangeShipFeedService`, `ExchangeOrgStockService`): snapshot
  paged by row id ending with the feed cursor, feed page answering each changed key once with its
  state or a tombstone (REQ-XCH-013). Feed key: the order id is a natural, stable key; tombstones
  need it opaque to clients.
- [ ] A write service implementing `ExchangeChangeSetAdapter` (ADR-0285): `plan` (per-op version
  check → `VERSION_CONFLICT`, `REMOVED_ELSEWHERE` against a live tombstone unless `override`),
  `holdsBack` (the **removal rule**: at least the hard delete; whether cancel counts is a decision),
  `start` returning an `Execution` that writes **through the refinery module's own services** so its
  audit, notifications and rules apply (as the org stock writes through `PoolEntryCommands`). The
  refinery module must expose what it needs in `refinery.api` (create, update with version, cancel,
  hard delete, client marker).
- [ ] The client key per order: the ship precedent is the link table `exchange_ship_link`
  (`user_id`, `client_id`, `installation_key`, `external_id`, `ship_id`; `ExchangeShipLink`) with a
  link step before the first create (REQ-XCH-017). A refinery link table needs the same treatment in
  the account merge (`ExchangeUserReassignment` names `exchange_ship_link` explicitly) and the
  retention of disconnected installations (REQ-XCH-035 holds keys as strings, not FKs).
- [ ] The client marker on the order: no refinery column records the originating client today. The
  blueprint precedent records the client and source in the domain row (REQ-XCH-015, REQ-INV-054);
  a new column is a refinery-module change with a migration and a REQ-REFINERY amendment.
- [ ] Journal: new `ExchangeJournalAction` values (`REFINERY_ORDER_CREATE`, `_UPDATE`, `_CANCEL`,
  `_DELETE`, or the chosen op set), a typed journal state class (precedent `ExchangeShipJournal`,
  `ExchangeOrgStockJournal`; `ExchangeJournalStateTest`), and a Flyway migration widening
  `ck_exchange_journal_resource` and `ck_exchange_journal_action` (precedent `V298`) (REQ-XCH-022).
- [ ] Undo: an `ExchangeEntryRestorer` for the resource, registered in `ExchangeUndoService` (it
  holds one journal per resource), with the version-checked skip as `CHANGED_AFTERWARDS` / `GONE`;
  a hard delete's undo recreates the order — decide whether under its own id (the org stock does)
  and what is not restored (REQ-XCH-022). Migration widening
  `ck_exchange_bulk_undo_run_resource` and `ck_exchange_bulk_undo_skip_resource` (precedent `V302`),
  and the bulk-undo request's `resource` values (REQ-XCH-034).
- [ ] Mass-change confirmation: `ExchangeMassChangeService` dispatches to each resource's write
  service by field (it injects all four today); the refinery write service needs its preview and
  `applyConfirmed` path there (REQ-XCH-021).
- [ ] Activity names and export: `ExchangeEntryLabels` (the connected-apps activity names an entry),
  `ExchangeExportSections` and `backend/src/test/resources/privacy/personal-data-export-sections.txt`
  if the journal or link table adds an export section (Art. 15).
- [ ] Live sync after commit: an `ExchangeLiveSync` method for refinery changes, and — since none
  exists — a refinery topic in `livesync.api.LiveSyncTopicClass` with its frontend registry
  counterpart (`LiveSyncTopicRegistryParityTest`), or a recorded decision (REQ-XCH-030, REQ-FE-001…010).
- [ ] Metrics: the new `metricTag()` value appears on `basetool_exchange_writes_total`,
  `_undo_total`, `_removals_total` and `_mass_changes_confirmed_total` without new code; any
  resource-specific counter (precedent `basetool_exchange_org_stock_pages_total`) goes into
  `platform/api/MetricNames.java` (REQ-OBS-011).

### B4. Database

- [ ] Feed triggers on `refinery_order` (and its goods table if a good is part of the served state):
  an `AFTER` row trigger recording `(member, 'REFINERY_ORDER', key)` into `exchange_change`, a
  migration widening `ck_exchange_change_resource` (precedent `V296`), keyed by `user_id` (the
  owner); the `ck_exchange_change_keyed_by` rule of `V296` already requires `user_id` for every
  resource but `ORG_STOCK`, so a member-keyed feed fits it unchanged (REQ-XCH-013, ADR-0224).
- [ ] Re-announcement paths: an owner change (user deletion reassigns orders), a location reference
  change (precedent `fd80aa7b5`, "re-announce feed entries when a location reference changes") and,
  if the order carries its unit, a unit rename (precedent ADR-0261).
- [ ] `ExchangeChangeFeedTriggerIntegrationTest` pins every synced table to its trigger and runs the
  bulk paths through them; `TableOwnershipTest` and `TriggerColumnResolutionTest` follow every new
  table and trigger.
- [ ] A hard delete of a refinery order must leave a tombstone, which the trigger gives on `DELETE`;
  check what cascades (goods, images, mission links) and that a cascade does not emit one tombstone
  per child key.

### B5. Backend contract documents and pinned counts

- [ ] `backend/src/main/resources/api/exchange-relay.openapi.json` (regenerated by
  `OpenApiGeneratorTest`; `CI=true` fails a stale one) and the `openapi.json` side of the split
  (REQ-XCH-039). Pinned there and in REQ-XCH-036/037/039: "19 relay operations on 18 backend paths",
  "83 schemas", "fifteen capability scopes", "ten exchange controllers" — `ExchangeFence`,
  `ExchangeFenceTest`, `OpenApiDocumentAssertionsTest`, `ExternalContractTest`.
- [ ] Tier and domain of the new operations (`T0`, domain `exchange`; `platform/internal/ApiDomains.java`)
  and `backend/src/test/resources/api/authorization-matrix.txt` (REQ-API-017/-018).
- [ ] `test-support/src/main/java/de/greluc/krt/profit/basetool/testsupport/exchange/ExchangeSeam.java`:
  the relay operations with route, backend path, capability, paging and schemas, and the capability
  list; parity tests `ExchangeRelaySeamParityTest` in backend and ingest (REQ-XCH-037).
- [ ] `ExchangeWireContractTest`: every new `valid/` request fixture accepted with `200`, every
  answer valid against its schema, the tombstones after a removal included (REQ-XCH-036).

### B6. Audit (REQ-AUDIT-001)

- [ ] Writes go through the refinery services, so `REFINERY_ORDER_CREATED` / `_UPDATED` /
  `_CANCELED` name the client by `ClientAttribution` for free.
- [ ] **New** for hard delete: an `AuditEventType` (e.g. `REFINERY_ORDER_DELETED`, domain
  `REFINERY`), its `auditRecorder.record(...)` call without free text or PII, the viewer's
  event-type filter, the DE/EN labels, and the coverage list in `docs/specs/audit.md`
  ("Raffinerie — order create / update / cancel / store …").
- [ ] The undo and the confirmation stay `EXCHANGE_CHANGES_UNDONE` / `EXCHANGE_MASS_CHANGE_CONFIRMED`
  (no new event); if the restore of a deleted order goes through a new refinery path, that path's
  own audit event (precedent `INVENTORY_ITEM_RESTORED`).

### B7. Frontend

- [ ] Resource labels on the connected-apps page, its confirmation page and the admin undo form:
  `frontend/src/main/resources/static/js/exchange/connected-apps.js`, `connected-apps-confirm.js`,
  `admin-exchange-clients.js`, the templates under `frontend/src/main/resources/templates/exchange/`
  (`data-resource-*` attributes, the admin `<option>`), and the keys
  `connectedApps.undo.resource.*`, `connectedApps.confirmMass.resource.*`,
  `admin.exchangeClients.undo.resource.*` in all three `messages*.properties` (umlauts as `\uXXXX`).
- [ ] `ConnectedAppActivityDto` (frontend model) Javadoc lists the resource values;
  `ConnectedAppsPageControllerMvcTest`, `AdminExchangeClientsPageControllerMvcTest`.
- [ ] The privacy notice keys (`privacy.p_3_10_*`) if refinery data is a new category flowing to a
  client.

### B8. Developer site `docs/exchange/` (gated by `checkExchangeDocs`)

- [ ] `docs/exchange/resources/refinery-orders.md` (precedent `resources/ships.md` for a member
  resource with versions and a link step, `stock.md` and `blueprints.md` for the feed) and its
  entry in `docs/exchange/_data/navigation.yml` (a missing page fails the link check).
- [ ] `docs/exchange/README.md` route and capability table, `sync-guide.md` (pull before push,
  add-only first sync, link before create, removals only from a diff, cancel versus delete),
  `errors.md` for every new per-op reason or code (codes are never reused; `ExchangeContractTest`
  checks the registry), `formats.md` if a new shared shape appears, `quickstart.md` and
  `sandbox.md` scope lists.
- [ ] Conformance fixtures under `docs/exchange/examples/v1/`: `refinery-order/`,
  `page--refineryOrderPage/`, `change-set--refineryOrderChangeSet/`, each with `valid/` and
  `invalid/` (every schema the OpenAPI document names needs at least one of each).
- [ ] `docs/exchange/changelog.md`: a dated entry announcing the routes, scopes and reasons
  (additive within `v1`, REQ-XCH-026, ADR-0219; `versioning.md` needs no change).
- [ ] English only, no umlaut or `„` outside fixtures; links resolve to kramdown heading ids;
  markdownlint; `./gradlew :frontend:checkExchangeDocs` before the push.

### B9. Specs, ADRs and companion documents

- [ ] `docs/specs/external-exchange.md`: the route table of REQ-XCH-001, the capability list of
  REQ-XCH-004, the schema list of REQ-XCH-011, the synced-table list of REQ-XCH-013, the resource
  lists of REQ-XCH-021/-022/-034, the counts of REQ-XCH-036/-037/-039, and new requirements for
  the resource itself (next free id after REQ-XCH-050).
- [ ] An ADR for the refinery exchange decisions (cancel versus delete, client key, client marker,
  feed key, removal rule), as ADR-0257 is for the org stock; claim the number at push time (latest
  on `main`: ADR-0350).
- [ ] The refinery specs (`docs/specs/refinery-*.md`) for hard delete and the client marker;
  `docs/specs/audit.md`; `docs/specs/observability.md` (the `resource` label values, any new
  metric or alert); `docs/specs/data-persistence.md` (the synced tables);
  `docs/specs/api-conventions.md` (relay operation count).
- [ ] `ROLES_AND_PERMISSIONS.md`, `README.md`, `docs/arc42/03-context-and-scope.md` (and §6/§12 when
  they describe the flow or a new term), `docs/privacy/processing-activities.md` (A11), a
  `changelog.d/<name>.md` fragment (never `CHANGELOG.md`), the German user wiki for
  "Verbundene Anwendungen" if the member-facing behaviour changes.

### B10. Monitoring (REQ-OBS-005…011)

- [ ] Grafana `monitoring/grafana/dashboards/15-exchange.json` and the operations dashboard's
  panel 80 show the new `resource` value; any resource-specific panel.
- [ ] Alerts in `monitoring/prometheus/alerts/business.yml` (precedent
  `ExchangeOrgStockRejectionSpike`) with tests in `monitoring/prometheus/tests/exchange_write_alerts_test.yml`;
  `monitoring/README.md`.
- [ ] The generic alerts (`ExchangeRemoveSpike`, `ExchangeGuardStorm`, `ExchangeBulkUndoFailed`)
  cover the resource without change once the counters carry its tag.

### B11. Sandbox, E2E and CI

- [ ] `docker/sandbox/seed.sql` (the sandbox client's granted capabilities and seed orders),
  `scripts/sandbox_client.py` (the requested scope string), `scripts/sandbox-smoke.py` (the read
  route, one sync, the change-set fixtures in `--conformance`; precedent `0a83f1702` routed the
  org-stock fixtures) (REQ-XCH-029).
- [ ] An E2E round trip on the E2E stack if the resource changes member-visible pages
  (`ExchangeSyncE2eTest` style).
- [ ] The PR carries the `e2e` label: `check_exchange_e2e_label.py` fails a PR touching the
  exchange package, ingest `src/main`, the fixtures or the seam without it (REQ-XCH-036, ADR-0311).

## Decisions the refinery resource must make (not answered by the framework)

1. **Op set and verbs**: `create` / `update` / `cancel` / `delete` as change-set ops (no `DELETE`
   method, no path id).
2. **Removal rule for the guard**: hard delete counts; does cancel? (Ships count `remove` and a
   name-and-type change.)
3. **Client key**: link table per (member, client, installation, external id) as ships do, or a key
   column on the order; and whether a link step precedes the first create (REQ-XCH-017 shape).
4. **Client marker**: column on `refinery_order` versus derivation from the journal or change log.
5. **Feed key and scope**: per owner (`user_id`) like ships; whether orders of a unit the member
   does not own are ever served (the org stock is the only unit-keyed feed).
6. **Undo of a hard delete**: recreate under the same id or a new one, and what does not come back
   (goods images, mission links, stored yields).
7. **Quota**: once per change set or every op (`everyOpCounts`).
8. **Live sync**: a new refinery topic, or how the refinery pages otherwise follow an exchange write.

## Sources

- `docs/specs/external-exchange.md` — REQ-XCH-001, -003, -004, -005, -009, -010, -011, -013, -014,
  -015, -017, -019, -020, -021, -022, -023, -025, -026, -027, -028, -029, -030, -032, -034, -035,
  -036, -037, -039, -042…-046.
- `docs/specs/audit.md` — REQ-AUDIT-001 coverage list, REQ-AUDIT-005; `docs/specs/observability.md`
  — REQ-OBS-011 exchange counters.
- `docs/exchange/README.md`, `versioning.md`, `changelog.md`, `examples/README.md`.
- ADR-0216, ADR-0219, ADR-0221, ADR-0224, ADR-0229, ADR-0238, ADR-0257, ADR-0261, ADR-0281,
  ADR-0285, ADR-0311.
- `docs/legal/approved-clients.md` (VerseKit row, amended by #2647).
- Code: `ExchangeRoutes`, `ExchangeController` (ingest); `ExchangeCapability`, `ExchangeResource`,
  `ExchangeChangeSetAdapter`, `ExchangeEntryRestorer`, `ExchangeJournalAction`,
  `ExchangeMassChangeService`, `ExchangeUndoService`, `ExchangeUserReassignment`,
  `ExchangeShipLink`, `ExchangeLiveSync`, `exchange/package-info.java`, `AuditEventType`,
  `RefineryOrder`, `LiveSyncTopicClass` (backend); `scripts/provision-keycloak-realm.py`;
  `frontend/build.gradle.kts` (`checkExchangeDocs`).
- Migrations `V295`, `V296`, `V298`, `V302`.
- Precedent commits: `a67c39463` (#2623), `7d8104911` (#2624), `8f855ace0` (#2626),
  `f4fc34b2d` (#2631), `0a83f1702` (#2629), `fd80aa7b5`.
