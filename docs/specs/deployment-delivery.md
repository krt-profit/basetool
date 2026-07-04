> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-07-02.
> **Owner area:** OPS · **Related ADRs:** [ADR-0049](../adr/0049-config-as-promotable-oci-artifact.md), [ADR-0055](../adr/0055-keycloak-spi-jar-as-promotable-oci-artifact.md)

# Deployment delivery & promotion

## Context & goal

How code and configuration reach the production host, and the safety invariants that
delivery must never violate. The operational runbook (the *how-to*) lives in
[`docs/deployment.md`](../deployment.md); this spec pins the *binding requirements* (the
*what-must-hold*) behind it so they are testable, referenceable, and cannot be silently
eroded. The implementation is `scripts/deploy.sh` driven by `scripts/iri-deploy.{service,timer}`,
the GitHub Actions workflows `release-images.yml` / `promote.yml`, the
`basetool-config` artifact built from `docker/config/Dockerfile`, and the
`basetool-keycloak-spi` provider-JAR artifact built from `docker/keycloak-spi/Dockerfile`.
See ADR-0049 / ADR-0055 for the decision records behind the artifact delivery.

These requirements are the first numbered `REQ-OPS-*` ids; the deployment area previously
existed only as a runbook.

## Requirements

### REQ-OPS-001 — Pull-only delivery

The production host **pulls**; nothing pushes to it. There is no inbound SSH, no webhook, and
no GitHub-issued credential capable of running commands on the box. The host holds only a
**read-only** GHCR pull token. A compromised Actions workflow or stolen `GITHUB_TOKEN` must
not be able to drive code execution on prod — at most it can read images already published.

**Acceptance**

- [ ] The host's only inbound deploy credential is a `Packages: Read` GHCR token; no SSH key,
  deploy key, or git credential is provisioned for the deploy path.
- [ ] `deploy.sh` performs only outbound registry operations (`docker login`, `imagetools
  inspect`, `pull`, `create`/`cp`); it opens no listening socket and accepts no inbound call.

**Enforced by:** `scripts/deploy.sh` · `scripts/iri-deploy.service` (sandbox) · **Runbook:** `docs/deployment.md` → *Why this design*

### REQ-OPS-002 — Deliberate promotion

Neither application images nor host configuration reach production on a `main` merge or a
release build. Production moves **only** when an operator runs `promote.yml`, which re-tags an
existing, already-validated digest to `:stable`. The app images, the `basetool-config` bundle
and the `basetool-keycloak-spi` provider-JAR bundle are promoted **in lock-step**, so the compose
file, the Keycloak provider JAR and the image versions the host applies always match each other.

**Acceptance**

- [ ] `release-images.yml` never writes the `:stable` tag for any artifact (backend/frontend/
  ingest/config/keycloak-spi).
- [ ] `promote.yml` is `workflow_dispatch`-only and promotes `backend`, `frontend`, `ingest`,
  `config` and `keycloak-spi` together (`fail-fast: true`).

**Enforced by:** `.github/workflows/release-images.yml` · `.github/workflows/promote.yml` · **Runbook:** `docs/deployment.md` → *Promoting to production*

### REQ-OPS-003 — Digest pin + health gate + auto-rollback

`deploy.sh` resolves `:stable` to concrete, immutable digests, applies them via a compose
override with `docker compose up -d --wait --wait-timeout`, and on a health-check failure
restores the previous state and exits non-zero. Rollback covers **both** the app-image digest
pin **and** the host config tree swapped in this deploy.

**Acceptance**

- [ ] A `:stable` tag flip in GHCR mid-deploy cannot partially apply: the deploy applies a
  single resolved digest set or none.
- [ ] When the new images fail to become healthy within `IRI_HEALTH_TIMEOUT`, the previous
  digest pin **and** the previous config tree are restored before the run exits non-zero.

**Enforced by:** `scripts/deploy.sh` (rollback block) · **Runbook:** `docs/deployment.md` → *What happens on the server*

### REQ-OPS-004 — Host configuration delivered as a promotable, digest-pinned artifact

The host configuration — `docker-compose.yml`, the NPM maintenance page
(`docker/maintenance/`) and the Keycloak login theme (`keycloak-theme/`) — is delivered to the
host as the signed `basetool-config` OCI artifact over the same pull-only, digest-pinned,
deliberately-promoted GHCR channel as the app images. The idempotence marker
(`last-deployed.digests`) **includes the config-bundle digest**, so a config-only change (e.g.
a bumped redis or npm image pin) is detected and applied — it is never skipped by the
app-image idempotence check. No manual `cp docker-compose.yml` or hand-run
`docker compose up -d` is required for an auto-appliable change.

**Acceptance**

- [ ] After a promotion whose only change is an infra pin bump, the next timer tick applies
  the new compose and recreates the affected container without operator file copying.
- [ ] `deploy.sh`'s idempotence marker carries five fields
  (`backend|frontend|ingest|config|keycloak-spi`); a changed config OR provider-JAR digest alone
  makes the marker differ and triggers an apply.
- [ ] A missing/unresolvable `basetool-config` artifact degrades to the legacy app-only deploy
  rather than failing the loop.

