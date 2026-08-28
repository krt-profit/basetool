# ADR-0085 — Scale the Keycloak user sync and stack capacity for 5000 accounts / 200 concurrent

- **Status:** Accepted
- **Date:** 2026-07-09
- **Deciders:** @greluc
- **Related:** `KeycloakService` · `UserSyncService` · `UserSyncTask` · `KeycloakSyncProperties` · `UserRepository.findIdsWithDiscordLink` · `RoleRepository.findAllNames` · `docker-compose.yml` (keycloak / db-keycloak / db-backend / redis) · REQ-SEC-043 · REQ-DATA-006 · ADR-0036 · ADR-0078 · ADR-0079 · the 2026-07-09 native-thread exhaustion incident

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
fetch already pages (REQ-SEC-043); user-list reads are squadron-scoped; the SSE stream cap is 5 per
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
> the role read, both pinned by REQ-SEC-043: (1) the local catalog names are matched
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
>
> **Owner decision 2026-08-02 — `blackbox-exporter` 32M → 64M (+32 MiB), sum now 14.02 GB.** The first
> increase taken under the rule above; @greluc approved it against the measured alternative of leaving
> the limit at 32M. Read-only prod measurement showed the exporter pinned at 97–100 % of its 32 MiB
> limit while its live heap was **9.8 MiB** — an un-budgeted Go runtime holding arena pages, not a
> workload that needs the memory. `GOMEMLIMIT=44MiB` is the actual fix; the limit bump buys the spike
> headroom a 78–85 % steady state never had (details in the `blackbox-exporter` block of
> `docker-compose.monitoring.yml`).
>
> This puts the sum of limits ~22 MiB past the `~14 GB` guidance. Two measurements justify accepting
> that rather than treating it as a breach: the guidance bounds a **sum of ceilings**, not consumption,
> and prod `node_memory_MemAvailable_bytes` held at **10–11 GB across the full 21-day window** with no
> pressure event — the host is nowhere near the condition the rule protects against. The guidance is
> therefore now understood as a *review trigger*, not a hard cap. The `backend 2048M → 1792M` lever
> (returns 256 MB) remains un-taken and is still the first move if real headroom is ever needed.
>
> **Plane-wide follow-up, same PR — nine more Go services budgeted at zero capacity cost.** The bump
> above prompted a check of every Go service in the stack; only `prometheus` and `alloy` carried a
> `GOMEMLIMIT`. All nine others (`grafana`, `loki`, `tempo`, `cadvisor`, `alertmanager`,
> `node-exporter`, both `postgres-exporter`s, `redis-exporter`) now carry one at 75 % of their limit.
> **No limit was raised**, so the sum stays at the 14.02 GB above. `socket-proxy` is HAProxy, not Go.
>
> **Correction, same day — those nine were preventive, not remedial.** The sweep first read
> `alertmanager` at 95.0 % of its limit, `node-exporter` at 83.8 % and both `postgres-exporter`s near
> 78 %, and attributed that to the same runtime ratchet as `blackbox-exporter`. Decomposing the metric
> disproved it: `container_memory_working_set_bytes` counts **active file pages**, including each
> service's own memory-mapped binary — clean, reclaimable, and no OOM risk whatsoever. On
> `container_memory_rss` (anonymous memory, the figure that actually predicts OOM) the same services
> read **48.3 %, 33.4 % and ~42 %**. On the small exporters the mapped binary is roughly half the
> working set. **`blackbox-exporter` at 95.3 % anon was the only genuine capacity case in the entire
> stack** — which is what the +32 MiB above bought. The `GOMEMLIMIT` values remain correct and are kept
> as defence-in-depth (all sit 1.5–4× above measured anon, so they constrain nothing today).
>
> **Consequence for this ADR's capacity rule: never open a capacity decision on a container-memory
> alert alone.** Split `container_memory_rss` from `container_memory_mapped_file` first — the method
> and the measured table are in `monitoring/README.md` → "Go services". Page cache expands to fill
> whatever limit it is given, so a working-set alert driven by it is unfixable by more RAM; `alloy`'s
> 192M → 256M → 384M → 512M history is that mistake four times over. The alert itself was fixed in the
> same PR: `ContainerWorkingSetHigh` → **`ContainerMemoryHigh`**, now keyed off `container_memory_rss`,
> so it no longer counts a service's own mapped binary against its limit.
>
> Two deliberate exceptions, both recorded in the compose comments rather than silently normalised:
> `prometheus` keeps its 900MiB (88 % of its limit, looser than the rule) because its measured live
> heap is 157.4 MiB against a 43.6 % working-set peak — tightening it would only risk GC pressure
> during WAL replay. `alloy` was **not** given more memory: its 94.8 % is 183.9 MiB of mapped binary,
> re-attributed to its cgroup when the v1.18.0 image pull reached prod on 2026-07-31, against a flat
> 190–217 MB `rss`. Not a leak, not a shortfall — see its compose block.

### Capacity re-tuning against the CPX42 baseline (#937, 2026-08-03)

The data-driven pass this ADR's capacity rule had been waiting for. Seven days of production
metrics, read-only, after the CPX42 rescale and one week of Phase-2 monitoring. It replaces the
**ratio-derived** sizing in section 4 above with **measured** sizing wherever the two disagreed —
and they disagreed most where this ADR guessed hardest.

**The finding that drove it: the databases were sized for a dataset that does not exist.** The
entire basetool database is **107.7 MB** and the Keycloak database **39.4 MB**. Section 4 scaled
`shared_buffers` and the container limits up alongside a projected "5000-account working set",
reasoning from user count rather than from bytes — but Keycloak stores little per user, and the app
data had not grown the way the projection assumed. `db-backend` was carrying a **2048 MB limit and a
512 MB buffer pool for a 108 MB database**, against a measured working-set peak of 295 MB. The
cache-hit ratio was **99.990 %** and **zero** temp files were written in seven days, so nothing was
being bought with the excess.

