# ADR-0085 — Scale the Keycloak user sync and stack capacity for 5000 accounts / 200 concurrent

- **Status:** Accepted
- **Date:** 2026-07-09
- **Deciders:** @greluc
- **Related:** `KeycloakService` · `UserSyncService` · `UserSyncTask` · `KeycloakSyncProperties` · `UserRepository.findIdsWithDiscordLink` · `RoleRepository.findAllNames` · `docker-compose.yml` (keycloak / db-keycloak / db-backend / redis) · REQ-SEC-018 · REQ-DATA-006 · ADR-0036 · ADR-0078 · ADR-0079 · the 2026-07-09 native-thread exhaustion incident

## Context

The org has grown past 3000 members; the tool is now sized for a target of **5000 Keycloak
accounts and 200 concurrent tool users** on the single **16 GB** Hetzner host. An audit of the code
and the container budget against that target found exactly one architectural blocker plus a set of
capacity knobs.

**The blocker — the scheduled Keycloak user sync was O(users) in Admin-API calls.**
`KeycloakService.fetchUsers()` fetched the roster (paged), then for **each** user issued two more
Admin-API calls: `GET /users/{id}/role-mappings/realm` (roles) and `GET
/users/{id}/federated-identity` (the Discord link back-fill, ADR-0036). At 5000 accounts that is
~1 (token) + ~50 (roster pages) + 5000 (roles) + 5000 (federated) ≈ **10,000 sequential Admin-API
calls per run** — a multi-minute burst that hammered Keycloak. Combined with the pre-2026-07
per-minute cadence, this Admin-API pressure was an accelerant of the native-thread exhaustion
incident; even at an off-peak cadence a 10k-call burst is the wrong shape at 5000 accounts.

**What was already adequate** (verified, no change): request handling runs on **virtual threads**
(`spring.threads.virtual.enabled`), so 200 concurrent users are trivial for threads and the
`pids: 2048` cgroup cap; the Hikari pool (100, ADR-0078) plus Postgres `max_connections=150` sit
well above what 200 concurrent users drive (they never open more than the 100-slot pool); the roster
fetch already pages (REQ-SEC-018); user-list reads are squadron-scoped; the SSE stream cap is 5 per
user. None of these are 5000-account blockers.

## Decision

### 1. Role-indexed role resolution (the N→#roles collapse)

Resolve roles by listing the members of each **app-relevant** realm role once — `GET
/roles/{name}/users`, paged — instead of reading each user's role mapping. The role set to query is
the local role catalog (`RoleRepository.findAllNames()` → `UserService.getMappableRoleNames()`),
because `UserService.mapRoles` only keeps roles that exist locally; ubiquitous default/technical
realm roles (`default-roles-*`, `offline_access`, `uma_authorization`) are never walked. Both the
old per-user endpoint and the role endpoint report **directly-assigned** realm roles, so the
reconstructed `user → roles` sets are equivalent — the call count is now bounded by the (small)
number of mappable roles × their page count, not by the user count.