**Enforced by:** `scripts/deploy.sh` (config-delivery block, `EXPECTED_MARKER`) · `docker/config/Dockerfile` · `.github/workflows/release-images.yml` (`build-config`) · **Runbook:** `docs/deployment.md` → *Infra / host-config bumps*

### REQ-OPS-005 — No secrets in the delivered bundle

The `basetool-config` bundle is an explicit allowlist and must **never** carry host secrets —
`.env`, `keystore.p12` (or any `*.p12`/`*.jks`/`*.pem`/`*.key`), `realm-export.json`, or the
`keycloak/providers/` JARs. This is enforced at three layers: the Dockerfile's COPY allowlist,
`.dockerignore` barring secrets from the build context, a CI assertion that pulls the built
bundle and fails the release on any secret-shaped file, and a final re-assertion in
`deploy.sh` before the bundle is applied.

**Acceptance**

- [ ] `release-images.yml` fails if the built `basetool-config` bundle contains a
  secret-shaped file or is missing `docker-compose.yml`.
- [ ] `deploy.sh` aborts before applying if the staged bundle contains `.env`, a keystore, a
  `realm-export.json`, or a `keycloak/providers` directory.

**Enforced by:** `docker/config/Dockerfile` · `.dockerignore` · `.github/workflows/release-images.yml` (*Assert config bundle carries no host secrets*) · `scripts/deploy.sh` (`assert_no_secrets`)

### REQ-OPS-006 — Stateful-infra changes are operator-gated

A change to the **postgres** or **Keycloak** image pin is a stateful, choreographed upgrade
(PGDATA major migration; Keycloak provider + keystore-SAN dance) that a blind `up -d` would
break and the health gate would then roll back in a loop. `deploy.sh` must detect such a change
and refuse to auto-apply it, alert once, then skip subsequent ticks quietly until a new
promotion or an explicit operator `--force` after the documented manual upgrade. redis and npm
image bumps are auto-applied.

**Acceptance**

- [ ] A promotion whose compose changes a `postgres:` or `quay.io/keycloak/keycloak:` pin is
  not auto-applied; the run records the block and exits non-zero on first encounter, then
  skips quietly on repeat ticks.
- [ ] `deploy.sh --force` applies a previously-gated stateful-infra change.
- [ ] A redis or npm image bump (no postgres/Keycloak change) is auto-applied.

**Enforced by:** `scripts/deploy.sh` (`infra_image_pins`, carve-out block) · **Runbook:** `docs/deployment.md` → *Stateful-infra upgrades*

### REQ-OPS-007 — Keycloak provider JAR delivered as a separate promotable artifact

