# ADR-0049 — Host configuration delivered as a promotable, signed OCI artifact

- **Status:** Accepted
- **Date:** 2026-06-29
- **Deciders:** @greluc
- **Related:** spec [REQ-OPS-001..006](../specs/deployment-delivery.md) · runbook [`docs/deployment.md`](../deployment.md) · builds on the pull-only deploy loop (`scripts/deploy.sh`, `scripts/iri-deploy.{service,timer}`)

## Context

Production runs a deliberately one-directional deploy loop (`docs/deployment.md`): GitHub
Actions build + sign the app images and push them to GHCR; `promote.yml` re-tags a chosen
version to `:stable`; the host's `iri-deploy.timer` polls `:stable`, and `deploy.sh` resolves
it to immutable digests, pins them in a compose override, applies with a health gate, and
rolls back on failure. The host holds **only** a read-only GHCR pull token — no inbound SSH
*for the deploy* (corrected 2026-09-16: the operator's administrative SSH exists and stays;
this clause is about the delivery path, see REQ-OPS-001),
no webhook, no git credential. That "pull, not push" posture is the security spine of the
whole design.

The compose file and its sibling host config (the NPM maintenance page under
`docker/maintenance/`, the Keycloak login theme under `keycloak-theme/`) were **outside** this
loop. They reached the host only by a manual `sudo cp docker-compose.yml /var/iri/code/`
(see the bootstrap and Keycloak-HTTPS sections of the runbook). Two consequences followed,
both confirmed in `scripts/deploy.sh`:

- **The compose file never arrives on its own.** `deploy.sh` only ever *reads* the on-disk
  compose file; nothing fetches it. So a bumped infra image pin (redis/postgres/keycloak/npm)
  merged to `main` cannot reach prod without a hand copy.
- **Even an on-disk change would not be applied.** The idempotence marker was the app-image
  triple `backend|frontend|ingest` only. A pure infra bump leaves those three digests
  unchanged, so the timer-driven `deploy.sh` short-circuits at "no change — already at target
  digests" and never runs `up -d`. The operator therefore had to hand-run
  `docker compose up -d <service>` as well.

A third, latent gap: rollback restored only the digest pin, which carries no infra `image:`
line and not the compose file — so a bad **infra** digest could not be rolled back at all.

The owner asked for infra-container bumps to deploy automatically, **without** weakening any
of the loop's invariants (pull-not-push; deliberate operator promotion; digest pin + health
gate + rollback; no secrets ever delivered to the host; infra images pinned-by-digest in git
and reviewed via Dependabot PRs).

## Decision

We will package the host configuration as a **`basetool-config` OCI image** and ship it
through the **exact** channel the app images already use.

- **Build.** `release-images.yml` builds `docker/config/Dockerfile` — a `FROM scratch` image
  whose only content is an explicit COPY allowlist: `docker-compose.yml`,
  `docker/maintenance/`, `keycloak-theme/` under `/config`. It is tagged with the same scheme
  as the app images, gets the same cosign keyless signature, and a CI step pulls the bundle
  back and **fails the release** if any secret-shaped file slipped in.
- **Promote.** `promote.yml` promotes `basetool-config` in lock-step with backend/frontend/
  ingest, so the compose file the host applies always matches the app images it references.
- **Deliver.** `deploy.sh` resolves `basetool-config:<tag>` to a digest (best-effort — a
  missing artifact falls back to the legacy app-only path, never bricking the loop), folds
  that digest into the idempotence marker as a **fourth field**, and — only when it moved —
  extracts the bundle into a staging dir with `docker create` + `docker cp`, re-asserts it
  carries no secrets, snapshots the live config tree as a rollback anchor, and copies the
  allowlisted paths into `/var/iri/code` (compose file replaced atomically; the asset trees
  mirrored within their own subtree only). The health-gated `up -d --wait` then recreates the
  changed containers, and a failure restores **both** the previous digest pin and the previous
  config tree.
- **Carve-out.** A **postgres or Keycloak image** change is a stateful, choreographed upgrade
  (PGDATA major migration; Keycloak provider + keystore SAN dance). `deploy.sh` detects it by
  diffing those pins, refuses to auto-apply, alerts once, then skips quietly until a new
  promotion or an operator `--force` after the documented manual upgrade. redis and npm bumps
  auto-apply.

`deploy.sh` and the systemd units are deliberately **not** in the bundle, so a promoted bundle
can never overwrite the running deployer; they stay a manual bootstrap concern.

## Consequences

- An infra pin bump (e.g. redis) now reaches prod and recreates the container automatically,
  through the operator's existing promotion — no manual `cp` or `docker compose up -d`.
- All loop invariants hold and one is **strengthened**: the config the host runs is now
  content-addressed and signed (was an untracked manual copy), rollback now covers the compose
  file, and the manual `scp` — a real secret-leak path — disappears. The host needs **no new
  credential and no new tool** (`docker create`/`cp` only).
- App and config promote together (atomic, no skew). The cost: a config-only change still
  rides a release promotion rather than going out independently — acceptable, since promotion
  is the deliberate gate by design.
- New operational surface: the `basetool-config` package in GHCR, three new state paths under
  `/var/lib/iri`, and the stateful-infra carve-out the operator must understand
  (`docs/deployment.md`). The health gate for infra is shallow (`redis-cli ping` /
  `pg_isready` do not exercise the app workload), documented as a residual risk.

## Alternatives considered

- **Server pulls the compose file from git (a `config-stable` ref).** Equally closes both
  gaps, but needs a repo-scoped deploy key + SSH egress on the host (the repo is private) —
  a new long-lived credential and a new outbound trust seam — and a sparse-checkout mistake
  could delete the host-only `.env`/`realm-export.json` that live inside the worktree.
  Rejected: it weakens the "read-only GHCR token, nothing else" posture for no gain over the
  artifact approach.
- **Resolve infra image tags to digests inside `deploy.sh`.** Avoids delivering a compose
  file, but re-floats infra to whatever a tag resolves to at deploy time — inverting the
  deliberate "infra pinned by digest in git, reviewed via Dependabot PR" rule — and still
  cannot deliver any non-image compose change (env/command/volumes/new service). Rejected as
  an anti-pattern for this repo.
- **Status quo (manual copy).** Rejected: it is exactly the toil the owner asked to remove,
  and the manual `scp` is a standing secret-leak path.

