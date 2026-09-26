# ADR-0213 — A release that moves the Keycloak provider JAR costs one outage, not two

- **Status:** Accepted — supersedes the *ordering* of [ADR-0055](0055-keycloak-spi-jar-as-promotable-oci-artifact.md) (the JAR swapped only after the app apply passed its gate) and that ADR's 2026-09-25 amendment; ADR-0055's artifact, signing and lock-step promotion stand
- **Date:** 2026-09-25
- **Deciders:** @greluc
- **Related:** spec REQ-OPS-007 · REQ-OPS-003 · ADR-0055 · ADR-0083 · PR #2072 · runbook `docs/deployment.md` → *Keycloak provider JAR*, *What happens on the host*

## Context

Since ADR-0055, `deploy.sh` has applied a release in two steps when the provider JAR moves: first
the app images, the config and the units, behind the health gate; then, only after that gate, the
JAR, with a Keycloak recreate and a second, Keycloak-scoped gate. The order was chosen so a failed
gate could tell a bad JAR from a bad app release, and a failed JAR could be reverted alone while the
healthy app release stayed.

Under Quadlet that order costs a second outage. `backend` `Requires=` keycloak and `frontend` and
`ingest` require backend, so the Keycloak recreate takes the whole app down again. On production on
2026-09-25, v1.12.0: the edge served maintenance-page 5xx from 17:39 to 17:43 UTC, and about two
minutes of it were the JAR step, after the app apply's own restart. PR #2072 made that second step
wait for the stack it takes down, and recorded the one-outage alternative as an open owner decision.

The same investigation left a second finding open: the app apply restarted each re-defined unit
with its own `systemctl --user restart`, one after the other. A restart travels along `Requires=`,
and `systemctl` returns when the named unit is up while the units that require it are still being
started. A `restart` of such a unit is not merged into its running start job — systemd turns that
job into a restart and runs it again — and a unit already back up is simply restarted a second time.
A release that re-pinned backend, ingest and frontend therefore restarted ingest and frontend twice.

## Decision

We will apply the provider JAR **as part of the release apply**, so a release costs one restart
window whatever it moves.

- **Staging.** `deploy.sh` extracts the promoted JAR into the state directory before anything on
  the host changes, inside the pre-gate guard of REQ-OPS-003: a failed extraction is recorded like
  every other pre-gate failure (a `FATAL` line, backoff, `DeployFailed`) and has nothing to undo.
- **Swap.** After the config delivery, the pin and the pull, as the last step before the apply, it
  snapshots the live JAR, installs the new one and marks keycloak as re-defined.
- **Apply.** The apply takes every re-defined unit — re-pinned app services, units the config
  replaced, keycloak for a moved JAR — down in **one** `systemctl --user stop`, which takes what
  requires them down with them, and then **starts** the stack in dependency order, waiting for each
  unit. Nothing in the apply is `restart`ed any more. Each unit therefore stops once and starts once,
  keycloak on the new JAR before backend, and one health gate covers all of it. A JAR-only release is
  exactly this apply with only keycloak re-defined.
- **Rollback.** A failed gate rolls back **everything the release changed, together**: the app
  digest pin (record and drop-ins), the config tree and units, and the previous JAR — then applies
  once more the same way. The run is recorded as a rolled-back target (`DeployRolledBack`, the
  bad-digest backoff); the marker is not advanced. There is no state in which new app images run on
  the old JAR or old images on the new JAR, except the operator's own manual steps.
- **Attribution.** The deploy log names what the release changed (`release parts: app images […] ·
  unit definitions […] · config bundle: yes|no · provider JAR: yes|no`) and, on a failed gate, the
  units that did not come up in start order and the one inference it can make: when keycloak is the
  first unit that failed and the JAR is the only change to keycloak, the JAR is the likely cause —
  and the cause when it is the release's only change. Otherwise it says that it cannot tell.

A Keycloak **image** change stays operator-gated by REQ-OPS-006; this decision does not touch it.

## Consequences

- **Easier:** one maintenance window per release instead of two — for v1.12.0 about two minutes
  less of maintenance page. Each unit is restarted once per release, not two or three times. The
  post-gate JAR step, its own rollback and its own recreate/wait pair are gone, so there is one gate
  and one rollback path to reason about. A failed JAR extraction now pages instead of ending the run
  in silence.
- **Harder / costs accepted:** **blame is weaker.** A failed gate can no longer prove whether the JAR
  or an app image broke it: a new frontend that cannot log in through a new JAR fails the same gate
  either way, and both are rolled back. The log narrows it where it can (keycloak itself failing on
  an otherwise unchanged keycloak) and says so where it cannot; the rest is the operator's reading of
  the unit logs. A bad JAR now also rolls back an app release that would have been healthy on its
  own — the owner accepted that as the price of one outage (2026-09-25). A re-promotion without the
  JAR change, or with a fixed JAR, is the way forward in that case.
- **Follow-up:** the runtime-health restart (ADR-0083) still restarts unhealthy services one by one
  with `restart` and waits only for the named unit; it shares the `Requires=` shape and is not
  changed here.

## Alternatives considered

- **Keep ADR-0055's order** (JAR after the app gate) — rejected by the owner: two outages per
  JAR-moving release for the sake of exact blame, when blame can be narrowed from the log and a
  combined rollback is safe.
- **Swap the JAR first and gate keycloak alone before the app apply** — rejected: it is still two
  restart windows (keycloak's restart takes the app down, then the app apply restarts it again), and
  it would still need to roll back an app release it had not yet applied.
- **Merge the restarts into one `systemctl restart a b c`** — rejected: `systemctl` sends one restart
  job per unit, each its own transaction, so a dependent the first restart is already starting is
  still restarted again by its own job. A stop merges with any pending job, and a start joins one;
  a restart does neither.
- **Start instead of restart for units a chain restart already covers** — rejected: it needs the
  `Requires=` graph in the deployer to know which units the chain covers, and it still races a
  dependent that systemd is starting on its own while `systemctl` returns.
