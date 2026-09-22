# ADR-0202 — The compose files stay the source of the units, and the operational scripts speak Podman only

- **Status:** Accepted
- **Date:** 2026-09-22
- **Deciders:** @greluc (the Docker-era removal was part of the operations audit's scope, 2026-09-22)
- **Related:** [ADR-0163](0163-the-container-runtime-becomes-rootless-podman-on-debian-13.md) ·
  [ADR-0188](0188-the-host-bootstrap-is-an-ansible-role.md) ·
  [ADR-0194](0194-the-weekly-cleanup-is-runtime-aware-and-drops-volume-pruning-on-podman.md) ·
  [`docs/specs/deployment-delivery.md`](../specs/deployment-delivery.md) (`REQ-OPS-001`, `REQ-OPS-014`) ·
  [`docs/specs/observability.md`](../specs/observability.md) (`REQ-OBS-011`)

## Context

Production moved from a Docker Compose host to rootless Podman with Quadlet units on 2026-09-22
(ADR-0163). The migration was built to be reversible, so every operational script — `deploy.sh`,
`backup.sh`, `restore-drill.sh`, `container-cleanup.sh` — went through `scripts/lib/container-runtime.sh`,
which carried a Docker arm beside every Podman one. The monitoring stack kept cAdvisor and a
docker-socket proxy for the Docker host, the recording rules carried a cAdvisor leg beside each
Podman-exporter leg, and the cleanup alert and Alloy watched two names during the job's rename.

After the cutover every one of those Docker branches is code that no production host runs:

- the Rocky hosts have no `docker` binary and no Docker socket;
- the retired Docker host is **shut down, not decommissioned** — its way back (the archived cutover
  runbook) is to power it on with the configuration and scripts **it already has on its own disk**.
  A release never reaches it, so nothing the repository changes today could help or harm that path;
- the local and test stacks run the compose files directly with `docker compose` and never went
  through the seam.

Each Docker branch still had to be kept green in `deploy.test.sh`, reasoned about in every change,
and read past by whoever debugs the Podman path at night. The operations audit of 2026-09-22 found
one real defect hiding in exactly that doubled surface: `deploy.sh`'s stateful-infrastructure pin
check matched the compose spelling of an image and never the qualified spelling the units carry, so
the gate it existed for had never fired under Quadlet.

The question the removal raises is what the compose files are **for** once no production host runs
them. Two answers were on the table: keep them as the source the units are generated from, or make
the units the source and let compose go.

## Decision

1. **The compose files stay the single source of the production units.** `scripts/generate-quadlet.py`
   keeps translating `docker-compose.yml` and `docker-compose.monitoring.yml` into `quadlet/`, and
   `--check` keeps gating that the committed units match. What compose cannot express — the
   `Internal=true` data networks (ADR-0162 amendment), the stop grace, `RunInit=`, the ulimits — the
   generator adds from its own tables or native keys, and **refuses** what it cannot translate
   rather than dropping it. The local and test stacks keep running the same files, so a developer
   and production still start from one description of the stack.
2. **The operational scripts speak rootless Podman only.** Every Docker arm leaves
   `lib/container-runtime.sh`; `RT_BACKEND=docker` is refused by name as retired rather than silently
   ignored. With it go the compose-only machinery `deploy.sh` carried for the Docker host — the
   `networks:` block diff and the gated clean-slate recreate (#974), `RT_COMPOSE_FILE` /
   `RT_MONITORING_FILE` — and the cleanup job's two Docker-only steps (ADR-0194 amendment).
3. **What the scripts share lives in one library.** `scripts/lib/common.sh` holds `log`, `fail`,
   `read_env` and the atomic textfile write the four scripts had each copied, and the monitoring
   services are derived from the unit directory instead of listed, so a new monitoring unit cannot
   be forgotten by the recreate.
4. **The Docker-only monitoring goes.** cAdvisor, the docker-socket proxy and its network leave
   `docker-compose.monitoring.yml`; the recording rules keep the Podman-exporter legs only; the
   cleanup alert and Alloy keep the one name the role now writes (arc42 §11.1). Alert text names
   `systemctl --user` and `podman`, not `docker`.

## Consequences

- A change to the stack is still written once, in compose, and reaches production through the
  generator and its guards. The generator's refusals remain the place where "compose cannot say
  this" is decided, deliberately and in review.
- The scripts, their tests and the rules keep one path each. The stateful-infra pin check now reads
  the units, matches both image spellings and is tested against the unit form.
- **There is no Docker production path in the repository any more.** Standing up a Docker host from
  a current checkout would need the removed arms back. That is accepted: the only Docker host is the
  retired one, and its way back does not run a current checkout.
- The local and test stacks are unaffected: they never used the seam, and `docker compose` against
  the same files is what they did before.
- The compose files carry production-only settings (volume modes, network comments) that only the
  generator reads. That was already true and is now the whole reason those settings are there — the
  files say so where it matters.

## Alternatives considered

- **Make the units the source and delete the compose files** — rejected. The local and test stacks
  would need a second description of the stack to stay in step with by hand, and the generator's
  guards (the read-only config tree, the internal-network publish check) would lose
  the one input they check against. The drift the generator exists to prevent would move to the
  developer's machine.
- **Keep the Docker arms "for the way back"** — rejected. The way back is the retired host with its
  own scripts; a Docker arm in today's scripts serves no host, costs every change a second path, and
  had already hidden one defect.
- **Remove the arms but keep cAdvisor as a second container-metrics source** — rejected. Its
  rootless-Podman support is closed upstream as not planned, so the generator never translated it
  into a unit; on the Podman host its recording-rule legs were always empty, and an empty leg is how
  a later reader concludes a metric is covered twice when it is covered once.
