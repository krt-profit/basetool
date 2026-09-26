# ADR-0083 — Deploy-bot distinguishes runtime-health drift from release drift (targeted restart, not rollback)

- **Status:** Accepted — amended 2026-09-25 (the heal is one restart window; see the amendment below)
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
  apply → health-gate → rollback path unchanged.
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
