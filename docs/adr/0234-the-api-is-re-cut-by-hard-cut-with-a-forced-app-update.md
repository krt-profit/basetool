# ADR-0234 — The API is re-cut by hard cut, with a forced app update and a release-bound floor

- **Status:** Accepted — machinery in place (Phase 0: contract tiers, frozen set from the app's call list, declared-break ledger, generated edge admission, release-bound floor, `APP_UPDATE_REQUIRED`, the app's policy re-read in app v0.5.0); the cuts follow wave by wave (REST API track). Supersedes
  the retirement clause of [ADR-0136](0136-external-contract-set-for-shipped-clients.md) (decision
  bullet 4).
- **Date:** 2026-09-29 (hard cut, D-03, D-04, D-05); 2026-10-01 (release-bound floor, D-11)
- **Deciders:** @greluc (owner decisions D-03, D-04, D-05, D-11)
- **Related:** [domain modularisation plan](../DOMAIN_MODULARISATION_PLAN.md) §5.10, §7.9 ·
  [REST API cut](../modularisation/rest-api-cut.md) (*Contract tiers*, *The forced update*,
  *Contract machinery*) · spec REQ-API-001, REQ-API-009, REQ-API-010 ·
  [ADR-0135](0135-public-api-vhost-not-a-gateway.md) (amended) ·
  [ADR-0216](0216-the-exchange-api-is-a-separate-contract-on-the-ingest-gateway.md) (amended) ·
  [ADR-0219](0219-the-exchange-contract-grows-additively-under-a-path-major-version.md) ·
  `docs/EXCHANGE_GO_LIVE_RUNBOOK.md` step S8

## Context

`/api/v1` is cut by controller and audience: 99 controllers serve 572 documented operations for 22
domains, `/api/v1/admin/**` holds five domains and `/api/v1/users/**` the data of six. The owner
decided a full per-domain cut (D-03). ADR-0136 and REQ-API-009 retire a frozen operation only
through `/api/v2` plus `@ApiDeprecation` with a sunset, which would mean running two paths per
operation through every wave of that cut.

The Android app is distributed through GitHub Releases and Obtainium; it reads
`GET /api/v1/app/version-policy` (REQ-API-010) ahead of its lock, login and session and shows a
non-dismissible update wall below the floor. Today the floor is
`app.android.minimum-version-code`, bound once at backend start from
`APP_ANDROID_MINIMUM_VERSION_CODE`, which exists only in the host `.env`. Raising it is runbook step
S8: a production write with about a minute of outage. Three gaps follow: the app reads the policy
once per process and fails open, so running apps meet a re-cut API without a wall; old apps meet it
between the deploy and S8; and a health-gate rollback after S8 re-renders the raised floor from the
same `.env`, so the old backend walls old apps while new apps find their paths gone.

## Decision

1. **Hard cut, no parallel paths** (D-04). A re-cut operation leaves its old path in the same
   release; there are no deprecation aliases and no sunset windows. A wave that changes Android
   operations is one app release, one deploy and one raise of the floor; a wave that changes only
   web operations ships with the ordinary atomic deploy of frontend and backend.
2. **Contract tiers.** Every operation carries one tier, marked on its handler and emitted into
   `openapi.json` as `x-contract-tier`:
   - **T0** never breaks: `GET /api/v1/app/version-policy`, `POST /internal/discord/account-existence`,
     the 14 exchange relay operations under `/api/v1/exchange/**` (D-05, frozen byte for byte), and
     the two SSE streams plus `POST /api/v1/live-sync/changed`. A T0 break fails every build.
   - **T1** is the Android contract — every operation a released app build calls, minus T0. It
     breaks only in a declared hard-cut wave.
   - **T2** is web-only and changes freely with the atomic deploy, guarded by the frontend contract
     tests and the call-existence test (plan guard G-14).
3. **Declared-break ledger** (`backend/src/test/resources/api/declared-breaks.txt`, plan guard
   G-23): one line per removed or changed T1 operation or field, naming the operation and field —
   never a wildcard — and the app `versionCode` that absorbs it. The previous-release comparison
   accepts exactly the declared breaks and fails on anything else; T2 breaks are reported.
4. **App call list.** Each app release publishes a machine-readable list of the calls it makes —
   verb, path, query parameters and the response fields it reads. The backend repository commits it
   per app release; the frozen set must cover it, and the list of app N+1 calls nothing the ledger
   declares broken. The list comes before the generated edge include (ADR-0135 amendment).
5. **Release-bound minimum version** (D-11). The floor becomes a reviewed default in the release's
   own configuration, so it deploys and rolls back together with the API it protects. The host
   `.env` value stays only as an emergency override. Until the release-bound floor is in place, the
   floor is raised only after the re-cut release is verified healthy (S8), and the rollback runbook
   reverts the floor first.
6. **The app re-reads the policy on foreground resume and after an unexpected 404** (or `NOT_FOUND`
   on a known path), shipped in an app release **before** the first T1 wave.
7. **Retired paths answer `APP_UPDATE_REQUIRED`.** An operation retired by a declared break answers
   one stable RFC 7807 problem with that code, which later app versions map to the update wall; only
   paths that were admitted before answer it.
8. **Cuts are announced and made at low usage.**
9. **The exchange stays out of the cut** (D-05): `/exchange/v1` on the ingest gateway and the
   backend relay surface `/api/v1/exchange/**` keep their behaviour byte-identical (ADR-0216
   amendment, ADR-0219).

## Consequences

- One path per operation at any time; no `/api/v2` and no `@ApiDeprecation` machinery for the cut.
  Spring's built-in API versioning adds nothing under a hard cut.
- Every T1 wave costs every member one forced update; waves can share an app release when ready
  together.
- Requirements change first: REQ-API-001, REQ-API-009 and REQ-API-010 are amended with this ADR.
- New machinery precedes the first T1 wave: the tier annotation, the ledger, the app call list, a
  mandatory previous-release baseline on `main`, the generated edge include, the release-bound
  floor, the `APP_UPDATE_REQUIRED` answer and the app's re-read.
- A health-gate rollback of a T1 wave is itself a hard cut in the other direction for members who
  already updated; a defect found after the floor rose is better fixed forward.
- Implementation: the waves are built on the integration branch `claude/api-cut` and ship together (D-24); the web-only wave
  moved every administration tree to `/api/v1/<root>/admin/**` behind one ADMIN rule (REQ-API-023). The wave plan is
  [api-cut-waves.md](../modularisation/api-cut-waves.md).
- Left open inside D-11: a static policy file served by the edge while the backend restarts, only if
  the app's re-read proves insufficient.

## Alternatives considered

- **`/api/v2` beside `/api/v1` with sunset windows** (ADR-0136 as written) — doubles every re-cut
  operation and its gates, path lists and probes for weeks per wave, while the floor already makes
  old builds stop.
- **Raising the floor in the same deploy while it lives in `.env`** — a health-gate rollback would
  restore the old backend with the raised floor, and no app version would work.
- **Freezing all of `/api/v1`** — ends in-place evolution for the web, which deploys atomically.
- **An external API-diff tool** (`oasdiff`) — an unverified binary outside Gradle's dependency
  verification; the ingest's `SchemaCompatibility` helper is generalised instead.