The Keycloak custom provider JAR (`keycloak-spi` — the Discord federation SPI plus the
first-login membership / account-existence gate) is delivered to the host **automatically**, over
the same pull-only, digest-pinned, deliberately-promoted GHCR channel as the app images and the
config bundle, as its **own** signed `basetool-keycloak-spi` OCI artifact (a `FROM scratch` image
carrying only `keycloak-spi.jar`). It is a **separate** artifact from `basetool-config` precisely
because REQ-OPS-005 bars `keycloak/providers/` JARs from the config bundle — that ban is on the
config bundle, not on automated provider delivery. The JAR is architecture-independent Java-21
bytecode (it must load under Keycloak's JDK), built once and cosign-signed like the app images.

When the promoted `keycloak-spi` digest moves, `deploy.sh` (after the app stack is healthy) stages
the JAR into `keycloak/providers/keycloak-spi.jar` and recreates **only** the keycloak container
(`up -d --no-deps --force-recreate keycloak`) so its `start` re-runs the provider build and loads
the new JAR — **health-gated**: on failure the previous JAR is restored, keycloak is brought back,
and the bad target backs off (the marker is not advanced). A provider-JAR-only change
**auto-applies**; a combined Keycloak-**image** + provider-JAR change stays operator-gated by the
postgres/Keycloak carve-out of REQ-OPS-006 (the image change blocks the tick until `--force`). A
missing/unresolvable `basetool-keycloak-spi` artifact degrades to no provider-JAR change for that
tick (the manual-staging fallback in the runbook still applies).

**Acceptance**

- [ ] `release-images.yml` builds, asserts (only the JAR, no secret-shaped file) and cosign-signs
  a `basetool-keycloak-spi` artifact; it never writes `:stable` (REQ-OPS-002).
- [ ] `promote.yml` promotes `keycloak-spi` in lock-step with the four other artifacts.
- [ ] `deploy.sh` resolves + cosign-trusts the `keycloak-spi:stable` digest, stages the JAR into
  `keycloak/providers/`, and recreates only keycloak (health-gated) when the digest changes.
- [ ] A provider-JAR-only promotion auto-applies; a combined Keycloak-image + JAR promotion is
  gated until `--force`.
- [ ] A failed keycloak recreate restores the previous JAR and records the failure for backoff.

**Enforced by:** `.github/workflows/release-images.yml` (`build-keycloak-spi`) · `.github/workflows/promote.yml` (matrix) · `docker/keycloak-spi/Dockerfile` · `scripts/deploy.sh` (`extract_keycloak_spi_jar`, the 5-field marker, the keycloak-recreate + JAR rollback) · **Runbook:** `docs/deployment.md` → *Keycloak custom providers* · **Decision:** ADR-0055

### REQ-OPS-013 — Idempotence fast-exit only over a verified running stack

(The ids REQ-OPS-008..012 are allocated to [`backup-recovery.md`](backup-recovery.md); this
requirement continues the series at the next free number.)

The idempotence marker (`last-deployed.digests`) records what the last **successful** deploy
applied — it says nothing about what is running *now*. A manual `docker compose up` without the
digest-pin overlay resolves `:stable` from the **local** image cache (which `deploy.sh` never
refreshes — it always pulls by digest) and can silently start an outdated build; a crash loop or
a half-down stack likewise leaves the marker untouched. `deploy.sh` must therefore take the
"no change" fast-exit **only after verifying the running stack against the target digest set**:
every app service (backend, frontend, ingest) has a container that is running, healthy (a
container without a healthcheck counts as healthy, mirroring `up --wait`), and created from an
image whose RepoDigest equals the target digest. On any divergence the run logs one
`drift: <service>: <reason>` line per finding and falls through to the normal apply path
(digest-pinned pull + `up -d --wait`), still honouring the bad-digest backoff so a
persistently-failing target does not flap every tick. `--check-only` reports the pending drift
re-apply without applying. Two deliberate exclusions keep the check free of false positives:
a container still inside its healthcheck **start period** (`running/starting`) counts as
converged for that tick (`up --wait` would merely wait on it, and a re-apply racing a slow
cold boot could record a false backoff failure for a good target — a genuinely broken container
surfaces as unhealthy/restarting on a later tick; a wrong image digest drifts regardless of the
start period), and one-off `docker compose run` containers are ignored (they are not part of
the deployed stack). Incident precedent: 2026-07-02, a pre-V199 backend started manually
off the stale local `:stable` tag against a V201-migrated database crash-looped while
`deploy.sh` reported "no change" and exited 0.

**Acceptance**

- [ ] A matching marker over a converged, healthy stack exits 0 without pulling or restarting
  anything ("no change", running stack verified).
- [ ] A matching marker over a container running a non-target image digest, an
  unhealthy/restarting container, or a missing container triggers a logged drift re-apply of
  the same digest set.
- [ ] A drift re-apply of a target inside the bad-digest backoff window is skipped like any
  other re-apply of that target; a failed drift re-apply records the failure for the backoff.
- [ ] `deploy.sh --check-only` over a drifted stack reports "would re-apply" and applies
  nothing.
- [ ] A container inside its healthcheck start period does not trigger a drift re-apply (but a
  non-target image digest does, even during the start period); a one-off `compose run`
  container never does.

**Enforced by:** `scripts/deploy.sh` (`running_stack_drift`, idempotence check) · `scripts/deploy.test.sh` (self-tests, run by `.github/workflows/deploy-script.yml`) · **Runbook:** `docs/deployment.md` → *What happens on the server*, *Restarting the stack manually*

### REQ-OPS-014 — Edge-proxy container runs with a hardened runtime baseline

The `npm` (nginx-proxy-manager) service is the most internet-exposed container on the host and
must run with the same runtime-hardening baseline the monitoring stack applies:
`security_opt: no-new-privileges`, `cap_drop: [ALL]` with an explicit, minimal `cap_add`
list, and a `pids` limit. The capability add-back set is not documented by upstream (the image
boots as root under s6-overlay), so it is defined empirically and **must be re-verified on every
image bump** before the bump is promoted: a clean boot (including the s6 prepare `sed` pass over
`/data/nginx`), a passing `/usr/bin/check-health`, a working `nginx -s reload`, and a clean
container restart. A read-only root filesystem is explicitly **out** of this baseline — the s6
prepare step writes across the filesystem and the container refuses to boot with EROFS (the same
constraint that keeps the two custom `.conf` bind mounts writable).

**Acceptance**

- [ ] The `npm` service in `docker-compose.yml` sets `no-new-privileges`, `cap_drop: [ALL]` plus
  a commented `cap_add` allow-list, and a `pids` limit.
- [ ] An `npm` image bump is only promoted after the capability set has been re-verified against
  the new image (boot + check-health + reload + restart).

**Enforced by:** `docker-compose.yml` (`npm` service) · verification recipe in the service comment

## Out of scope

- The deploy script (`deploy.sh`) and the systemd units themselves are **not** delivered via
  the bundle (self-update hazard) — they remain a manual bootstrap step. Bootstrap, token
  rotation, and the maintenance-page mechanics live in `docs/deployment.md`.
- Application-level configuration delivered as environment variables in `.env` is host-only and
  out of scope here (it is never bundled). The list of env keys lives in `README.md`.

## Open questions

- Deepening the infra health gate beyond `redis-cli ping` / `pg_isready` (which do not
  exercise the app workload) — e.g. recreating dependents on a config-digest change so their
  healthchecks gate the rollback. Promote to an ADR if pursued.