|    service    |                                      change                                       |                                  measured basis                                   |
|---------------|-----------------------------------------------------------------------------------|-----------------------------------------------------------------------------------|
| `db-backend`  | **2048M → 1536M**, `shared_buffers` 512→384MB, `effective_cache_size` 1365→1024MB | working set 295 MB (14 % of limit), DB 107.7 MB, hit ratio 99.990 %, temp files 0 |
| `db-keycloak` | **768M → 512M**, `shared_buffers` 192→128MB, `effective_cache_size` 512→384MB     | working set 87 MB (11 %), DB 39.4 MB, hit ratio 99.998 %, temp files 0            |
| `frontend`    | `cpus` **1.0 → 2.0**                                                              | **1506 s throttled / 7 d** — the worst absolute stall in the stack                |
| `ingest`      | `cpus` **1.0 → 1.5**                                                              | 76.7 % peak throttle ratio, the highest anywhere                                  |
| `redis`       | `cpus` **0.5 → 1.0**                                                              | 545 s throttled on a single-threaded command loop                                 |
| `npm`         | `cpus` **0.5 → 1.0**                                                              | 20.0 % peak throttle at the TLS-terminating edge                                  |

**Sum of limits: 14 352 MiB (14.02 GiB) → 13 584 MiB (13.27 GiB)**, i.e. **768 MiB returned**, back
under this ADR's `~14 GB` review trigger with ~1.97 GiB of host headroom. The CPU quota sum rises
9.5 → 12.0 on 8 physical vCPU; that is deliberate overcommit of a *burst ceiling*, not a
reservation, on a host whose 21 containers together average **0.256 cores (3.2 %)**.

**`work_mem` was the trap worth naming.** It is unchanged at 8MB/4MB — but the reason is a
measurement, not caution: `pg_stat_database_temp_files` was **0** across the whole window, which is
direct evidence no query ever spilled. The related trap is on the container side: the textbook
`max_connections × work_mem` product (150 × 8MB = 1200 MB) must **not** be used to size a DB
container here. It presumes every pooled connection simultaneously runs a sort that fills
`work_mem` — an analytics workload shape this OLTP app does not have, refuted directly by
`temp_files = 0` at a connection peak of 31. Size DB containers from the working-set peak.

**Deliberately kept, each against a measurement that would have allowed less:**

- **`backend` 2048M.** The `2048M → 1792M` lever identified above stays **un-taken**; #937 returned
  its 768 MiB from the Postgres containers instead, so the busiest JVM's ceiling never had to move.
  The lever remains available. Re-measured: heap 684 MB committed / 647 used of the 1167 MB ceiling,
  `rss` 55.6 %, GC overhead 0.14 % peak.
- **`keycloak` 2560M.** Working set 922 MB (36 %), heap 400 MB used of ~1792. Not reclaimed because
  the *limit is the heap knob* — the image derives the heap from ~70 % of it — so cutting the
  container also cuts the burst headroom the 5000-account target bought.
- **HikariCP `maximum-pool-size: 100`.** Active peak **10**, pending peak **0**, timeouts **0**. A
  lazy pool's unreached ceiling costs nothing, while the ADR-0078 burst it guards against is a spike
  a quiet baseline week does not contain and whose failure mode is a tool-wide breaker outage.
- **Redis `maxmemory 384mb` / 512M cgroup.** Used peak **7.07 MB (1.8 %)** across 8191 keys, zero
  evictions. `noeviction` makes the ceiling a hard failure boundary (a refused write is a failed
  login), so ADR-0079 bought that headroom on purpose; the measurement confirms the purchase.
- **Every monitoring-stack memory limit**, and the deliberate absence of CPU quotas there — throttling
  an exporter would put gaps in the series used to diagnose an incident, precisely when the host is
  busiest. Rationale now recorded in `docker-compose.monitoring.yml`.

**Two watch items this baseline surfaced, neither actionable yet.** `tempo`'s live heap has more
than doubled (208.8 → **466.8 MiB**), leaving it only **1.65×** under its `GOMEMLIMIT` where every
other Go service holds ≥3.7× — it tracks trace volume, so re-measure it before anything else when
tracing changes. And `npm` now has the highest `rss` ratio of the non-JVM app containers (66.3 % of
256M), making it the first to need room if the edge ever gains a module.

**Consequence for the capacity rule.** It stands unchanged and is now *satisfied with margin* rather
than exceeded. What #937 adds is a method constraint: **never size from a ratio applied to the
container limit** — that is how `shared_buffers` ended up at 4.75× the entire database, and it is the
same error class as re-applying `MaxRAMPercentage` to a raised limit (PR #1419) or bumping `alloy`
four times against page cache. Size from a measured peak, state the multiple, and record what would
trigger a re-review.

### 5. Preserve the 30-day rolling login (Redis sized instead of TTL cut)

The 30-day Spring-Session idle window is a **deliberate** design premise (it carries the OAuth2
refresh token behind a rolling login — ADR-0079, the `docker-compose.yml` redis rationale).
Shortening it to reduce Redis growth would contradict that decision and change auth UX, so the
session TTL is **kept** and the growth is absorbed by the Redis capacity bump in (4) instead. If the
owner later wants a shorter login window, that is a separate product decision.

## Consequences

- The daily sync is a bounded off-peak burst even at 5000 accounts; Keycloak is no longer hammered.
- Role resolution stays faithful (directly-assigned realm roles), and the REQ-SEC-043 completeness
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

