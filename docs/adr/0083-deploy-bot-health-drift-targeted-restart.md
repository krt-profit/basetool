# ADR-0083 — Deploy-bot distinguishes runtime-health drift from release drift (targeted restart, not rollback)

- **Status:** Accepted — amended 2026-09-25 twice (the heal is one restart window; a failed structural re-apply does not roll back) and 2026-09-26 (`deploy.sh --reapply`; a failed re-apply backs off with the heal's durations) — see the amendments below
- **Date:** 2026-07-09
- **Deciders:** @greluc
- **Related:** `scripts/deploy.sh` · REQ-OPS-016 (`docs/specs/observability.md`) · ADR-0072 (deploy textfile metrics) · ADR-0084 (readiness health-group) · the 2026-07-09 native-thread exhaustion incident

## Context

`deploy.sh` runs every ~5 minutes from `iri-deploy.timer`. It resolves `:stable` to digests, and
when the running stack does not match the last-deployed target it treats that as **drift** and
re-applies via `docker compose up --wait`; if the health gate fails within `IRI_HEALTH_TIMEOUT` it
**rolls back** to the previous digest pin and writes `basetool_deploy_last_rollback_timestamp`,
which trips the `DeployRolledBack` alert.

On 2026-07-09 the already-running, unchanged v1.2.3 backend exhausted its container `pids` cap
(`OutOfMemoryError: unable to create native thread`), which broke `/actuator/health/readiness` and
flipped the container to `unhealthy`. `running_stack_drift()` reported that `unhealthy` state as
drift, so deploy.sh re-applied and — because the target digests were **identical to what was already
running** (no promotion) — "rolled back" to the same image, which was equally unhealthy. This looped
for ~2 hours with exponential backoff, firing `DeployRolledBack` + `ContainerRestartLoop` +
`HttpLatencyP95High` repeatedly. None of these named the real cause; the "rollback" was a fiction
(there was no newer release to revert), and re-creating the container against the same image could
never fix a runtime fault.

The root problem: deploy.sh conflated two very different conditions — **a wrong release** (the
running image differs from the target, or a container is missing) versus **a runtime fault on the
correct release** (the right image is running but the container is unhealthy). Only the first is a
deploy problem; the second is an application-runtime problem that a rollback cannot address.

## Decision

`running_stack_drift()` now **classifies** each divergence as `structural` or `health`:

- **structural** — a service has no container, OR a running container's image does not match the
  target digest. This is a genuine release mismatch; deploy.sh takes the existing full
  apply → health-gate → rollback path unchanged. *(Amended 2026-09-25: the apply and the health
  gate, yes — the rollback, no. A failed re-apply of the deployed release is recorded like a failed
  heal; see the second amendment below.)*
- **health** — a container is present and running the **target** image but is not
  `running/healthy` (unhealthy / restarting / exited / …). The deployed *release* is correct; only
  the runtime is sick.

When a drift report contains **only** `health` divergences (no `structural` line), deploy.sh takes a
new **targeted-restart** path instead of the apply/rollback path:

- It restarts **only** the affected service(s) with `docker compose up -d --no-deps
  --force-recreate --wait <svc>` — no re-pull, no cosign re-verify (the image is already the
  verified, running target), no full-stack recreate, and **no release rollback**.
- It is bounded by its own short exponential backoff (`IRI_HEALTH_RESTART_BASE=300s`,
  `IRI_HEALTH_RESTART_MAX=3600s`, state in `/var/lib/iri/health-restart.digests`) so a container
  that will not recover is not force-recreated every tick.
- It writes a **distinct** signal to `deploy-health.prom`, never a deploy `rollback`/`failure`:
  `basetool_deploy_last_stack_healthy_timestamp` (a heartbeat stamped on every healthy tick and on a
  restart that restores health) and `basetool_deploy_last_health_restart_failed_timestamp` (stamped
  when the targeted restart does not restore health). The `DeployHealthRestartFailing` alert fires
  while the failed stamp is newer than the healthy one and self-clears the moment a tick observes the
  stack healthy again.

A report that mixes `health` and `structural` divergences is treated as **structural** (a wrong
release must be corrected before health can be judged), so the safety of the existing re-apply path
is never weakened.

## Consequences

- A transient runtime fault on the deployed release (thread/GC stall, a dependency blip, an OOM) now
  self-heals via a single targeted service restart in minutes, instead of a multi-hour full-stack
  rollback storm. The `DeployRolledBack` metric stays **truthful**: it fires only when a genuinely
  promoted release failed its health gate and was reverted — never for a runtime blip on an unchanged
  release.
- A runtime fault that a restart cannot fix surfaces as `DeployHealthRestartFailing` (critical) —
  a clear "the runtime is broken on the deployed release" signal, distinct from both the
  deploy-outcome alerts and the leading `JvmThreadsHigh` indicator, pointing the operator at the app
  (threads/memory/dependencies) rather than at the release pipeline.
- Startup ordering, the digest-pin idempotence, the bad-digest backoff, the config/keycloak-spi
  choreography and the supply-chain cosign gate are all unchanged — only the response to an
  at-target-but-unhealthy container changed.
- The targeted restart deliberately does not raise the container `pids` cap or otherwise mask the
  underlying fault; combined with the hourly Keycloak-sync cadence and the `JvmThreadsHigh` alert
  (this same change set), the goal is to make such a fault visible and non-amplified, then fixed at
  the source.

Tested by `scripts/deploy.test.sh` (the targeted-restart success, restart-failure signal,
restart backoff, and mixed-drift-is-structural scenarios).

## Amendment — 2026-09-25: the heal is one restart window, resolved only when the chain is back

**Context.** Under Quadlet (since 2026-09-22) the targeted restart became `systemctl --user
restart <svc>.service`, one service after the other. The application units are chained with
`Requires=` — backend requires db-backend and keycloak, frontend requires backend, keycloak and
redis, ingest requires backend and redis — and systemd restarts every unit that requires the one
restarted. `restart` returns when the **named** unit is up, while the dependents' start jobs are
still queued. Measured with the stubbed systemd of `scripts/deploy.test.sh` (the model #2073
measured under systemd 255):

| Case | Before this amendment | After |
| --- | --- | --- |
| backend unhealthy | backend restarted; ingest and frontend restarted with it but **still stopped, with no container, when „health drift resolved" and the healthy heartbeat were written** | backend, ingest, frontend started once each, then resolved |
| backend and frontend unhealthy | backend 1, ingest 1, **frontend 2** (its own restart ran after backend's had brought it back) | 1 each |
| frontend (or ingest) unhealthy | frontend restarted once, nothing else | frontend started once, nothing else |
| keycloak unhealthy | not detected: the drift check probes only backend, frontend and ingest | unchanged — see below |
| frontend and ingest have no container | structural re-apply: a `start` of each, once (already so since #2073) | unchanged |
| backend has no container | structural re-apply: a `start` of backend, once | unchanged |

That is the same defect #2072 found in the provider-JAR step and ADR-0213 removed from the release
apply: success written over a half-down stack, and a unit restarted again by its own job after a
restart travelling along `Requires=` had already brought it back.

**Decision.** The heal is the release apply's restart window (ADR-0213), with the unhealthy
services standing in for the re-defined ones — `rt_heal_stack` in `scripts/lib/container-runtime.sh`,
which is `rt_apply_stack` with those services marked:

1. **one** `systemctl --user stop` naming the unhealthy services. systemd takes down with them what
   `Requires=` them, once, and nothing they require — an unhealthy frontend stops frontend alone, an
   unhealthy backend stops backend, ingest and frontend, and keycloak is never touched by either;
2. a `start` of every stack unit in stack order, each waited for until healthy (`Notify=healthy`).
   What the stop took down comes up once; an active unit returns at once and is not restarted; a
   unit that was down for another reason is started too.

„health drift resolved", the removal of the backoff record and the
`basetool_deploy_last_stack_healthy_timestamp` heartbeat are written **only when every start
returned healthy**. Otherwise the log names what did not come up, in start order
(`health drift: did not come up, in start order: […]`), and the failure is recorded exactly as
before: `health-restart.digests` with its backoff and
`basetool_deploy_last_health_restart_failed_timestamp`, which `DeployHealthRestartFailing` reads.
The metric names, their semantics and the alert rules are unchanged.

A report that mixes `health` and `structural` findings stays structural, as decided above. The
re-apply's start of the unhealthy service's active unit is a no-op, so that service is **not**
healed by the re-apply; the next tick finds it health-only and heals it. What changed: such a
re-apply no longer stamps the healthy heartbeat, nor clears the heal's backoff record — until
2026-09-25 it did both, which could clear an active `DeployHealthRestartFailing` over a container
that was still sick. It logs `stack-health heartbeat NOT stamped: […]` instead.

**Not decided here.** Keycloak (and the databases and redis) are outside the drift check, so an
unhealthy keycloak is not healed automatically, before or after this amendment. Healing it would
restart the whole application through `Requires=` — about two minutes of maintenance page on
production — on the strength of one failed health probe; whether the tick may do that is the
owner's call and is not taken implicitly by this change. `rt_heal_stack keycloak` already does the
right thing if it is ever taken (one stop naming keycloak alone, then the ordered start).

**Consequences.** One outage window per heal instead of a partially overlapping sequence of them,
and no false „resolved" while dependents are still starting. The window is as long as the slowest
chain the stop took down — for an unhealthy backend, backend's start plus the slower of ingest and
frontend, the same as before but now waited for. Tested by `scripts/deploy.test.sh`
(`scenario_heal_*`, `scenario_missing_*`, `scenario_mixed_drift_does_not_stamp_an_unhealthy_stack_healthy`)
and `scripts/container-runtime.test.sh` (`rt_heal_stack`).

## Amendment — 2026-09-25: a structural re-apply is not a release, and a failed one does not roll back

**Context.** The decision above sent a structural divergence down the release path "unchanged",
rollback included. That path saves the live digest pin as `previous-digest-pin.yml` before it writes
the new one, and — when it delivers config — snapshots the live tree as `config-previous/`. On a
re-apply the target is the release already deployed, so both saves copied the deployed release over
the anchors that named the one before it (the pin on every re-apply; the tree on a host whose unit
files were gone). A re-apply that then failed its gate "rolled back" to the release it was on,
stamped `basetool_deploy_last_rollback_timestamp`, and paged `DeployRolledBack` for a release that
had shipped — the 2026-07-09 fiction this ADR removed from the health path, reached by the structural
one — while the real previous release was lost as a rollback target. Found in review of #2075.

**Decision.** A run whose target equals the last-deployed marker (a structural re-apply,
`REAPPLY` in `scripts/deploy.sh`) still verifies, pins, pulls and passes the health gate, but:

1. it rotates **no** rollback anchor — no pin save, no `config-previous/` snapshot, and no
   `config-apply.incomplete` marker, because it mirrors the deployed release over itself and leaves
   one release behind even when it stops halfway. The provider-JAR anchor cannot move: a matching
   marker means the JAR did not change;
2. when it fails — at the health gate or before it — it rolls **nothing** back and restores nothing:
   the release stays, the anchors keep naming the release before it;
3. the failure is recorded as "the running release could not be restored": the bad-digest backoff
   record (keyed to the deployed target, the backoff this path has always honoured) and
   `basetool_deploy_last_health_restart_failed_timestamp`, so `DeployHealthRestartFailing` pages.
   Not `DeployFailed`: that means a promoted release did not ship and clears only on the next
   successful deploy, so a stack that recovers on its own would have kept it firing, while the
   heartbeat comparison clears on the next healthy tick. The gauge keeps its name; its meaning widens
   from "the targeted restart failed" to "restoring the deployed release failed", and the alert text
   says both.

Only a change of target rotates the anchors and can roll back, so `DeployRolledBack` is again what
this ADR's first consequence says it is. A structural re-apply's backoff stays the bad-digest one
(600 s doubling to 6 h), separate from the heal's — sharing the heal's record would let a failed heal
of one service hold back the re-apply of another service's missing container. *(Superseded on
2026-09-26: the re-apply keeps a record of its own, still apart from the heal's, with the heal's
durations — see the next amendment.)*

**Consequences.** A failed re-apply leaves production where it was, pages the runtime signal, and
retries after the backoff (or at once with `--force`); the next release rolls back to the deployed
release, not past it. Tested by `scripts/deploy.test.sh`
(`scenario_reapply_that_fails_keeps_the_anchors_and_rolls_nothing_back`,
`scenario_reapply_that_succeeds_keeps_the_anchors`,
`scenario_release_after_a_reapply_rotates_the_anchor_to_the_deployed_release`,
`scenario_reapply_of_lost_units_keeps_config_previous`); against the deployer before it the first,
second and fourth fail. Specified by REQ-OPS-003 and REQ-OPS-013.

## Amendment — 2026-09-26: `--reapply`, and a failed re-apply backs off like the heal

**Context.** Two things were left over by the amendment above. The documented way for an operator to
force a full re-apply was to delete `last-deployed.digests` — which takes away exactly what makes a
run a re-apply, so that run took the deployed release for a new one and rotated all three anchors
onto it (the loss the amendment above fixed for the drift path). And a failed re-apply was backed off
by the release durations, 600 s doubling to 6 h, from `failed.digests`: a missing container on the
release production is already on could stay missing for hours, and — once an operator can trigger a
re-apply while the tag names a newer release that rolled back — sharing `failed.digests` meant the
re-apply's record would overwrite that release's, or its success delete it, and the next tick would
retry the release at once.

**Decision** (@greluc, 2026-09-26).

1. **`deploy.sh --reapply`** re-applies the deployed release on request. Its target is read from
   `last-deployed.digests`, never resolved from a tag; it takes the re-apply path of the amendment
   above in full — no anchor rotated, nothing rolled back on failure, `DeployHealthRestartFailing`
   rather than a deploy outcome — and re-delivers the config bundle and units and re-stages the
   provider JAR, swapping it in only when the live JAR differs (so a re-apply of an intact stack
   restarts nothing it does not have to). It is refused on a host with no marker, with `--tag`, and
   when the pin record disagrees with the marker. It bypasses the re-apply backoff: that backoff
   throttles the timer, and an operator asking now has chosen to spend the restart window now.
   Deleting the marker is no longer documented as a way to re-apply.
2. **A failed re-apply — drift or `--reapply` — backs off with the self-heal's durations**
   (`IRI_HEALTH_RESTART_BASE` 300 s doubling to `IRI_HEALTH_RESTART_MAX` 1 h), from **its own**
   record, `reapply-failed.digests`. Still not the heal's record, for the reason given above; and no
   longer `failed.digests`, which keeps the release backoff (600 s doubling to 6 h) for releases only
   and is left untouched by any re-apply.
3. **A successful re-apply is not a deploy outcome either.** It stamps the stack-health heartbeat,
   not `basetool_deploy_last_success_timestamp`, which would clear a `DeployRolledBack` or
   `DeployFailed` that a different, still unshipped release raised.

**Consequences.** One command replaces a recipe that destroyed the rollback anchors. A missing
container that a re-apply could not bring back is retried after five minutes instead of ten, and
after at most an hour instead of six. A failed re-apply record from before this amendment (in
`failed.digests`, keyed to the deployed target) is ignored by the re-apply and dropped by the next
successful re-apply or the next release. Tested by `scripts/deploy.test.sh`
(`scenario_reapply_flag_*`, `scenario_failed_drift_reapply_backs_off_with_the_heal_durations`; the
guards `scenario_deleting_the_marker_still_rotates_the_anchors` and
`scenario_failed_release_keeps_the_long_backoff`). Specified by REQ-OPS-003 and REQ-OPS-013.