> **Refinement (2026-07-10, Discord account-creation regression audit).** Two hardening tweaks to
> the role read, both pinned by REQ-SEC-018: (1) the local catalog names are matched
> **case-insensitively** against the realm's actual role names — resolved once via a paged `GET
> /roles` — before the member read, so a role whose Keycloak casing differs from the local name is
> still resolved (removing a scheduled-vs-interactive asymmetry, since the JWT path already maps via
> `findByNameIgnoreCase`). (2) A transient role-member read failure (5xx/timeout/…) now **skips the
> whole run** instead of being swallowed — persisting a role-stripped set had let a brand-new admin
> be created `PENDING` and mass-downgraded existing admins. Only a benign `404` on one role (a TOCTOU
> after the listing) is swallowed. The N→#roles scaling shape is unchanged (one extra `GET /roles`
> listing per run is O(#roles), not O(users)).

### 2. Incremental Discord federated-identity back-fill

Read `GET /users/{id}/federated-identity` only for roster users **without a local Discord link**
(`UserRepository.findIdsWithDiscordLink()` → `getKnownDiscordLinkedUserIds()` is the skip-set passed
into `fetchUsers`). The already-linked majority is skipped each run. `syncUser` already treats a
`null` link as "leave the existing link alone", so skipping is safe. A *relink* to a different
Discord account is caught by the login-claim path (ADR-0036 layer 1) at the linker's next login, so
the daily sync only needs to cover accounts with no local link yet.

Together, 1 + 2 take a steady-state run from ~10,000 Admin-API calls to on the order of ~50–100.

### 3. Daily cadence at 05:00, on-demand via the manual button

`UserSyncTask` moves from a fixed-delay interval to a 6-field cron
(`app.keycloak.sync.cron`, default `0 0 5 * * *`, in `app.keycloak.sync.zone`, default
`Europe/Berlin`) — one off-peak run per day. The sync is a drift-correction safety net, not a live
feed: the login path reconciles roles on every authentication, and admins who need an immediate
refresh use the "Sync now" button (`POST /api/v1/users/sync`), which shares the same
`UserSyncService.syncFromKeycloak()`. An invalid cron fails startup rather than silently disabling
the sync.

### 4. Container capacity sizing (fits the 16 GB host)

- **keycloak** `1536M → 2560M` — 5000 users + persistent sessions + 200 concurrent token/refresh
  flows; the image auto-sizes the JVM heap to ~70 % of the limit (~1.0 GB → ~1.75 GB).
- **db-keycloak** `512M → 768M`, `shared_buffers 128 → 192MB`, `max_connections 100 → 120` — 5000
  Keycloak users + KC-26 persistent sessions, with pool headroom for `postgres_exporter`.
- **db-backend** `1536M → 2048M`, `shared_buffers 384 → 512MB` — the larger 5000-account working set
  (`max_connections` stays 150; the 100-slot Hikari pool + exporter + Flyway still fit).
- **redis** `256M → 512M`, `maxmemory 192 → 384mb` — session-index growth at 5000 accounts under the
  30-day rolling login and the cap-10 concurrent sessions; `noeviction` means the ceiling must never
  be reached (evicting a session key is a silent logout), so headroom is bought deliberately.

Post-pass the `prod` app services total ~9.5 GB; with the ~2–3 GB monitoring stack that leaves ~4 GB
headroom on the 16 GB host. Keep the sum of limits under ~14 GB.

> **Measured update 2026-07-25 (PR #1419) — the ~14 GB guidance is now effectively reached.** The app
> side held to its prediction (**9.75 GB**, after the frontend limit went 1024M → 1280M to fix
> `ContainerWorkingSetHigh`), but the **monitoring stack has grown to 4.23 GB**, well past the "~2–3 GB"
> this ADR assumed. Current sum: **13.98 GB — about 16 MB under the cap.** The `~14 GB` rule itself is
> unchanged and still honoured; what is stale is the monitoring estimate it was derived from.
>
> The next capacity increase therefore needs an explicit owner decision, not an incremental bump. The
> identified lever, with measurements to back it, is the **`backend` app container: 2048M against a
> measured 1108 MB working-set peak (54%)**, whose heap is capped at 1167 MB (57%) for a 1605 MB
> worst case. Reducing it to 1792M with `MaxRAMPercentage=55` (986 MB heap — still 1.5× the 670 MB it
> actually commits) keeps its worst case at 79% and returns **256 MB**. That was deliberately NOT done
> in PR #1419: shrinking the busiest JVM's ceiling is a capacity trade for @greluc to make. The same
> applies to trimming the monitoring stack, which is where the actual drift is.
>
> Measure before deciding — the per-service snapshot queries (working set, `jvm_memory_committed_bytes`
> by area, and the unreported native-memory term) are in `monitoring/README.md`.

### 5. Preserve the 30-day rolling login (Redis sized instead of TTL cut)

The 30-day Spring-Session idle window is a **deliberate** design premise (it carries the OAuth2
refresh token behind a rolling login — ADR-0079, the `docker-compose.yml` redis rationale).
Shortening it to reduce Redis growth would contradict that decision and change auth UX, so the
session TTL is **kept** and the growth is absorbed by the Redis capacity bump in (4) instead. If the
owner later wants a shorter login window, that is a separate product decision.

## Consequences

- The daily sync is a bounded off-peak burst even at 5000 accounts; Keycloak is no longer hammered.
- Role resolution stays faithful (directly-assigned realm roles), and the REQ-SEC-018 completeness
  invariant is unchanged — role-indexing only changes how the paged roster is *annotated*.
- The incremental Discord back-fill is bounded by the *unlinked* population, not the full roster; a
  relink is caught at next login rather than by the daily sync (acceptable — the sync is a safety
  net, ADR-0036).
- The staleness alerts move to the daily cadence: `UserSyncStale > 26h`, `UserSyncZeroItems` over a
  `[26h]` window (monitoring moves with the change, REQ-OBS-011).
- The manual "Sync now" button (already shipped) becomes the primary on-demand refresh path.
- `KeycloakSyncProperties.interval` (an unused-in-code binding) is replaced by `cron` + `zone`;
  `APP_KEYCLOAK_SYNC_INTERVAL` becomes `APP_KEYCLOAK_SYNC_CRON` / `APP_KEYCLOAK_SYNC_ZONE`.

## Alternatives considered

- **Bounded parallelism on the per-user calls.** Would cut wall-clock but not the ~10k-call *load*
  on Keycloak — the wrong axis. Rejected in favour of the role-indexed collapse.
- **Raise `pids: 2048`.** The concurrency is handled by virtual threads; a higher pids cap would only
  mask a leak (the incident root cause) without helping the real 5000-account cost. Rejected.
- **Shorten the session idle TTL to 7–14 days.** Reduces Redis growth but contradicts the documented
  30-day rolling-login decision (ADR-0079); sizing Redis is the non-breaking choice. Deferred to the
  owner as a product call.
- **Async server-side user pickers instead of the squadron-scoped reference list.** The admin
  "all-squadrons" picker can return up to 5000 slim rows; converting the pickers to async search is a
  UI feature (design + tests + wiki), not a scaling knob, and the rows are already scoped and tiny.
  Deferred as a follow-up rather than half-built here. **Done in #1193 / ADR-0089:** the all-users
  pickers were switched to the combobox `remoteSource` mode (server-side `/users/search`, and the
  bank-audience `/users/search-bank`), so no converted picker ships the full roster anymore.

