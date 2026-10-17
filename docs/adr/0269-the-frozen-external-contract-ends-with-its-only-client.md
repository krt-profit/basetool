# ADR-0269 — The frozen external contract ends with its only client

- **Status:** Accepted — decided ahead of the change; the removal lands in #2673. Supersedes
  [ADR-0136](0136-external-contract-set-for-shipped-clients.md). Amends
  [ADR-0234](0234-the-api-is-re-cut-by-hard-cut-with-a-forced-app-update.md) (the hard-cut machinery
  loses its subject), [ADR-0151](0151-clients-are-told-their-authorisation.md) and
  [ADR-0156](0156-the-app-carries-the-banks-direct-booking.md) (both lose the client half of their
  reasoning and keep their rule).
- **Date:** 2026-10-17
- **Deciders:** @greluc (issue #2638)
- **Related:** [ADR-0268](0268-the-backend-is-internal-only-and-ingest-is-the-single-public-api-entry-point.md)
  (the exposure this follows) ·
  [ADR-0216](0216-the-exchange-api-is-a-separate-contract-on-the-ingest-gateway.md) ·
  [ADR-0219](0219-the-exchange-contract-grows-additively-under-a-path-major-version.md) (where an
  external contract is actually frozen) ·
  [ADR-0055](0055-keycloak-spi-jar-as-promotable-oci-artifact.md) (the one artifact that still
  deploys on its own) · [ADR-0231](0231-the-backend-becomes-a-modular-monolith-one-package-per-domain.md)
  (the cut that continues) · specs `REQ-API-001`, `REQ-API-007`, `REQ-API-009`, `REQ-API-010`,
  `REQ-API-016`, `REQ-API-017`, `REQ-API-020`, `REQ-API-021`, `REQ-SEC-052`, `REQ-XCH-039` ·
  [`rest-api-cut.md`](../modularisation/rest-api-cut.md)

## Context

REQ-API-001 lets a `/api/v1` operation consumed solely by the in-repo frontend change its response
shape in place, because frontend and backend ship from one repository and promote as one digest set.
ADR-0136 narrowed that carve-out for one reason, stated plainly: *"A released Android build removes
the premise entirely. It sits on a member's phone for weeks or months."*

Everything built on that narrowing is machinery for a client that cannot redeploy:

- `contract-tiers.txt` and `ContractTiers` — 23 operations at T0, 243 at T1, the rest T2 — emitted
  into `openapi.json` as `x-contract-tier`;
- `ExternalContractTest`, which reads the committed `openapi.json` and fails on a vanished
  operation, a changed verb, a dropped recorded field, a new required request field, a changed
  frozen enum or a changed recorded query parameter;
- the app call lists under `api/app-calls/` (REQ-API-016), one file per app build the server still
  serves, which the frozen set has to cover;
- the declared-break ledger (REQ-API-017) and the previous-release comparison that accepts exactly
  its lines;
- `EdgeAdmission`, which renders the edge's admission map and the nightly probe table from the same
  model;
- `RetiredOperations` and its filter, answering `410 APP_UPDATE_REQUIRED` on a retired path, and the
  release-bound minimum app version that converts "deprecated" into "actually retired".

With the app discontinued, almost every operation the tier file lists has exactly the atomic-deploy
premise REQ-API-001 was written for. Two parts of the T0 set do not, and they are the reason this ADR
has to say what is *kept*: the 20 exchange relay operations, whose real clients are external and
whose contract is frozen by ADR-0216 and ADR-0219 on the gateway where those clients reach it; and
`GET /api/v1/notifications/stream`, which the frontend's own relay consumes.

There is one genuine cross-artifact contract left, and the machinery never held it.
`POST /internal/discord/account-existence` is called by the Keycloak SPI, whose provider JAR is a
*separate* promotable artifact (ADR-0055) living in the Keycloak container — so backend and caller
do not deploy atomically, and a shape change there can break a login while both artifacts are
individually healthy. ADR-0234 names that operation as T0. `contract-tiers.txt` does not list it:
the tiers are keyed to the OpenAPI document, which does not describe `/internal/**`. The one
compatibility constraint that outlives the app was outside the guard the whole time, which is the
clearest statement available of what the guard was really for.

## Decision

**The frozen external contract set and the hard-cut machinery end. What remains is kept for its own
reasons, named one by one.**

**Removed** (#2673): the contract tiers (`contract-tiers.txt`, `ContractTiers`, the
`x-contract-tier` extension), `ExternalContractTest`, the app call lists and their test,
`DeclaredBreaks` and the declared-break ledger, `EdgeAdmission` with the generated admission map and
probe table, `RetiredOperations` / `RetiredOperationFilter` / `retired-operations.txt` and the
`APP_UPDATE_REQUIRED` answer, and the app floor with `GET /api/v1/app/version-policy`.

**Kept, and why:**

- **`CommittedOpenApi` and the committed `openapi.json`** (REQ-API-007). It is not a promise to a
  third party; it is the input the frontend's generated DTO types and the DTO mirror tests read, and
  the drift it catches is drift between our own two modules.
- **`ExchangeFence`** (ADR-0216, REQ-XCH-039). The split between the published document and the
  exchange's internal relay document is about what external clients may see, not about what may
  change.
- **The anonymous-surface guards** (`AnonymousSurfaceSweepTest`, REQ-SEC-052). A security
  invariant — no mapping serves a caller who is not a member — that happened to live next to the
  contract tests. `ApiVhostAnonymousSurfaceTest` goes with the vhost it pins; the sweep stays.
- **The exchange's own freeze.** The 20 relay operations keep it through ADR-0219's additive growth
  under a path-major version, where their clients are. They do not need a second, weaker freeze on
  this side.
- **The SPI's compatibility constraint, stated where a reader will find it.** The one operation whose
  caller deploys separately is recorded as a named constraint on the internal surface in the spec,
  not as a tier in a machine-readable file whose only other consumer is gone. A constraint on one
  operation does not earn a framework, and the framework is what mistook it for one.

- **The live-sync topic-registry parity test.** It reads the frontend's source rather than a
  committed contract, and it guards a mirror between two of our own modules that is still
  load-bearing: the backend publishes into the frontend's rooms after an exchange or ingest write
  (ADR-0274, amendment 1). It sat beside the contract tests and is not one of them.

**`/api/v1` is evolvable in place again**, under REQ-API-001 unchanged. The per-domain re-cut of
ADR-0231 continues — it is why the tiers existed in that shape — but a wave is now an ordinary
atomic deploy of frontend and backend: no app release, no floor raise, no ledger line, no retired
path.

## Consequences

**ADR-0234's mechanism loses its subject; its history keeps its value.** D-03's per-domain cut
stands, D-04's "no parallel paths" stands trivially (there is one client and it deploys with the
server), and D-05's exchange carve-out is now the only external contract there is. D-11's
release-bound floor, the forced update, the ledger and the call lists describe a problem the project
no longer has. The ledger's contents stay in git, where a question about a past break is answered by
the commit that declared it.

