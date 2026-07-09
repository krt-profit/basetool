# ADR-0083 — Deploy-bot distinguishes runtime-health drift from release drift (targeted restart, not rollback)

- **Status:** Accepted
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
