# ADR-0194 — The weekly cleanup is runtime-aware, and it does not prune volumes on Podman

- **Status:** Accepted
- **Date:** 2026-09-21
- **Deciders:** @greluc
- **Related:** ADR-0163 (rootless Podman) · ADR-0188 (the host bootstrap is an Ansible role) ·
  ADR-0049 (config as a promotable OCI artifact) ·
  [`docs/specs/observability.md`](../specs/observability.md) (`REQ-OBS-*`) ·
  [`docs/arc42/07-deployment-view.md`](../arc42/07-deployment-view.md) §7.4a ·
  `scripts/container-cleanup.sh` · `scripts/lib/container-runtime.sh`

## Context

`scripts/docker-cleanup.sh` prunes stopped containers, unused images, the build cache, unused
networks and anonymous volumes once a week. It called `docker` directly and was **the only
operational script that did not go through `lib/container-runtime.sh`** — the abstraction
`deploy.sh`, `backup.sh` and `restore-drill.sh` all use.

Measured on the migration target on 2026-09-21:

| checked | result |
| --- | --- |
| `command -v docker` | **nothing** — the role installs `podman`, not `podman-docker` |
| `systemctl is-enabled iri-docker-cleanup.timer` | **enabled** |
| a `docker_cleanup` series in any textfile | **none** |

So on the post-cutover host the weekly run fails at its first command, nothing is reclaimed, and
`DockerCleanupStaleOrMissing` — whose expression is *stale for more than eight days **or
`absent()`*** — starts firing an hour after the cutover and can never be satisfied. It sits in the
same alert group as the backup and restore-drill staleness alerts, so the cost is not the noise: it
is that the group teaches its reader to ignore it, and the two alerts beside it are the ones that
say the backups have stopped.

**The obvious fix is a trap.** Three of the five steps translate command-for-command. Two do not,
and a mechanical rename would have been *worse than the broken job it replaced*.

## Decision

**We will make the job runtime-aware rather than runtime-specific, skip the two steps Podman cannot
express safely, and fix the leak one of them was absorbing at its source.**

1. **`scripts/container-cleanup.sh` replaces `scripts/docker-cleanup.sh`**, sources
   `lib/container-runtime.sh`, and issues every command through the detected `RT_CLI` — which on a
   rootless host is `sudo -n -u <service user> podman`, not a bare binary. Its prose is English
   (the previous script's was German, which the repository's English-only rule forbids).

2. **Volume pruning is Docker-only.** Docker's `volume prune`, without `--all`, removes **only
   anonymous** volumes. Podman has no such distinction — the command is documented as *"Volumes
   that are not currently owned by a container will be removed. Note all data will be destroyed"*,
   and its only filter is `label=`. Measured on the target, `podman volume ls --filter
   dangling=true` listed **`edge-certs` and `edge-acme-state`**: the edge's TLS material and the
   ACME account, which are in no snapshot and were carried across by hand. They are "dangling"
   whenever the stack is down, which is exactly when a weekly maintenance job runs.

3. **The leak that step was absorbing is fixed where it is made.** `rt_rm_force` now removes a
   container's **anonymous** volume with the container (`rm -f -v`; `-v` never touches named
   volumes). The restore drill's throwaway Postgres declares `VOLUME /var/lib/postgresql/data`, so
   every run left one behind — 156 MB, measured, from a single run on the target. **Nobody had ever
   seen it** because on Docker the weekly anonymous-only prune swept them up. `rt_rm_force` is used
   by the restore drill and nothing else, and the production databases are bind mounts rather than
   volumes, so the change cannot reach them.

4. **Build-cache pruning is Docker-only.** On Podman `builder prune` is an alias for `image prune`
   — running both would be the same step twice, not a build-cache sweep. Nothing is built on this
   host; images arrive pre-built and signed.

5. **The metric, the unit, the log file and the alert are renamed** to `container-cleanup`, and
   **the alert accepts both metric names during the transition**, with `container_cleanup_rename_test.yml`
   locking all four combinations. This is not belt-and-braces: **the alert rules ride the config
   bundle (ADR-0049) while the scripts are installed by the Ansible role (ADR-0188)**, so the two
   halves of the rename reach a host independently and in either order. Alloy watches both log
   paths for the same reason. Both halves are removed once every host has run the role.

## Consequences

- The job runs on both runtimes and the alert becomes satisfiable again.
- **Podman reclaims less than Docker did, and that is correct rather than a partial run.** The
  alert's description says so, because "why is the reclaimed figure smaller on the new host" is
  otherwise a reasonable thing to investigate for an hour.
- Anonymous volumes on the Podman host are no longer swept by anything. They are no longer *created*
  by the only thing that was creating them either; if a future container leaves one, it will be
  found by hand and the trade is revisited then, with the name in view.
- `basetool_docker_cleanup_*` survives in the alert expression and in Alloy's path list as dead
  weight with a removal condition. A rule that accepts a name nothing writes is how a rename
  quietly never finishes.

## Alternatives considered

- **`podman volume prune --filter label=…`, with our anonymous volumes labelled** — rejected. It
  would require every container that can create an anonymous volume to be labelled at creation, the
  label would have to survive image changes we do not control, and a missed label fails *open*: the
  volume is pruned, not kept. The failure mode is the one that destroys the certificates.
- **Removing volumes whose name is 64 hex characters** (Podman's anonymous-volume shape) — rejected
  as a heuristic standing between a maintenance job and irreplaceable data. It is very probably
  correct; "very probably" is the wrong standard when the downside is re-issuing into Let's
  Encrypt's duplicate-certificate limit during an outage. Fixing the producer removed the need.
- **Leaving the job disabled on Podman and silencing the alert** — rejected. Silencing an alert to
  match a broken job is how the condition it watches for arrives unannounced, and disk on a
  single-host deployment is exactly such a condition.
- **Keeping the name `docker-cleanup` and only changing the body** — rejected. The name is what an
  operator reads at 03:40; a unit named for a runtime the host does not have is a false statement
  in the place where a false statement is most expensive.
- **Renaming the `ops-cleanup` log stream label to match** — rejected. The alert and the dashboard
  both send a reader to `{app="ops-cleanup"}`, and a label rename would break every historical
  query for a cosmetic gain. The label is deliberately shorter than the unit name already.

## Amendment 2026-09-22 — Podman only, one name (OPS-SIMP-01, OPS-SIMP-02)

The job stopped being runtime-aware the day after this ADR: every Docker arm left the operational
scripts ([ADR-0202](0202-compose-stays-the-source-of-the-units-and-the-scripts-speak-podman-only.md)),
so `container-cleanup.sh` now runs the three Podman steps and nothing else. The two Docker-only steps
— `builder prune` and the anonymous-only `volume prune` — are gone from the script rather than
skipped, together with `IRI_CLEANUP_BUILDER_UNTIL`, `IRI_CLEANUP_PRUNE_VOLUMES` and the unit's
`DOCKER_CONFIG`. The decision above stands unchanged: **nothing prunes volumes**.

The removal condition of the transitional double name was met — both Rocky hosts have run the role,
and the retired Docker host never will again — so `basetool_docker_cleanup_*` left the alert
expression and `iri-docker-cleanup.log` left Alloy's path list the same day (arc42 §11.1). The role
still removes the old units wherever it finds them.