**ADR-0151 keeps its rule and loses one of its two clients.** *A client is told its authorisation; it
never derives it from a role or a membership flag* was not an app rule — the trap it names is that
an admin holds no Staffel membership by design, and the web frontend was right about it only by
having written the hierarchy out a second time. The capability reads and the per-row `canEdit` flags
stay.

**ADR-0156 keeps the lesson that was never about the app.** Its four allow-list rules are moot with
the allow-list; *an exclusion may not cite a fact nobody re-checks* is a process finding, and the
shape it warns about — a probe written from an expectation, turning a mistake into a green
light — is exactly the risk in decision 4 of ADR-0268, which is why that probe asserts an absence
and not a belief.

**The frozen set was also documentation, and that is a real loss.** 243 T1 lines said which
operations a real client actually called, field by field; nothing replaces that, and nothing needs
to, because the question "may this change?" now has the same answer everywhere.

**One guard class disappears without a replacement, on purpose.** Nothing will tell us that
`GET /api/v1/notifications/stream` changed shape between releases. Its consumer is in this
repository, ships in the same promotion, and is covered by the DTO mirror tests. That is the
carve-out's bargain, taken deliberately rather than inherited.

## Alternatives considered

*Keep the frozen set, narrowed to the exchange relay paths.* It would freeze the right operations on
the wrong side. ADR-0216 and ADR-0219 already freeze the exchange contract where its clients consume
it, with a versioning rule of its own; a second ledger on the relay path would drift from the first
and would have to be reconciled by hand.

*Keep `ExternalContractTest` as a release-to-release drift detector.* Tempting, because the test is
written and works. Without a client that cannot redeploy, every intended shape change becomes a
failure that has to be declared, and the ledger turns into a changelog maintained under build
pressure. A gate that fires on intended changes teaches people to feed it.

*Keep the tiers as documentation only, with nothing enforcing them.* A tier file nothing reads is a
claim nothing keeps true. This repository has the same finding written down twice already (ADR-0156's
self-reinforcing exclusion, ADR-0135's allow-list that proved admission rather than freezing).

*Promote the backend and the provider JAR as one artifact so the SPI's operation needs no
constraint.* A real option and a different decision: ADR-0055 split them deliberately, and the split
has operational reasons that have nothing to do with this. The constraint is one operation wide;
naming it is cheaper than merging two delivery paths.
