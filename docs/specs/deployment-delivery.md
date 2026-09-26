> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-09-23.
> **Owner area:** OPS · **Related ADRs:** [ADR-0049](../adr/0049-config-as-promotable-oci-artifact.md), [ADR-0055](../adr/0055-keycloak-spi-jar-as-promotable-oci-artifact.md), [ADR-0213](../adr/0213-a-release-that-moves-the-provider-jar-costs-one-outage.md), [ADR-0075](../adr/0075-host-side-cosign-signature-verification.md), [ADR-0079](../adr/0079-redis-session-store-aof-and-maxmemory-noeviction.md), [ADR-0083](../adr/0083-deploy-bot-health-drift-targeted-restart.md), [ADR-0145](../adr/0145-build-provenance-anchored-outside-the-registry.md), [ADR-0163](../adr/0163-the-container-runtime-becomes-rootless-podman-on-debian-13.md), [ADR-0169](../adr/0169-the-e2e-concurrency-group-is-keyed-on-the-gates-own-verdict.md), [ADR-0187](../adr/0187-the-edge-learns-the-client-address-from-a-proxy-protocol-front-end.md), [ADR-0188](../adr/0188-the-host-bootstrap-is-an-ansible-role.md), [ADR-0189](../adr/0189-stateful-containers-run-as-their-own-uid.md), [ADR-0190](../adr/0190-every-container-but-keycloak-runs-read-only.md), [ADR-0196](../adr/0196-a-rootless-host-aliases-its-own-public-names-to-the-container-gateway.md)

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

**Runtime.** Since 2026-09-22 production runs **rootless Podman under Quadlet** on Rocky Linux 10
(ADR-0163): every container is a systemd user unit of the service account, generated from the
compose files by `scripts/generate-quadlet.py` into `quadlet/` and delivered inside the config
bundle. `deploy.sh` drives the runtime through `scripts/lib/container-runtime.sh`, which since
2026-09-22 speaks rootless Podman only (OPS-SIMP-01, ADR-0194 amended); the local and test stacks run
the compose files directly and never go through it. Where a requirement below still names a Compose
mechanism (`up -d --wait`, a compose override, `docker compose run`), that is history from the Docker
host; the Quadlet realisation is stated beside it. The host
itself is provisioned by the Ansible role in `ansible/` (ADR-0188), which is not a delivery path.

These requirements are the first numbered `REQ-OPS-*` ids; the deployment area previously
existed only as a runbook.

## Requirements

### REQ-OPS-001 — Pull-only delivery

The production host **pulls**; nothing pushes to it **for delivery**. There is no inbound SSH
*for the deploy*, no webhook, and no GitHub-issued credential capable of running commands on the
box. The host holds only a **read-only** GHCR pull token. A compromised Actions workflow or stolen
`GITHUB_TOKEN` must not be able to drive code execution on prod — at most it can read images
already published.

> [!note] Corrected 2026-09-16 — this paragraph overstated its own scope
> It read *"There is no inbound SSH"*, flatly, while the acceptance criteria below have always
> scoped it to the **deploy path**. The operator's administrative SSH exists, is the host's sole
> administrative entrance and the only route to the two loopback-bound admin interfaces, and it
> **stays** — confirmed by @greluc on 2026-09-16. This requirement governs the delivery mechanism,
> not human access. A requirement whose prose is false teaches its readers not to trust the ones
> that are true, which is why this is a correction rather than a clarification.

**Acceptance**

- [ ] The host's only inbound deploy credential is a `Packages: Read` GHCR token; no SSH key,
  deploy key, or git credential is provisioned for the deploy path.
- [ ] `deploy.sh` performs only outbound registry operations (`login`; tag resolution with
  `skopeo inspect` under Podman or `buildx imagetools inspect` under Docker; `pull`;
  `create`/`cp` to extract the bundles; `cosign verify`); it opens no listening socket and accepts
  no inbound call.
- [ ] The Ansible role that provisions the host ships no image, unit file or configuration bundle
  and is never scheduled on the host (ADR-0188); the scripts and units it installs carry no image
  digest or application version.

**Enforced by:** `scripts/deploy.sh` · `scripts/iri-deploy.service` (sandbox) · **Runbook:** `docs/deployment.md` → *Why this design*

### REQ-OPS-002 — Deliberate promotion

Neither application images nor host configuration reach production on a `main` merge or a
release build. Production moves **only** when an operator runs `promote.yml`, which re-tags an
existing, already-validated digest to `:stable`. The app images, the `basetool-config` bundle
and the `basetool-keycloak-spi` provider-JAR bundle are promoted **in lock-step**, so the compose
file, the Keycloak provider JAR and the image versions the host applies always match each other.

A promotion passes three gates before it flips `:stable`. Gate (1) is the **vulnerability** gate —
Trivy scans the three app images at the digest the requested tag resolves to, on both architectures,
and a fixed HIGH/CRITICAL finding fails the run before a reviewer is ever asked
([REQ-OPS-024](#req-ops-024--a-promotion-is-gated-on-the-promoted-digests-vulnerability-scan)).
Gate (2) is the **human-approval** gate — a dedicated
`approve` job is bound to the `production` GitHub Environment, so a repo-configured required reviewer
must approve the run before the promote matrix starts (workflow_dispatch alone lets anyone with
Actions-write reach prod). The gate lives on its **own single job**, not on the promote matrix, so the
run mints exactly one GitHub deployment record — a matrix-bound environment minted one deployment per
leg, four of which `auto_inactive` then flipped to `inactive`, leaving the repo home page showing a
misleading permanent "Inactive" badge for `production`. Gate (3) is the
**signature** gate — cosign-verify the source digest against the release-images identity, so only an
image built and signed by our own release pipeline can be promoted. `release-images.yml` also scans
every build with Trivy and uploads SARIF to the Security tab, but *that* scan stays advisory — the
same finding is advisory at build time and blocking at promotion, and REQ-OPS-024 says why.

The two gates answer different threats and neither stands in for the other: **the approval gate
guards against mistakes** — the wrong version, the wrong moment, a promotion nobody meant — and
**the signature gate guards against tampering** — a digest our own release pipeline did not produce.
A reviewer cannot see from a tag whether its digest was forged; a signature cannot tell whether
promoting it now is wise.

`promote.yml` runs **from `main` only**. `workflow_dispatch` executes the workflow file of the ref it
is dispatched on, so a branch's copy could drop a gate; the `production` environment accepts
deployments from `main` only (a repository setting since 2026-09-22), and the workflow's first job
also fails on any other ref, so the refusal is stated before six scan runners are spent. Every job
declares its own permissions; only `promote` and `sync-testing` can write to the registry, and no
job holds `id-token: write` — a keyless `cosign verify` needs no OIDC token (audit item CI-SEC-04).

**Acceptance**

- [ ] `release-images.yml` never writes the `:stable` tag for any artifact (backend/frontend/
  ingest/config/keycloak-spi).
- [ ] `promote.yml` is `workflow_dispatch`-only and promotes `backend`, `frontend`, `ingest`,
  `config` and `keycloak-spi` together (`fail-fast: true`).
- [ ] A dedicated `approve` job (not the promote matrix) declares `environment: production`, so a
  required reviewer configured on that environment gates the run before any `:stable` flip, and the
  run creates exactly one deployment record (no phantom "Inactive" badge on the repo home page).
- [ ] The `promote` job cosign-verifies the resolved digest against the release-images identity
  before re-tagging; an image not signed by our own release pipeline cannot be promoted.
- [ ] A `scan` job runs ahead of `approve` and fails the run on a fixed HIGH/CRITICAL finding in any
  of the three app images, on either architecture, unless `allow_vulnerable` was set (REQ-OPS-024).
- [ ] A run dispatched from any ref other than `refs/heads/main` fails in `validate-inputs`, before
  any scan, approval or re-tag.
- [ ] No job in `promote.yml` or `promote-testing.yml` holds `id-token: write`; permissions are
  declared per job, and only the re-tagging jobs hold `packages: write`.

**Enforced by:** `.github/workflows/release-images.yml` · `.github/workflows/promote.yml` (`environment`, cosign gate) · **Runbook:** `docs/deployment.md` → *Promoting to production*

> Non-production environments are fed by their own channel and their own tag — see
> [REQ-OPS-022](#req-ops-022--non-production-environments-are-fed-by-their-own-promotion-channel).
> `:stable` remains the only tag production reads.

### REQ-OPS-003 — Digest pin + health gate + auto-rollback

`deploy.sh` resolves `:stable` to concrete, immutable digests, pins them, applies them, waits for
health, and on a health-check failure restores the previous state and exits non-zero. Rollback
covers **both** the app-image digest pin **and** the host config tree swapped in this deploy.

The pin has a record and, under Quadlet, a binding. The record is
`/var/lib/iri/current-digest-pin.yml` (its predecessor is the rollback anchor); Compose reads it
directly as an override (`up -d --wait --wait-timeout`). Quadlet reads unit files, so the binding
is one drop-in per app service, `<svc>.container.d/10-digest-pin.conf`, whose `Image=` replaces
the unit's tag; the apply is one `systemctl --user stop` naming every service whose pin or unit
changed this run (keycloak too when the provider JAR moved, REQ-OPS-007), which takes what requires
them down with them, followed by a `start` of every stack unit in dependency order — so each affected
unit stops once and starts once. It was a per-service `restart` until 2026-09-25: a restart travels
along `Requires=`, and the restarts that followed ran the dependents again (ADR-0213). The wait is structural — `Notify=healthy` makes the unit
`Type=notify`, bounded by its generated `TimeoutStartSec=` (so `IRI_HEALTH_TIMEOUT` governs the
Compose path only). A rollback that restored the record without re-materialising the drop-ins would
roll forward into the failed release, so both move together, together with the previous unit files.

**A failure before the health gate is a deploy failure too, and is recorded as one.** Between the
signature verification and the health gate `deploy.sh` extracts the config bundle, mirrors it onto
the host, renders `env.d`, installs the units, writes the pin and pulls the images. A failure
anywhere in that window — a `fail`, a mirror's rsync, an errexit, a pull — must leave the same
evidence as a failed health gate: a `FATAL` line naming the step and the exit code, the previous
config tree, units and pin restored if the run had changed them, a bad-digest backoff record for the
target, and `basetool_deploy_last_failure_timestamp` (`DeployFailed`). An EXIT trap armed for exactly
that window (`on_pre_gate_exit`) is what guarantees it, because errexit ends the process without
passing through any code that could record it. Where the cause can be seen beforehand it is refused
**before anything changes**: every directory in a subtree the apply mirrors must be owned and
writable by the deploy account, and the compose directory, the unit directory and `env.d` writable
(`assert_config_tree_writable`). The pin is written only after the config delivery, so a release
the stateful-infra gate holds back (REQ-OPS-006) leaves no pin behind. A restore that fails itself
is reported as an inconsistent tree, and `config-apply.incomplete` stops the next tick from
snapshotting that tree over `config-previous/`.

**The rollback anchors move only with the target.** `previous-digest-pin.yml`, `config-previous/`
(the previous units with it) and `keycloak-spi-previous.jar` name the release *before* the deployed
one, and nothing else on the host remembers it. Only a run whose target differs from the last
deployed one rotates them. A drift re-apply of the deployed release (REQ-OPS-013) rewrites the pin,
and on a host whose unit files are gone the config too, but it saves no pin, takes no snapshot and
writes no `config-apply.incomplete`. A re-apply that fails — at its health gate or before it — rolls
nothing back, because its target *is* the deployed release: it leaves the release and the anchors
where they are, records the failure in its **own** backoff record, `reapply-failed.digests` (keyed to
the deployed target), and stamps `basetool_deploy_last_health_restart_failed_timestamp`
(`DeployHealthRestartFailing`, "the running release could not be restored") rather than a deploy
outcome. A re-apply that succeeds is not a deploy outcome either: it stamps the stack-health
heartbeat, not `basetool_deploy_last_success_timestamp`, which would clear a `DeployRolledBack` or
`DeployFailed` raised by a different release that has still not shipped. `DeployRolledBack`
therefore means a rollback to a different release, always.

**An operator re-applies the deployed release with `deploy.sh --reapply`** (since 2026-09-26). The
target is read from `last-deployed.digests` — never resolved from a tag, which may already name a
newer release — and the run is a re-apply in every respect above: it re-delivers the release's config
bundle and units, rewrites its pin, re-stages its provider JAR and swaps it in only when the live one
differs (a byte-identical JAR restarts nothing), passes the health gate, rotates no anchor and rolls
nothing back. It is refused, before any registry call and without a metric or a failure record, on a
host with no deployed release (no marker), together with `--tag`, and when the pin record names an
app digest the marker does not. It bypasses the re-apply backoff — the backoff throttles the timer,
and an operator who asks for a re-apply now has chosen to spend the restart window now — but leaves
the bad-digest backoff (`failed.digests`) and the stateful-infra marker alone, because those may
belong to the newer target the tag names; clearing them would let the next tick retry a release that
just rolled back. **Deleting `last-deployed.digests`** is not a re-apply: the run then cannot tell
that its target is the deployed release, takes the release path, and rotates all three anchors onto
the release that is already deployed.

**Backoff durations.** A failed **release** (a new target) is retried after `IRI_BACKOFF_BASE`
(600 s), doubling per consecutive failure, capped at `IRI_BACKOFF_MAX` (6 h), from
`failed.digests`. A failed **re-apply** of the deployed release — drift or `--reapply` — uses the
self-heal's durations, `IRI_HEALTH_RESTART_BASE` (300 s) doubling to `IRI_HEALTH_RESTART_MAX` (1 h),
from `reapply-failed.digests` (the owner's decision of 2026-09-26): it restores the release
production is on, like the heal, and a missing container should not stay missing for six hours. The
record is kept apart from the heal's `health-restart.digests`, so a failed heal of one service never
holds back the re-apply of another service's missing container. `--force` bypasses either backoff.

> [!bug] Added 2026-09-25 — a re-apply rotated the anchors until then
> The pin save ran on every apply, so a drift re-apply ("drift: frontend: no container") copied the
> deployed pin over `previous-digest-pin.yml`, and a host with no unit files snapshotted the deployed
> tree over `config-previous/`. If that re-apply then failed its gate, the „rollback" restored the
> release it was already on, fired `DeployRolledBack` for a release that had shipped, and the real
> previous release was gone as an anchor. Replayed by `scripts/deploy.test.sh`
> (`scenario_reapply_that_fails_keeps_the_anchors_and_rolls_nothing_back` and the three after it).

> [!bug] Added 2026-09-25 — this path was silent until then
> v1.11.0 met a root-owned `/var/iri/code/docker/acme`. Every tick from 12:25 to 12:35 mirrored three
> subtrees, died on rsync exit 23 inside `mirror_dir`, and ended under `set -e` with no FATAL line, no
> backoff record, no restore and no metric; `DeployFailed` could not fire and production stayed on
> the old release for fifteen minutes. The second tick also snapshotted the half-mirrored tree as
> `config-previous/` and copied the first tick's new pin over the rollback anchor. This paragraph and
> the last three acceptance criteria were written for that incident; `scripts/deploy.test.sh` replays
> it (`scenario_config_mirror_failure_is_recorded_and_undone` and the four scenarios after it).

**Acceptance**

- [ ] A `:stable` tag flip in GHCR mid-deploy cannot partially apply: the deploy applies a
  single resolved digest set or none.
- [ ] When the new images fail to become healthy (within `IRI_HEALTH_TIMEOUT` under Compose, the
  unit's `TimeoutStartSec=` under Quadlet), the previous digest pin — record **and**, under
  Quadlet, the drop-ins — **and** the previous config tree and unit files are restored before the
  run exits non-zero.
- [ ] A service started by hand (`systemctl --user start`) runs the pinned digest, because the pin
  is part of its unit.
- [ ] The container readiness probe cannot hang past the container healthcheck timeout: every
  readiness-group health indicator is bounded so `/actuator/health/readiness` returns *within* the
  probe window (frontend: the reactive Redis `PING` is capped by `spring.data.redis.timeout` = 2 s,
  well below the 5 s HEALTHCHECK timeout — ADR-0114). A slow/stalled dependency therefore yields a
  fast, truthful `DOWN`, so the health gate and the deploy `--wait` see a real, timely signal
  instead of a probe that never completed.
- [ ] A failure between the signature verification and the health gate writes a `FATAL` line that
  names the step and the exit code, a bad-digest backoff record for the target, and
  `basetool_deploy_last_failure_timestamp`; the next tick inside the backoff window skips.
- [ ] Such a failure after part of the config tree was mirrored restores `config-previous/` and the
  previous units, and rolls back the pin if it had been written; a failed restore is reported and
  leaves `config-apply.incomplete`, and a tick that finds it does not re-snapshot `config-previous/`.
- [ ] A directory the apply would mirror into that the deploy account does not own or cannot write
  is refused before the snapshot and before any mirror, with a line naming the path; a release held
  back by the stateful-infra gate writes no digest pin.
- [ ] A drift re-apply of the deployed release — successful or not, with or without a config
  re-delivery — leaves `previous-digest-pin.yml`, `config-previous/` and
  `keycloak-spi-previous.jar` naming the release before it, and writes no `config-apply.incomplete`.
- [ ] A failed drift re-apply restores nothing, writes neither `basetool_deploy_last_rollback_timestamp`
  nor `basetool_deploy_last_failure_timestamp`, records the deployed target in
  `reapply-failed.digests` and stamps `basetool_deploy_last_health_restart_failed_timestamp`. A later
  release rotates the anchor to the deployed release, and its rollback lands there.
- [ ] A failed re-apply is backed off 300 s, doubling, capped at 1 h (`IRI_HEALTH_RESTART_*`); a
  failed release 600 s, doubling, capped at 6 h (`IRI_BACKOFF_*`). Neither writes the other's record.
- [ ] `deploy.sh --reapply` re-applies the release `last-deployed.digests` names, whatever the tag
  names, and resolves no tag; it rotates no anchor, restarts keycloak only for a live provider JAR
  that differs from the deployed one, stamps no `basetool_deploy_last_success_timestamp`, and leaves
  `failed.digests` of another target as it was. When it fails it behaves like a failed drift
  re-apply.
- [ ] `deploy.sh --reapply` is refused — exit 1, no registry call, no metric, no failure record — on a
  host with no `last-deployed.digests`, together with `--tag`, and when `current-digest-pin.yml`
  names an app digest the marker does not.
- [ ] Deleting `last-deployed.digests` still makes the next run a release that rotates all three
  anchors onto the deployed release (documented, not a way to re-apply).

**Enforced by:** `scripts/deploy.sh` (rollback block, `on_pre_gate_exit`, `REAPPLY` / `record_reapply_failure`, `--reapply`, `backoff_seconds`, `assert_config_tree_writable`,
`restore_previous_config_tree`) · `scripts/lib/container-runtime.sh`
(`rt_pin_apply`, `rt_pin_rollback`, `rt_apply_stack`) · `frontend/src/main/resources/application.yml`
(`spring.data.redis.timeout` / `connect-timeout`, ADR-0114) · `scripts/deploy.test.sh` · **Runbook:** `docs/deployment.md` → *What happens on the host*, *Troubleshooting*

### REQ-OPS-004 — Host configuration delivered as a promotable, digest-pinned artifact

The host configuration — both compose files, the maintenance page (`docker/maintenance/`), the edge
configuration (`docker/edge/`), the ACME publishing loop (`docker/acme/`), the Keycloak login theme
(`keycloak-theme/`), the monitoring configuration (`monitoring/`) and the **Quadlet units with their
environment templates** (`quadlet/`) — is delivered to the host as the signed `basetool-config` OCI
artifact over the same pull-only, digest-pinned, deliberately-promoted GHCR channel as the app
images. The idempotence marker (`last-deployed.digests`) **includes the config-bundle digest**, so
a config-only change (e.g. a bumped redis image pin) is detected and applied — it is never skipped
by the app-image idempotence check. No manual file copy and no hand-run apply is required for an
auto-appliable change. `deploy.sh`'s mirror list is explicit, so a directory added to the bundle's
`COPY` allowlist must be added to `apply_config_tree` in the same change or it stops in the staging
area (the 2026-09-12 edge and 2026-09-16 acme gaps).

**Under Quadlet the units are part of the delivered definition.** The compose files stay the source:
`scripts/generate-quadlet.py` generates `quadlet/systemd/` and `quadlet/env.d/*.env.tmpl` from them,
both are committed, and `repo-lint.yml` (`quadlet-drift`) fails a change whose units no longer match
the compose files. A Dependabot compose bump regenerates them on its own branch (REQ-OPS-035). On a config change `deploy.sh` renders one environment file per service from the
host's `.env` (`scripts/render-env-d.py`, which refuses to write a half-rendered set), installs every
unit the bundle names into the service user's delivery directory
(`/etc/containers/systemd/users/<uid>/`), **stops** a unit the bundle no longer names before removing
its file, and never touches the `.container.d/` drop-ins beside them. A host with no units at all is
treated as a config change, so the first deploy on a freshly provisioned host installs them.

**A network change is installed, not applied.** The networks are `.network` units, and Quadlet creates
one with `podman network create --ignore`, so a changed unit leaves the existing network as it was.
Recreating one means stopping its members — a brief full-stack outage — so it is an **operator
maintenance** step with its exact commands in the runbook, never an automatic tick. (Under Compose,
`deploy.sh` detected a changed `networks:` block and ran a gated clean-slate down+up for it — the
2026-07 name-resolution incident, #974. That machinery was removed with the Docker runtime on
2026-09-22, OPS-SIMP-01.)

Pinning guarantees that an *existing* gateway keeps its address; it does not guarantee that the
tunnel still arrives from one of the gateways the allow-list names. Adding or removing a bridge
changes **which** leg the published ports DNAT over, and that is a `networks:` change like any other.
`net-edge-ingress` did exactly this on 2026-09-12: every gateway in the allow-list kept its address
and the Keycloak admin console still went dark, because the tunnel began arriving on the new bridge
instead. **A `networks:` change that touches the edge's own attachments MUST be checked against the
`/auth/admin` allow-list in `docker/edge/conf.d/10-frontend.conf.template` in the same change** —
and, under Quadlet, against the edge's pinned addresses, which `EDGE_TRUSTED_PROXY` and the role's
`basetool_host_edge_trusted_proxies` must name one for one (the generator refuses a mismatch).
Behind the PROXY-protocol front end the tunnel arrives as the host loopback, which
`render-and-run.sh` adds to that allow-list only in that mode (ADR-0187).

**Acceptance**

- [ ] After a promotion whose only change is an infra pin bump, the next timer tick applies
  the new compose and recreates the affected container without operator file copying.
- [ ] `deploy.sh`'s idempotence marker carries five fields
  (`backend|frontend|ingest|config|keycloak-spi`); a changed config OR provider-JAR digest alone
  makes the marker differ and triggers an apply.
- [ ] A missing/unresolvable `basetool-config` artifact degrades to the legacy app-only deploy
  rather than failing the loop.
- [ ] Under Compose, a promotion that changes the compose `networks:` block is applied via a clean
  down+up (not an in-place `up`) on both apply and rollback, so name resolution is not stranded; a
  `networks:`-unchanged config bump keeps the in-place `up`.
- [ ] Under Quadlet, a config change renders `env.d/` before any unit reloads, installs changed
  units, recreates (stop, then start) exactly the application services whose unit or pin changed
  and what requires them, each once, stops a retired unit
  before removing it, and leaves every `.container.d/` drop-in in place. A changed monitoring or
  `acme` unit is restarted too (since 2026-09-22 — see REQ-OPS-013).
- [ ] `generate-quadlet.py --check` fails CI when `quadlet/` no longer matches the compose files.

**Enforced by:** `scripts/deploy.sh` (config-delivery block, `EXPECTED_MARKER`, `apply_config_tree`,
`install_quadlet_units`) · `scripts/render-env-d.py` ·
`scripts/generate-quadlet.py` (`repo-lint.yml` → `quadlet-drift`) · `docker/config/Dockerfile` ·
`.github/workflows/release-images.yml` (`build-config`) · **Runbook:** `docs/deployment.md` →
*Configuration changes that are not app releases*

### REQ-OPS-005 — No secrets in the delivered bundle

The `basetool-config` bundle is an explicit allowlist and must **never** carry host secrets —
`.env`, `keystore.p12` (or any `*.p12`/`*.jks`/`*.pem`/`*.key`), `realm-export.json`, or the
`keycloak/providers/` JARs. This is enforced at three layers: the Dockerfile's COPY allowlist,
`.dockerignore` barring secrets from the build context, a CI assertion that pulls the built
bundle and fails the release on any secret-shaped file, and a final re-assertion in
`deploy.sh` before the bundle is applied.

**Acceptance**

- [ ] `release-images.yml` fails if the built `basetool-config` bundle contains a
  secret-shaped file or is missing a required payload: both compose files, `docker/maintenance`,
  `keycloak-theme`, `monitoring`, `quadlet/env.d`, and `quadlet/systemd` holding at least one
  `.container` unit.
- [ ] The `env.d` templates carry `${VAR}` references only; every value is rendered on the host
  from its own `.env` and never enters the bundle.
- [ ] `deploy.sh` aborts before applying if the staged bundle contains `.env`, a keystore, a
  `realm-export.json`, or a `keycloak/providers` directory.

**Enforced by:** `docker/config/Dockerfile` · `.dockerignore` · `.github/workflows/release-images.yml` (*Assert config bundle carries no host secrets*) · `scripts/deploy.sh` (`assert_no_secrets`)

### REQ-OPS-006 — Stateful-infra changes are operator-gated

A change to the **postgres** or **Keycloak** image **tag** is a stateful, choreographed upgrade
(PGDATA major migration; Keycloak provider + keystore-SAN dance) that a blind recreate would
break and the health gate would then roll back in a loop. `deploy.sh` must detect such a change
and refuse to auto-apply it, alert once, then skip subsequent ticks quietly until a new
promotion or an explicit operator `--force` after the documented manual upgrade. Every other
image bump (redis, the edge, acme, the monitoring images) is auto-applied. The tags are read from
both definitions the bundle can carry — the compose `image:` lines and the Quadlet units' `Image=`
lines — so a Quadlet host compares like with like (reading only the compose file made the gate
refuse every Quadlet tick on the testing host, 2026-09-18), and a host that has never received a
definition is not treated as an upgrade.

**The gate is on the tag, not on the whole pin.** A *same-tag digest refresh* — a rebuilt base
image, which is normally a security fix — **must** auto-apply. Gating it inverts the purpose of
the gate: it leaves the stack on the vulnerable build precisely when it should move fastest.

> [!warning] Corrected 2026-09-17
> This requirement said "image **pin**" and its first acceptance criterion said any `postgres:` or
> `quay.io/keycloak/keycloak:` pin change is not auto-applied. That has been untrue since
> 2026-09-12, when `031e75fed` narrowed the comparison to the tag after the full-reference
> comparison froze the testing host for seven days (1901 skipped ticks, a compose file from
> 2026-08-21) over a Keycloak rebuild. The code and `scenario_infra_digest_refresh_is_not_gated`
> were right and this spec was stale; the wording is corrected here, the behaviour is unchanged.

**Acceptance**

- [ ] A promotion whose compose changes a `postgres:` or `quay.io/keycloak/keycloak:` **tag** is
  not auto-applied; the run records the block and exits non-zero on first encounter, then
  skips quietly on repeat ticks.
- [ ] A promotion that changes only the **digest** behind an unchanged `postgres:` or
  `quay.io/keycloak/keycloak:` tag **is** auto-applied, with no `CARVE-OUT` line.
- [ ] `deploy.sh --force` applies a previously-gated stateful-infra change.
- [ ] Any other image bump (no postgres/Keycloak tag change) is auto-applied.
- [ ] The first config bundle on a host with no previous definition is applied without a
  `CARVE-OUT`.

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

**A release costs one restart window, the provider JAR included** ([ADR-0213](../adr/0213-a-release-that-moves-the-provider-jar-costs-one-outage.md),
the owner's decision of 2026-09-25). When the promoted `keycloak-spi` digest moves, `deploy.sh`:

1. **extracts** the JAR into the state directory (`keycloak-spi-stage.jar`) before anything on the
   host changes — a failure there is a pre-gate failure under REQ-OPS-003: a `FATAL … step 'extract
   the keycloak-spi provider JAR'` line, the backoff record and `DeployFailed`, with nothing to undo;
2. after the config delivery, the pin and the pull, as the **last step before the apply**, snapshots
   the live `keycloak/providers/keycloak-spi.jar` (`keycloak-spi-previous.jar`), installs the new one
   and marks keycloak as re-defined;
3. applies the release **once**: one `systemctl --user stop` takes down every re-defined unit —
   keycloak, the re-pinned app services, the units the config replaced — and, through `Requires=`,
   what requires them; then every stack unit is **started** in dependency order and waited for
   (`Notify=healthy`). Keycloak's start re-runs the provider build on the new JAR before backend
   starts. **One health gate** covers the app images, the units and the JAR, and the run records
   success only when every stack unit is healthy.

If the gate fails, **everything the release changed is rolled back together** — the digest pin
(record and drop-ins), the config tree and units, and the previous JAR — and applied the same way; the
run is recorded as rolled back (`DeployRolledBack`) with the bad-digest backoff, and the marker is not
advanced. New app images never run on the old JAR, nor old images on the new one. The log names what
the release changed (`release parts: app images […] · unit definitions […] · config bundle: … ·
provider JAR: …`) and, on a failed gate, the units that did not come up in start order; when keycloak
is the first of them and the JAR is the only change to keycloak, it says the JAR is the likely cause
(the cause, when the JAR is the release's only change). Exact blame between a JAR and an app image is
not possible in one gate, and the log says so rather than guessing. **Expected outage:** one keycloak
start, then one backend start, then the slower of frontend and ingest — about two minutes on
production — for the whole release, JAR or not. A provider-JAR-only change **auto-applies** and is
exactly this apply with only keycloak re-defined; a combined Keycloak-**image** + provider-JAR change
stays operator-gated by the postgres/Keycloak carve-out of REQ-OPS-006 (the image change blocks the
tick until `--force`). A missing/unresolvable `basetool-keycloak-spi` artifact degrades to no
provider-JAR change for that tick (the manual-staging fallback in the runbook still applies).

*(Corrected 2026-09-25, third time — a changed requirement, not a wording fix: until ADR-0213 the
JAR was staged only **after** the app apply had passed its gate, and a failed JAR was reverted alone
while the app release stayed. That cost a second full-app outage for every JAR-moving release (v1.12.0:
about two of the ~five minutes of maintenance page), and the app apply itself restarted re-defined
units one by one, so a `restart` of backend followed by `restart`s of ingest and frontend restarted
those two twice. A failed JAR extraction after the gate recorded no failure. All three are gone; the
two corrections below describe the step as it was.)*

*(Corrected 2026-09-25: "recreates **only** the keycloak container" holds under Compose's
`--no-deps`, not under Quadlet. `backend` `Requires=` keycloak, and `frontend` and `ingest` require
backend, so `systemctl --user restart keycloak.service` restarts all three with it — measured on
production 2026-09-25 for a manual restart of the same unit: about two minutes of maintenance page.
A provider-JAR delivery is therefore a short full-app restart, not a Keycloak-only one:
`rt_recreate` leaves keycloak's *dependencies* alone, but systemd restarts its *dependents*.)*

*(Corrected 2026-09-25, second time: the gate was keycloak's alone. `systemctl restart
keycloak.service` returns once keycloak is healthy, while the restarts of backend, frontend and
ingest are still running — frontend and ingest stopped, with no container, until backend is up. On
production that day the v1.12.0 deploy logged "deploy successful" at 17:43:17 in that window, and the
next tick's drift check found frontend and ingest with no container and re-applied. Since then the
step waits for the whole stack and records success only after it; see the paragraph above.)*

**Acceptance**

- [ ] `release-images.yml` builds, asserts (only the JAR, no secret-shaped file) and cosign-signs
  a `basetool-keycloak-spi` artifact; it never writes `:stable` (REQ-OPS-002). The JAR is compiled
  in its own job (`keycloak-spi-jar`, `contents: read`, no Gradle cache) and handed over as a
  run-scoped artifact; the job that pushes and signs (`build-keycloak-spi`) runs no Gradle.
- [ ] `promote.yml` promotes `keycloak-spi` in lock-step with the four other artifacts.
- [ ] `deploy.sh` resolves + cosign-trusts the `keycloak-spi:stable` digest and, when it changes,
  swaps the JAR into `keycloak/providers/` before the release apply, so keycloak starts on the new
  JAR **inside** the release's single restart window and the single health gate covers it.
- [ ] A release that moves the JAR and the app images starts keycloak, backend, ingest and frontend
  **once each**; no stack unit is `restart`ed, and the re-defined units go down in one `stop`.
- [ ] A release that re-pins app services without moving the JAR does not stop or restart keycloak.
- [ ] A provider-JAR-only promotion auto-applies as that same apply (one window); a combined
  Keycloak-image + JAR promotion is gated until `--force`.
- [ ] A failed health gate restores the previous digest pin, config tree, units **and** JAR together,
  applies them once, records the rollback for backoff and never reports success; the log names what
  the release changed and what did not come up.
- [ ] A JAR that cannot be extracted is a recorded pre-gate failure (`FATAL`, backoff,
  `DeployFailed`) and changes nothing on the host.

**Enforced by:** `.github/workflows/release-images.yml` (`keycloak-spi-jar`, `build-keycloak-spi`) · `.github/workflows/promote.yml` (matrix) · `docker/keycloak-spi/Dockerfile` · `scripts/deploy.sh` (`stage_keycloak_spi_jar`, `swap_in_keycloak_spi_jar`, `restore_previous_keycloak_spi_jar`, `explain_gate_failure`, the 5-field marker, the rollback block) · `scripts/lib/container-runtime.sh` (`rt_apply_stack`, `rt_await_stack`) · `scripts/deploy.test.sh` (`scenario_spi_*`, `scenario_apps_only_restart_each_unit_once`) · `scripts/container-runtime.test.sh` · **Runbook:** `docs/deployment.md` → *Keycloak provider JAR* · **Decisions:** ADR-0055, ADR-0213

### REQ-OPS-013 — Idempotence fast-exit only over a verified running stack

(The ids REQ-OPS-008..012 are allocated to [`backup-recovery.md`](backup-recovery.md); this
requirement continues the series at the next free number.)

The idempotence marker (`last-deployed.digests`) records what the last **successful** deploy
applied — it says nothing about what is running *now*. Under Compose a manual `docker compose up`
without the digest-pin overlay resolves `:stable` from the **local** image cache (which `deploy.sh`
never refreshes — it always pulls by digest) and can silently start an outdated build; under Quadlet
the pin is part of each unit, so that particular path is closed, but a crash loop, a hand-stopped
service or a half-down stack still leaves the marker untouched on either runtime. `deploy.sh` must
therefore take the "no change" fast-exit **only after verifying the running stack against the
target digest set**: every app service (backend, frontend, ingest) has a container that is running,
healthy (a container without a healthcheck counts as healthy, mirroring `up --wait`), and created
from an image whose RepoDigest equals the target digest. Under Quadlet the containers are found by
the `PODMAN_SYSTEMD_UNIT` label rather than by a compose project, and a host with no unit files is
reported as such first. The run logs one `drift: <service>: <reason>` line per finding and then
distinguishes two classes, and the case where both occur:

- **Structural** — a missing container, or one on a non-target image: the release is wrong, so the
  run falls through to the normal apply path (verify, pin, pull, apply), still honouring a backoff
  so a persistently-failing re-apply does not flap every tick. It re-applies the **same** release,
  so it is not a release: it rotates no rollback anchor, and a re-apply that fails rolls nothing
  back — it is recorded as `DeployHealthRestartFailing`, never as `DeployRolledBack` or
  `DeployFailed` (REQ-OPS-003, since 2026-09-25). Its backoff is the self-heal's durations (300 s
  doubling to 1 h) from its own record, `reapply-failed.digests`, since 2026-09-26 — until then it
  was the release backoff (600 s doubling to 6 h) in `failed.digests`. An operator forces the same
  re-apply over a converged stack with `deploy.sh --reapply` (REQ-OPS-003).
- **Health only** — every divergent container is on the target image but not healthy: the release
  is right and the runtime is sick, so `deploy.sh` restarts **only** those services, with no pull,
  no signature re-verification and no release rollback, throttled by its own backoff
  (`IRI_HEALTH_RESTART_BASE` / `_MAX`, 300 s doubling to 1 h). A restart that does not restore
  health is recorded in `deploy-health.prom` and raises `DeployHealthRestartFailing`, never a false
  `DeployRolledBack` ([ADR-0083](../adr/0083-deploy-bot-health-drift-targeted-restart.md)).
  **Since 2026-09-25 the restart is one restart window** (ADR-0083's amendment, the release apply's
  shape from [ADR-0213](../adr/0213-a-release-that-moves-the-provider-jar-costs-one-outage.md)):
  one `systemctl --user stop` naming the unhealthy services — which takes down with them what
  `Requires=` them and nothing they require — then a `start` of every stack unit in order, each
  waited for until healthy. Each affected unit starts exactly once; an unhealthy frontend never
  touches backend or keycloak. The heal is reported resolved, and the healthy heartbeat stamped,
  only when every one of those starts returned healthy; until then a `restart` per service returned
  once the named unit was up, so an unhealthy backend was reported resolved while ingest and
  frontend still had no container, and a frontend unhealthy beside it was started twice.
- **Both** — a structural finding outranks a health one, so the run re-applies. The re-apply does
  not recreate the unhealthy container (its unit is active, and a `start` of it is a no-op); the
  next tick finds it health-only and heals it. Such a re-apply therefore neither stamps the healthy
  heartbeat nor clears the heal's backoff record.

`--check-only` reports the pending re-apply or restart without acting. Two deliberate exclusions keep the check free of false positives:
a container still inside its healthcheck **start period** (`running/starting`) counts as
converged for that tick (`up --wait` would merely wait on it, and a re-apply racing a slow
cold boot could record a false backoff failure for a good target — a genuinely broken container
surfaces as unhealthy/restarting on a later tick; a wrong image digest drifts regardless of the
start period), and one-off `docker compose run` containers are ignored (they are not part of
the deployed stack; Podman has no equivalent, so the flag is always false there). Incident precedent: 2026-07-02, a pre-V199 backend started manually
off the stale local `:stable` tag against a V201-migrated database crash-looped while
`deploy.sh` reported "no change" and exited 0.

Even this fast-exit path (and the config-changing apply, and every healthy tick in between) runs a
**self-healing monitoring-config reconcile** when the monitoring stack is enabled: Prometheus, Alloy
and the blackbox exporter serve their config from single-file bind mounts, so `deploy.sh` diffs each
component's on-disk config subtree against a persisted per-service snapshot of what it was last
applied (`${STATE_DIR}/monitoring-reload/<svc>`) and **force-recreates** the service on any drift,
refreshing the snapshot only after a successful recreate. A `SIGHUP` is deliberately *not* used: the
new config is written by `mirror_dir`/`rsync` as a fresh inode, and a single-file bind mount is
pinned to the inode it was created with, so the container keeps reading the old file until recreated
(a SIGHUP would just re-read the stale inode). This is decoupled from whether *this* tick swapped a
config bundle in: an apply that was skipped, lost, or bypassed by a rollback is retried on the next
tick until the running config matches on disk — the reason the 2026-07-11 ingest `TargetDown` (a
stale `11262` scrape target after the ADR-0090 port move, held through Prometheus's pinned inode even
though the on-disk file was already correct) could not have persisted. On a Prometheus recreate
`deploy.sh` stamps `basetool_monitoring_config_applied_timestamp{component="prometheus"}`, which backs
the `PrometheusConfigStale` alert (REQ-OBS-014). The reconcile is best-effort and **never gates** the
deploy — a stopped monitoring service or a failed recreate only logs — and force-recreates only on an
actual content change (config edits are rare, so the brief scrape gap is negligible). Under Quadlet
the recreate is `systemctl --user restart <svc>.service` for the containers and a root
`systemctl restart alloy.service` for Alloy, which runs as a host service (the deploy account holds
exactly that one sudo grant). Before that
per-service config diff the reconcile additionally applies the monitoring **definitions** — a plain
`docker compose -p iri-monitoring … up -d` (no `--force-recreate`) under Compose, a
`daemon-reload` plus, under Quadlet, a `systemctl --user restart` of each monitoring unit this
release re-defined and a `start` of the others — so a monitoring definition drift — a service's
memory limit, `environment`, `volumes`, or image pin, none of which the config-subtree diff can see —
is applied per service (a fast no-op otherwise).

> [!note] Closed 2026-09-22 — a changed monitoring or `acme` unit is restarted
> Found by the documentation audit the same morning and recorded here as a known gap:
> `systemctl --user start` on an **active** unit re-reads nothing, the monitoring apply said only
> `start`, and `acme` was in neither `RT_STACK_SERVICES` nor `RT_MONITORING_SERVICES` — so a
> release that changed a monitoring or `acme` unit installed it and left the old container running,
> the 2026-07-17 shape below reached by another road. `rt_monitoring_up` now honours
> `RT_CHANGED_SERVICES` exactly as `rt_apply_stack` does, and forgets a service once its restart
> succeeded, because the success path calls it twice (the monitoring apply, then
> `reconcile_monitoring_reloads`); a failed restart stays pending, so the second call is its retry.
> `acme` is the last entry of `RT_STACK_SERVICES` and is gated like the rest of the stack, as it was
> under Compose. Locked by `scripts/deploy.test.sh`
> (`scenario_podman_changed_monitoring_and_acme_units_are_restarted`,
> `scenario_podman_acme_is_part_of_the_stack`) and `scripts/container-runtime.test.sh`.

Without the definition apply a compose-definition change
reached the running container only on a full app deploy, so on a quiet host it silently never landed —
the 2026-07-17 case where Alloy ran the stale 256M/230MiB definition for days while disk said
384M/300MiB (and cAdvisor a stale containerd mount), tripping a chronic `ContainerWorkingSetHigh`. A
host that runs the monitoring stack **must** set `IRI_MONITORING_ENABLED=true` — in the host `.env`,
from which `deploy.sh` reads that one key when its environment does not carry it, or as an
`Environment=` drop-in on `iri-deploy.service`, which wins over the file. If the stack is running but
the flag is unset the reconcile gates itself off, yet the config-bundle rsync keeps rewriting
`monitoring/**` on disk every tick — so on-disk rule/scrape changes silently never reach the running
Prometheus (the 2026-07-13 drift). `PrometheusConfigStale` cannot catch that: its applied-stamp series
is written only from inside the gated reconcile, so the same condition disables both the reconcile and
its alarm. `deploy.sh` therefore logs a per-tick WARN and emits
`basetool_monitoring_reconcile_disabled{component="deploy"}` = 1 on its own textfile path, backing the
`MonitoringReconcileDisabled` alert (REQ-OBS-014). On a host with no monitoring stack running at all the
reconcile is a silent no-op — nothing scrapes the textfile there anyway.

**Acceptance**

- [ ] A matching marker over a converged, healthy stack exits 0 without pulling or restarting
  anything ("no change", running stack verified).
- [ ] On a host with monitoring enabled, a tick whose on-disk Prometheus config differs from the
  last applied snapshot force-recreates Prometheus — including on the converged no-op fast-exit —
  without pulling or re-applying the app stack; a component whose on-disk config already matches its
  snapshot is left untouched, and the reconcile never gates the deploy.
- [ ] On a host with monitoring enabled, every reconciling tick — including the converged no-op
  fast-exit — applies the monitoring definitions (`up -d` under Compose, `start` of the nine units
  under Quadlet) so a definition drift is applied per service, while a fully converged monitoring
  stack (config subtree matching every snapshot) triggers no recreate.
- [ ] On a host running the monitoring stack with `IRI_MONITORING_ENABLED` unset, every tick logs a
  WARN and emits `basetool_monitoring_reconcile_disabled{component="deploy"} == 1` (the
  `MonitoringReconcileDisabled` signal); with the flag set, the gauge is `0` and the reconcile runs.
- [ ] A matching marker over a container running a non-target image digest, or a missing
  container, triggers a logged drift re-apply of the same digest set.
- [ ] A matching marker over containers that are all on the target image but unhealthy or
  restarting triggers a targeted restart of only those services — no pull, no re-verify, no
  rollback — throttled by the health-restart backoff; a failed restart updates `deploy-health.prom`
  and writes no deploy-outcome metric.
- [ ] That restart is one `stop` of the unhealthy services followed by an ordered `start` of the
  stack, never a `restart`: every unit the stop took down (the unhealthy ones and what `Requires=`
  them) starts exactly once, every other unit zero times, and „resolved" and the healthy heartbeat
  are written only after all of them are up; otherwise the log names what did not come up.
- [ ] A missing container is brought back by a `start` — never a `restart` of a running unit it
  requires — and a drift re-apply that leaves an unhealthy at-target container alone stamps no
  healthy heartbeat.
- [ ] A drift re-apply of a target inside the re-apply backoff window (`reapply-failed.digests`,
  300 s doubling to 1 h) is skipped; a failed drift re-apply records the failure there, stamps the
  `deploy-health.prom` failure gauge, and does not roll back (REQ-OPS-003).
- [ ] `deploy.sh --check-only` over a drifted stack reports "would re-apply" and applies
  nothing.
- [ ] A container inside its healthcheck start period does not trigger a drift re-apply (but a
  non-target image digest does, even during the start period); a one-off `compose run`
  container never does.

**Enforced by:** `scripts/deploy.sh` (`running_stack_drift`, idempotence check, the health-drift branch, `reconcile_monitoring_reload(s)`) · `scripts/lib/container-runtime.sh` (`rt_service_container_ids`, `rt_heal_stack`, `rt_monitoring_recreate`) · `scripts/deploy.test.sh` (self-tests, run by `.github/workflows/deploy-script.yml`) · **Runbook:** `docs/deployment.md` → *What happens on the host*, *Driving the stack*

### REQ-OPS-014 — Every prod service runs with a hardened runtime baseline

**Every** `prod`-profile container runs with the runtime-hardening baseline, not just the edge
proxy: `security_opt: no-new-privileges:true`, `cap_drop: [ALL]` with an explicit, minimal
`cap_add` allow-list, and a `pids` ceiling. The intent is defence-in-depth against a
container-escape or in-container compromise — a service that cannot escalate privileges and holds no
capabilities it does not need is a far smaller blast radius, and it matters most on the two
internet-reachable edges (`edge`, `ingest`).

> [!important] `cap_drop: [ALL]` changes what *root inside the container* may do
> Dropping every capability also drops `DAC_OVERRIDE` and `FOWNER`, and a root process without them
> is not a privileged one: it obeys the file permission bits like any other user, and it may not
> `chmod` a file it does not own. Any container that runs as uid 0 under this baseline and touches
> files owned by another uid has to be written accordingly — set the mode before handing ownership
> away, and keep the directory it writes in. `acme` broke twice on exactly this
> (ADR-0162, REQ-OPS-026).

The capability add-back set is **per service**, defined by what each image's entrypoint actually
needs:

- **App services (`backend`, `frontend`, `ingest`) and `keycloak`** run as a fixed non-root uid
  (10001 for the JVM apps, 1000 for Keycloak/Quarkus) and bind only high ports, so they need **no**
  capabilities — `cap_drop: [ALL]` with an empty add-back.
- **`postgres` (db-backend, db-keycloak) and `redis`** boot as root to chown their data dir and then
  drop to their service user via gosu, so under Docker they keep `CHOWN`/`DAC_OVERRIDE`/`FOWNER` +
  `SETGID`/`SETUID`. `no-new-privileges` still holds because gosu drops via the `CAP_SETUID` syscall,
  not a setuid binary. **That set was measured on 2026-09-16 and is wrong in both directions**
  ([`PODMAN_MIGRATION_PLAN.md` §20](../archive/PODMAN_MIGRATION_PLAN.md)): Postgres needs four of the five, `FOWNER` never among them, and
  redis needs only `SETGID`/`SETUID`. Under Quadlet all three instead run **as their own uid** — 70,
  70 and 999 — read-only and with no capabilities at all ([ADR-0189](../adr/0189-stateful-containers-run-as-their-own-uid.md)).

> [!danger] For redis, a partial capability set is worse than the full one
> Its entrypoint tests `has_cap setuid && has_cap setgid` before dropping privileges and, finding
> neither, **skips the drop and runs as root** — healthy, answering `PING`, and green on every other
> check here. It then writes its append-only files as `0:0`, which the correct configuration can no
> longer open. Never remove `SETGID`/`SETUID` from redis without giving it a uid in the same edit,
> and assert the **uid of pid 1** rather than the capability list: `check-conformance.py`'s
> `containers-unprivileged` is what does that.

- **`edge`** runs as uid 101 on high ports (8080/8443) and needs **no** capabilities at all — an
  empty add-back on the one service most exposed to the internet. Under Quadlet it publishes them on
  loopback only, behind the host's haproxy front end (ADR-0187); under Compose they are published as
  80/443.
- **`acme`** runs as root, because lego writes its state as root, and keeps exactly `CHOWN`: it has
  to hand the issued certificates to uid 101 for the edge to read them. Nothing else — it listens on
  nothing and holds no inbound surface.

The **Quadlet units are the production posture** and are generated from the compose file, so the two
describe the same services with one runtime-specific difference each way: `NoNewPrivileges=true`,
`DropCapability=ALL` and a `PidsLimit=` on every unit, `AddCapability=CHOWN` on `acme` alone, and
`User=` 70/70/999 in place of the stateful services' compose `cap_add` sets.

Because the add-back set is not upstream-documented for the third-party images, it **must be
re-verified on every image bump** of that service before the bump is promoted (a clean boot and its
healthcheck passing). The deploy health-gate is the safety net: a wrong cap set fails the container
at start and is rolled back rather than shipped.

**A read-only root filesystem is part of the baseline under Quadlet** — all eighteen units
([ADR-0190](../adr/0190-every-container-but-keycloak-runs-read-only.md)). Earlier revisions of this
requirement said the opposite — *the JVM and DB working dirs write across the filesystem* — and that
was never measured; when it was, on 2026-09-16, it was wrong in both halves. A healthy Spring Boot
module writes Tomcat's work directory, its docbase and the JVM perf data, all three under `/tmp`,
and nothing else; its own source contains no filesystem write API at all. Nine of the ten
third-party images write nothing outside their mounts or write only under `/tmp`. **No `Tmpfs=`
entry was needed for any of them**, because Podman mounts `/run`, `/tmp` and `/var/tmp` itself under
`--read-only` and copies the image's content up into them — which **Docker does not do**, and is why
this lives in the Quadlet units rather than in the compose file.

**`keycloak` needs one `Tmpfs=` to get there, and it is the entry that has to be re-verified on
every image bump.** `kc.sh start` without `--optimized` re-augments the Quarkus application at every
boot, which plain read-only stops dead. A tmpfs over the 4.7M directory it rewrites — with
`tmpcopyup`, so the image content is present — lets it start ready with the real SPI provider
compiled in, at a measured 466M of its 2560M limit. That keeps [ADR-0055](../adr/0055-keycloak-spi-jar-as-promotable-oci-artifact.md)
intact: the provider JAR stays its own promotable artifact, a provider-only change still
auto-applies, and the rollback stays at JAR level. Baking the provider into a custom image and
running `start --optimized` would buy the same property by making every provider change an
operator-gated image rebuild.

Under Docker, `read_only: true` remains required of `edge` specifically — `tmpfs` for nginx's temp
paths and `/var/cache/nginx`, which Podman does **not** supply either — because that service exists
to face the internet.

`scripts/check-conformance.py`'s `containers-read-only` asserts the posture on a running host, in
both directions: a container that should be read-only and is not, and `keycloak` becoming read-only
without this record being updated.

**The config tree is mounted read-only, everywhere** (since 2026-09-22). `/var/iri/code` is what the
deployer rewrites from the signed config bundle on every release; a container that can write into it
can change what the next release applies. `keycloak` mounted its theme, its provider directory and
`realm-export.json` writable until then. The realm export is no longer mounted into the production
container at all — production runs `start` without `--import-realm`, so the file was never read — and
`generate-quadlet.py` refuses any bind mount from the config tree without `:ro`.

**The data networks carry no egress** (since 2026-09-22, ADR-0162 as extended). `net-db-backend`,
`net-db-keycloak` and the three `net-redis-*` networks are `Internal=true` in the Quadlet units, so
`db-backend`, `db-keycloak` and `redis` — which are on nothing else — have no outbound path.
*(Status 2026-09-25: applied on production — the five networks recreated `internal=true`, verified
by the missing default route in all three containers; the testing host still runs the old networks,
see `deployment.md` → Network changes are installed, not applied.)* **The compose file keeps all five non-internal, deliberately** (accepted by @greluc on 2026-09-22):
it also runs the local stacks, where the `-dev` twins publish `127.0.0.1:15432`, `:15433` and `:6379`
on these very networks for a developer's `bootRun`, and an internal network carries no DNAT — so
`internal: true` in compose would break every local database and Redis connection with no error at
startup. The list lives in `generate-quadlet.py` (`QUADLET_INTERNAL_NETWORKS`), which adds the key to
the units only; production publishes nothing on these networks.

**A container gets its stop grace** (since 2026-09-22). `stop_grace_period` becomes both
`[Container] StopTimeout=` (what `podman rm -f` waits before `SIGKILL`) and `[Service]
TimeoutStopSec=` fifteen seconds longer; until then only the second was generated and podman killed
every container after its 10 s default, cutting off the JVMs' graceful shutdown, the dskit drains and
PostgreSQL's clean shutdown.

**Acceptance**

- [ ] Every `prod`-profile service in `docker-compose.yml` (backend, frontend, ingest, keycloak,
  redis, db-backend, db-keycloak, edge, acme) sets `no-new-privileges:true`, `cap_drop: [ALL]` with
  an explicit (possibly empty) `cap_add`, and a `pids` ceiling.
- [ ] `backend`/`frontend`/`ingest`/`keycloak`/`edge` carry no `cap_add`; `acme` carries only
  `CHOWN`; `postgres`/`redis` carry only the chown + privilege-drop set (Compose only).
- [ ] `edge` additionally runs `read_only: true` under Compose.
- [ ] Every generated `.container` unit carries `NoNewPrivileges=true`, `DropCapability=ALL`, a
  `PidsLimit=` and `ReadOnly=true`; only `acme` adds a capability (`CHOWN`); `db-backend`,
  `db-keycloak` and `redis` run with `User=`/`Group=` 70, 70 and 999. `check-conformance.py`'s
  `containers-unprivileged` and `containers-read-only` assert the running host.
- [ ] Every `Volume=` whose source is under `/var/iri/code` ends in `:ro`, and the production
  `keycloak` unit mounts no `realm-export.json` (`generate-quadlet.py` refuses the first,
  `generate-quadlet.test.sh` asserts both).
- [ ] `net-db-backend`, `net-db-keycloak`, `net-redis-backend`, `net-redis-frontend` and
  `net-redis-ingest` are `Internal=true`; no unit whose every network is internal publishes a port or
  aliases the host gateway (`generate-quadlet.py` refuses it).
- [ ] Every unit with a `TimeoutStopSec=` carries a `StopTimeout=` fifteen seconds below it.
- [ ] An image bump for any of these services is only promoted after the new image has been
  booted under its unit's hardening once (clean start + healthcheck).

**Enforced by:** `docker-compose.yml` (all `prod` services) · `scripts/generate-quadlet.py` and the
generated `quadlet/systemd/*.container` · `scripts/check-conformance.py` · **Decisions:** ADR-0189,
ADR-0190

### REQ-OPS-015 — Host-side signature verification before apply

The production host **cryptographically verifies** every artifact it is about to run. Before
`deploy.sh` pulls, extracts or applies any resolved digest — the three app images and, when
present, the `basetool-config` and `basetool-keycloak-spi` bundles — it `cosign verify`s that
`image@digest` against the **release-images** workflow's keyless (Fulcio/OIDC) signature. This is
the **host half** of the supply-chain seam; `promote.yml`'s pre-flight verify (REQ-OPS-002) is the
CI half. Neither alone is sufficient: `promote.yml` verifies at promotion time in CI, but the host
re-resolves `:stable` independently on every tick, so a `:stable` tag moved out-of-band — a leaked
`packages:write` credential retagging an arbitrary digest, or a registry-side tag manipulation —
would otherwise be pulled and run **unverified** — as the service user under rootless Podman, and
as root-equivalent `docker`-group code on a Docker host. The host gate closes that TOCTOU: the tag verified at promote time is no longer
assumed to be the artifact the host pulls later.

The trusted signer identity is pinned to
`^https://github\.com/<repo>/\.github/workflows/release-images\.yml@refs/(heads/main|tags/v[0-9]+\.[0-9]+\.[0-9]+)$`
— a main-branch or release-tagged build only, never a `workflow_dispatch` build off an arbitrary
branch — and the **same** identity is used by `promote.yml`, `promote-testing.yml` and `deploy.sh`
so the halves cannot diverge. It is **anchored at both ends**: cosign matches the regexp anywhere in
the certificate SAN, so the unanchored `…@refs/(heads/main|tags/v.+)` it replaced on 2026-09-22 also
trusted `refs/heads/main-x`, `refs/heads/maintenance` and `refs/tags/vfoo` (audit item CI-SEC-01).
`scripts/check-cosign-identity.py` (repo-lint) asserts every copy is anchored, that the copies are
byte-identical after shell unquoting, and that they accept and refuse the right subjects;
`scripts/deploy.test.sh` runs deploy.sh's default against the same subjects.

The signing side matches it: `release-images.yml`'s `ref-guard` job refuses to build, push or sign
from any ref other than `refs/heads/main` or a `vMAJOR.MINOR.PATCH` tag, and a tag only when its
commit is an ancestor of `main` (audit item CI-SEC-02) — so a release identity never covers code
that did not pass review. Who may create such a tag at all is the tag ruleset's `creation` rule.
The gate is **fail-closed**: a host without `cosign` (with verification enabled) aborts the tick
rather than falling back to trusting an unverified image. A single break-glass override
(`IRI_COSIGN_VERIFY=false`) exists **only** to ride out a Sigstore public-good outage that is
blocking every deploy; it is logged loudly on every skipped verification.

**Version compatibility.** cosign is not backward-compatible across majors: cosign 3.x verifies
both 2.x and 3.x keyless signatures, but cosign 2.x **cannot** verify 3.x signatures. The CI signs
with the cosign that `sigstore/cosign-installer` pins (currently 3.0.6 via `@v4.1.2`), so the host
cosign must be **≥ that version and never a lower major** — otherwise the fail-closed gate blocks
every deploy. The host tracks the current 3.x release (3.1.3); when the CI installer pin is bumped,
the host is kept ≥ it. The host binary is also kept current for its **own** advisories, not only for
signature compatibility: cosign ≤ 3.1.2 carries GHSA-fx35-mq7g-6g98, an identity-pinning bypass that
reaches `verify-blob` only and therefore never exposed this OCI-image gate.

**A transient failure is not a supply-chain alarm.** Verification is a network round-trip — it
fetches the signature layer from GHCR and reaches the Sigstore roots — so it fails for the same
transient reasons `release-images.yml` already wraps `cosign sign` in a 5-attempt retry for. The
gate therefore **retries** (`IRI_COSIGN_VERIFY_ATTEMPTS`, default 3, delay
`IRI_COSIGN_VERIFY_DELAY` doubling per attempt) before it escalates, and it **captures cosign's
stderr** so both the retry log and the abort quote the actual reason. Discarding that output made a
registry blip and a forged image produce byte-identical operator-facing output. On 2026-08-05 that
cost a critical `DeployFailed` page reading "refusing to deploy an unverified/untrusted image":
the identical digest verified cleanly by hand minutes later, and the same tick had also failed to
resolve `keycloak-spi:stable` — one GHCR hiccup, two symptoms. Retrying costs at most a few seconds
on a genuinely bad signature, which still aborts fail-closed.

**Acceptance**

- [ ] A verification that fails transiently is retried before the tick aborts; only an exhausted
  retry budget escalates to the security abort and the deploy-failure metric.
- [ ] The retry log line and the abort both quote cosign's own stderr, so a registry/Sigstore error
  is distinguishable from a signature mismatch without host access.
- [ ] `deploy.sh` runs `cosign verify` (identity
  `^…/release-images\.yml@refs/(heads/main|tags/v[0-9]+\.[0-9]+\.[0-9]+)$`, anchored,
  issuer `token.actions.githubusercontent.com`) against every resolved `image@digest` — backend,
  frontend, ingest, and the config + keycloak-spi bundles when resolved — **before** the first
  `pull`, `create`/`cp` extraction or apply, and aborts the tick non-zero on any verification failure.
- [ ] A `:stable` digest that is unsigned or signed by any other identity is never pulled, extracted
  onto the host, or applied; the failure records a deploy-failure metric (surfacing `DeployFailed`).
- [ ] Verification does not run on the steady-state idempotence no-op tick (on the apply path it
  runs only once the tick is committed to applying, past the no-op and the bad-digest backoff).
- [ ] `deploy.sh --check-only` verifies every resolved digest as a dry-run signature preflight —
  reporting per artifact and exiting non-zero on failure — **without** writing a deploy metric or
  applying anything (so it does not trip `DeployFailed`); it runs even over a converged no-op stack.
- [ ] `cosign` missing on the host with `IRI_COSIGN_VERIFY=true` fails the pre-flight; the sole
  documented override is `IRI_COSIGN_VERIFY=false` for a Sigstore outage. "Not on `PATH`" is not
  "missing": the pre-flight also looks in `/usr/local/bin`, `/usr/bin` and `/opt/cosign/bin`, because
  sudo's `secure_path` on Rocky omits the directory the role installs into.
- [ ] The host cosign is installed by the Ansible role from the upstream release, checked against
  a sha256 pinned in the role's defaults; upgrading it is a reviewed change to that pin.
- [ ] `promote.yml` and `promote-testing.yml` verify against the identical, anchored identity
  regexp; `release-images.yml`'s reuse gate uses the same regexp narrowed to `heads/main`.
  `scripts/check-cosign-identity.py` fails repo-lint on an unanchored or divergent copy, and
  `scripts/deploy.test.sh` refuses `refs/heads/main-x`, `refs/heads/maintenance`, `refs/tags/vfoo`
  and `refs/tags/v1.9.2-rc1` while accepting `refs/heads/main` and `refs/tags/v1.9.2`.
- [ ] `release-images.yml` signs nothing unless the run's ref is `refs/heads/main` or a
  `vMAJOR.MINOR.PATCH` tag whose commit is an ancestor of `main` (`ref-guard` job, which every
  signing job depends on).
- [ ] The host `cosign` is a major **≥** the cosign the CI signs with (`cosign-installer` pin, 3.x);
  a host on cosign 2.x cannot verify the 3.x signatures and is a mis-bootstrap, not a supported mode.

**Enforced by:** `scripts/deploy.sh` (`verify_signature`, `verify_digest_or_die`, cosign pre-flight)
· `scripts/deploy.test.sh` (signature-gate self-tests, including the anchored identity) ·
`.github/workflows/promote.yml` (pinned identity) · `scripts/check-cosign-identity.py` (repo-lint:
every copy anchored and identical) · `.github/workflows/release-images.yml` (`ref-guard`) ·
`ansible/roles/basetool_host/tasks/15-cosign.yml` · **Runbook:** `docs/deployment.md` → *Signature verification (cosign)* · **Decision:** ADR-0075

### REQ-OPS-016 — Deploy host runtime hardening

The deploy path is hardened at the host layer, beyond running as an unprivileged user:

- **The privilege boundary is a named sudo bridge, not a group.** Under rootless Podman the
  `deploy` account reaches the service user's containers through `/etc/sudoers.d/basetool-deploy`:
  `podman *` and `systemctl --user *` as that one user, and `systemctl restart alloy.service` as root
  — nothing else (`22-deploy-user.yml`). The Docker deployment's `docker` group, which is
  root-equivalent by design, does not exist on the production host.
- **Systemd sandbox.** `iri-deploy.service` confines the `deploy.sh` process with
  `ProtectSystem=strict`, `ProtectHome=read-only`, `PrivateTmp`, `PrivateDevices`,
  `ProtectKernelTunables`/`Modules`/`Logs`, `ProtectControlGroups`, `ProtectClock`, `ProtectHostname`,
  `ProtectProc=invisible`, `RestrictRealtime`, `RestrictSUIDSGID`, `RemoveIPC`, `LockPersonality`,
  `RestrictAddressFamilies` limited to AF_UNIX/AF_INET/AF_INET6/AF_NETLINK,
  `SystemCallArchitectures=native`, a `SystemCallFilter=@system-service @mount` seccomp allow-list,
  and a `CapabilityBoundingSet=~` **blocklist** (`CAP_SYS_ADMIN`, `CAP_SYS_MODULE`, `CAP_SYS_RAWIO`,
  `CAP_SYS_BOOT`, `CAP_SYS_TIME`, `CAP_NET_ADMIN`, `CAP_MAC_ADMIN`, `CAP_MAC_OVERRIDE`,
  `CAP_SYS_PTRACE`). `ReadWritePaths` is the **narrow** set the script actually writes
  (`/etc/containers/systemd/users /var/lib/iri /var/log /var/lock /var/iri/code
  /var/iri/monitoring`) — NOT the whole `/var/iri`, whose database and redis bind mounts are written
  by the containers, out-of-band from this sandbox.

  > [!note] Corrected 2026-09-22 — four directives this requirement named are gone, on purpose
  > It required `NoNewPrivileges`, `RestrictNamespaces`, an **empty** `CapabilityBoundingSet` and a
  > bare `@system-service` filter. Each was measured on 2026-09-18 to make the unit unable to run on
  > the rootless platform: `NoNewPrivileges` forbids the sudo bridge above, rootless Podman *is* a
  > user namespace, sudo's switch and PAM stack need more than no capabilities, and Podman sets up
  > its container filesystem with the `@mount` family. The unit file carries the measurement for
  > each. The boundary they approximated is now the sudo bridge, which is narrower than the docker
  > group they were compensating for.
- **Keystore not world-readable.** The shared `keystore.p12` is `0640` with its group set to the
  app modules' uid and a POSIX ACL granting read to Keycloak's — under rootless Podman the
  **translated** host uids, `root:110000` plus `user:100999:r` (subuid base 100000), and an extra
  `user:iri:r` so the backup helper, which runs as the service user, can read it. The private-key
  material is never readable by `other`; the Docker host's `root:10001` + `u:1000` is the same rule
  before translation. The per-service keystores under `/var/iri/secrets/tls/` (REQ-SEC-070)
  follow the same rule, each readable only by the service it belongs to (and `iri` for the backup);
  the CA-only truststore and `ca.crt` hold no key and may be `0644`.
- **The pre-flight checks the keystore the stack will actually mount** (since 2026-09-22). Under
  Compose that is `IRI_KEYSTORE_HOST_PATH` from `.env`; under Quadlet it is the source of the units'
  `Volume=…:/run/secrets/keystore.p12`, which `generate-quadlet.py` fixes at
  `/var/iri/secrets/keystore.p12` and which never reads `.env` again. `deploy.sh` checked `.env` on
  both runtimes until then, so on the Podman host it could certify a file the units do not mount; it
  now reads the path out of the installed units and falls back to `.env` only while no unit names a
  keystore mount (a host before its first bundle). Since REQ-SEC-070 it checks **every**
  PKCS#12 the units mount under `/run/secrets/` — each service's own keystore and the internal
  truststore — not just the first match.
- **The deployer runs from `/`.** `sudo -u <service user>` keeps the caller's working directory and
  the service user cannot enter `/root`, so `deploy.sh` started by hand from there failed every
  rootless call with `cannot chdir to /root`, which runtime detection reported as "no lingering user
  could be found". It changes to `/` right after resolving its own directory (since 2026-09-22), as
  `container-cleanup.sh` already did. The timer was never affected: systemd starts a service in `/`.
- **The sandboxed jobs start only when they can see the stack, and only when they are due**
  (since 2026-09-25). A timer only *triggers* its service (`Unit=`); none of the `iri-*.timer`
  files pulls it in with `Requires=`/`Wants=`, which started all four deploy-account jobs at every
  boot without the timer elapsing. The four units are ordered
  `After=systemd-logind.service user@<service uid>.service` by the role's drop-in
  `20-service-user.conf` — ordering only, never a pull-in — because their sandbox
  (`ProtectHome=read-only` covers `/run/user`) never sees a runtime mounted after it was built.
  `rt_detect` runs no podman before the service user's runtime is visible, waits (bounded, 120 s)
  while a visible runtime refuses and its manager is still `initializing`/`starting`, answers a
  refusal from a `running`/`degraded` manager at once, and quotes podman's error in its refusal.
  A normal tick is unchanged: the runtime is visible and podman answers first time.
- **Token expiry is monitored (opt-in).** When the pull token **expires**, `deploy.sh` emits
  `basetool_ghcr_token_expiry_timestamp` from an operator-recorded `${TOKEN_FILE}.expiry` on every
  tick (incl. the no-op); `GhcrPullTokenExpiring` (warning, <14 d) and `GhcrPullTokenExpired`
  (critical) alert before/at the lapse, whose expiry would otherwise silently stop all deploys.
  A deliberately **non-expiring** token omits the `.expiry` file — no metric, and the alerts do
  **not** fire on absence (no `absent()` guard), so a non-expiring token is not falsely warned on.

**Acceptance**

- [ ] `iri-deploy.service` sets the sandbox baseline above, the capability blocklist, a seccomp
  `SystemCallFilter`, and a `ReadWritePaths` that excludes the database and redis bind mounts.
- [ ] The deploy account holds no privileged group and no sudo grant beyond the three commands of
  the bridge; `22-deploy-user.yml` proves the grant works rather than only that it parses.
- [ ] The keystore is `0640` with a Keycloak read ACL (translated uid on the rootless host), never
  world-readable; the runbook and the restore procedure re-apply the ACL, which restic does not
  carry.
- [ ] `deploy.sh` writes `basetool_ghcr_token_expiry_timestamp` when `${TOKEN_FILE}.expiry` exists
  (and nothing when it does not); `ops-automation.yml` alerts on <14 d / expired only — **not** on
  absence, so a non-expiring token is not false-warned.
- [ ] No `scripts/iri-*.timer` carries `Requires=`/`Wants=`/`BindsTo=`/`Requisite=`/`Upholds=`, and
  each names its service in `Unit=`; the role installs `20-service-user.conf` beside every unit in
  `basetool_host_deploy_account_units`, with `After=systemd-logind.service user@<uid>.service` and no
  pull-in (`container-runtime.test.sh`). On a host, `systemctl show iri-backup.service -p After`
  names `user@<uid>.service`.
- [ ] `rt_detect` runs no podman while the runtime is not visible, waits out a refusal while the
  manager is starting, refuses at once for a running/degraded one, and its refusal carries podman's
  last error line (`container-runtime.test.sh`). After a reboot no `iri-*` unit is `failed`.

**Enforced by:** `scripts/iri-deploy.service` (sandbox) · `scripts/deploy.sh` (`write_token_expiry_metric`,
`HOME` for the cosign cache) · `monitoring/prometheus/alerts/ops-automation.yml` (token alerts) ·
`ansible/roles/basetool_host/tasks/22-deploy-user.yml` (the sudoers bridge) ·
`scripts/iri-*.timer`, `ansible/roles/basetool_host/templates/iri-deploy-account-order.conf.j2`,
`scripts/lib/container-runtime.sh` (`rt_runtime_visible`, `rt_probe_service_user`) ·
**Runbook:** `docs/deployment.md` → *Accounts and what runs where*, *Internal keystore and
certificate rotation*, *Token rotation*

### REQ-OPS-018 — Redis session store: durable persistence and a session-safe memory ceiling

> [!note] Planned amendment — external client exchange (epic #2078, [`external-exchange.md`](external-exchange.md))
> Before the first exchange release, `maxmemory` rises from 384 MB to a fixed **768 MB** and the container limit to **1024 MB** (ADR-0221, owner decision 2026-09-26), checked against host RAM; the Redis memory alerts follow. It is a gated production write (WP 2.1, #2092).

The Redis instance backing Spring Session (frontend) and the ingest handoff staging runs with a
durability and memory posture matched to a store whose loss forces users to re-login — **not** a
throwaway cache (Redis is session-store only, ADR-0074):

- **Durable persistence — RDB + AOF.** `--appendonly yes --appendfsync everysec` makes AOF the
  primary durability layer (~1 fsync/s regardless of write volume; ~1 s worst-case loss on a crash),
  and `--save "60 1"` keeps a compact RDB snapshot for fast restart. On restart Redis loads the AOF.
  This bullet said the save cadence also existed "to keep the `RedisRdbStale` probe green" until
  2026-09-08; **no cadence can do that**, because Redis snapshots only when a key changed, so an idle
  store's save age climbs regardless — which is what made the unguarded probe page twice that night.
  The probe was corrected instead (see the observability bullet below). Both files live on the
  `/var/iri/redis` bind mount and are **excluded from off-site backups** (REQ-OPS-010 — sessions transparently re-login). The
  `appendfsync always` mode (one fsync per write, the pre-M-7 pathology) is deliberately **not** used.
- **Bounded memory — explicit ceiling below the cgroup.** `--maxmemory 384mb` sits below the 512 MB
  container limit, leaving copy-on-write headroom for the RDB / AOF-rewrite forks and fragmentation,
  so Redis manages the boundary itself instead of ceding it to the kernel OOM-killer. Raised from
  `192mb` / 256 MB by ADR-0085 for the 5000-account session index; this requirement kept quoting the
  pre-ADR-0085 pair until 2026-09-08, so its acceptance list contradicted the shipped compose file.
- **Session-safe eviction — `noeviction`.** `--maxmemory-policy noeviction` is **mandatory**:
  evicting a session key is a silent logout, so at the ceiling Redis refuses **new** writes (a failed
  login) while every live session survives. An evicting policy (`allkeys-*` / `volatile-*`) is a
  defect here.

Both command lines — the `redis-dev` template and the `redis` prod override (which additionally
carries `--aclfile`) — stay in lockstep on these persistence/memory flags, and both carry
`--notify-keyspace-events Egx`, which Spring Session needs and the frontend's own ACL user may not
set itself (REQ-SEC-068). The posture is
**observable**: because `--maxmemory` is set (`redis_memory_max_bytes > 0`), the `RedisMemoryHigh`
leading-indicator alert is functional (it self-guards on that being non-zero and was inert while
maxmemory was unset), and `RedisEvictions` is a misconfiguration tripwire (any eviction under
`noeviction` means the policy was wrongly changed) — both in the alert catalog (REQ-OBS-005).

The **persistence** alerts distinguish a broken snapshot from an idle one. `RedisRdbStale` requires
`redis_rdb_changes_since_last_save > 0` alongside the hour-old timestamp: Redis clears that counter
only on a successful save and restores it when a fork fails, so pending-and-unsaved is a real
failure while pending-zero is a quiet store. The failure modes the guard does not cover get their
own direct rules — `RedisRdbSaveFailing` on `rdb_last_bgsave_status:err` (5 min, independent of write
volume) and `RedisAofWriteFailing` on `aof_last_write_status:err`, which is the first alert this
posture has ever had on the **primary** durability layer.

**Acceptance**

- [ ] Both redis command lines in `docker-compose.yml` set `--appendonly yes --appendfsync everysec`,
  `--save "60 1"`, `--maxmemory 384mb`, and `--maxmemory-policy noeviction`; the prod override keeps
  `--aclfile` and the two lines carry identical persistence/memory flags and
  `--notify-keyspace-events Egx`.
- [ ] `--maxmemory` (384mb) is strictly below the container memory limit (512M) so a snapshot /
  AOF-rewrite fork has copy-on-write headroom.
- [ ] The eviction policy is `noeviction`; no `allkeys-*` / `volatile-*` policy is configured.
- [ ] `RedisMemoryHigh`, `RedisEvictions`, `RedisRdbStale`, `RedisRdbSaveFailing` and
  `RedisAofWriteFailing` exist in `infrastructure.yml` and their descriptions match this posture
  (384mb maxmemory, noeviction semantics, AOF-primary durability).
- [ ] `RedisRdbStale` carries the `and redis_rdb_changes_since_last_save > 0` guard, so an idle store
  with an ageing snapshot does not page. Pinned by
  `monitoring/prometheus/tests/redisrdbstale_idle_guard_test.yml`.

**Enforced by:** `docker-compose.yml` (`x-redis` template + `redis` prod override) · the generated
`quadlet/systemd/redis.container` (`Exec=` carries the prod line; `quadlet-drift` keeps it equal) ·
`monitoring/prometheus/alerts/infrastructure.yml` (Redis memory/persistence alerts) · **Decision:** ADR-0079

### REQ-OPS-019 — Containers with a wget-HTTPS healthcheck reap orphaned subprocesses (PID-1 zombie reaping)

**Scope rule (amended 2026-07-26): this applies to *every* container — in either compose file —
whose PID 1 is not an init and whose healthcheck fetches an `https://` URL with BusyBox `wget`.** It
is not a JVM property. The requirement was originally written for the three JVM app services and
therefore silently exempted `grafana`, which carries the identical probe against its own TLS port
with a Go binary as PID 1 (Go does not reap orphans either). It leaked one `ssl_client` zombie per
30 s probe into a `pids: 512` cap — roughly four hours to exhaustion — and on 2026-07-26 was found
at 512/512 with 493 zombies and `pids.events max=7445`, refusing every `fork()`: the healthcheck
itself could no longer run (`/bin/sh: can't fork: Resource temporarily unavailable`), so the
container reported `unhealthy` for two days while the Go server, which needs no fork, kept serving.
When adding any service, check the probe, not the runtime.

Every JVM app service (`backend`, `frontend`, `ingest`) runs with a zombie-reaping init as PID 1
(`init: true`, Docker's bundled tini). The image entrypoint `exec java …` makes the JVM PID 1, and a
bare JVM does **not** `wait()` on children reparented to it. The Docker HEALTHCHECK probes the
internal management port with BusyBox `wget --no-check-certificate https://…` every 30 s; BusyBox
`wget` forks an `ssl_client` TLS helper it does not reap, which on exit reparents to PID 1 and, with
no reaper, lingers as a `<defunct>` zombie. One zombie accumulates **per probe** (≈1 / 30 s) until the
container's `pids` cgroup ceiling (REQ-OPS-014, 2048) is exhausted — at which point the JVM can no
longer create an OS thread (`pthread_create failed (EAGAIN)`, a native-thread OOM) at ≈17 h uptime,
**independent of heap and of `jvm_threads_live`** (which stays flat, so `JvmThreadsHigh` never catches
it — the leak is non-JVM pids). `init: true` inserts tini as PID 1, which reaps these orphans while
still `exec`-ing the JVM and forwarding signals (graceful shutdown and `stop_grace_period` unchanged);
it needs no capability and is compatible with `no-new-privileges` + `cap_drop: [ALL]` (REQ-OPS-014).
Keycloak (bash `/dev/tcp` probe), Postgres (`pg_isready`) and Redis (`redis-cli`) fork no TLS helper
and are unaffected. Incident precedent: 2026-07-12, `ingest` (the longest-lived continuous uptime of
the three) exhausted its `pids` cap this way and entered a restart loop; `716` container processes
were `712 × (ssl_client) Z`.

**Acceptance**

- [ ] `backend`, `frontend` and `ingest` (via their `x-*` compose templates) **and `grafana`** set
  `init: true`, which the generator carries into the units as Quadlet's `RunInit=true` (a
  `PodmanArgs=--init` until 2026-09-22); a prod container
  that runs a wget-HTTPS healthcheck with a bare runtime as PID 1 is a regression.
- [ ] A long-lived (>17 h) app container's `pids` count stays flat instead of climbing ≈1 per 30 s
  healthcheck — no `<defunct>` `ssl_client` accumulation (spot-check: `docker exec <svc> sh -c 'cut
  -d" " -f3 /proc/[0-9]*/stat | sort | uniq -c'` shows no growing `Z` count).
- [ ] Host-side cross-check that needs no working `fork()` in the container (the spot-check above
  cannot run once the cap is hit, which is exactly when it matters): for each container,
  `pids.current` in its cgroup stays well under `pids.max` — under rootless Quadlet that is
  `/sys/fs/cgroup/user.slice/user-<uid>.slice/user@<uid>.service/app.slice/<svc>.service/`
  (the path `scripts/cgroup-container-metrics.py` reads), under Docker
  `/sys/fs/cgroup/system.slice/docker-<id>.scope/` — and
  `ps -eo stat,ppid | awk '$1 ~ /^Z/ && $2 == <container host PID>'` counts zero zombies.
- [ ] `ContainerPidsHigh` (REQ-OBS-014) covers the service — it is cap-relative
  (`basetool:container:pids / basetool:container:pids_max > 0.8`, recording rules fed by the cgroup
  collector on the Podman host and by cAdvisor's `container_threads` on a Docker one) and unscoped by name, so a new service is
  monitored automatically.
- [ ] `.github/scripts/check_pid1_reaping.py` passes. It resolves each service's *effective* probe
  the way Docker does (explicit compose `healthcheck` first, else the image's `HEALTHCHECK` — which
  for our own images it reads out of the Dockerfiles, since the dev-profile services declare no
  compose healthcheck at all) and fails the build on any forking probe without a reaping PID 1.

**Defence in depth.** Three independent layers, because both incidents were silent until the cap was
already exhausted: (1) `init: true` on the **shared** templates — `x-backend` / `x-frontend` /
`x-ingest` in `docker-compose.yml` and `x-mon-base` in `docker-compose.monitoring.yml`, the latter
covering **all 13** monitoring services rather than only grafana, so switching any of them to an
HTTPS probe cannot re-arm the leak; (2) the CI gate above, which blocks the regression at review
time; (3) `ContainerPidsHigh` as the runtime backstop for a task leak from any *other* source.

**Enforced by:** `docker-compose.yml` (`x-backend` / `x-frontend` / `x-ingest` templates, `init:
true`) · `docker-compose.monitoring.yml` (`x-mon-base` anchor, `init: true`) ·
`.github/scripts/check_pid1_reaping.py` (wired into the `pid1-reaping` check of
[`repo-lint.yml`](../../.github/workflows/repo-lint.yml), with a self-test that keeps it from
passing vacuously) · the spot-checks in the acceptance list above

### REQ-OPS-020 — Container resource limits are derived from measurement, never from a ratio

Every `memory:` and `cpus:` limit in `docker-compose.yml` and `docker-compose.monitoring.yml`, and
every runtime sizing knob derived from one (`MaxRAMPercentage`, `GOMEMLIMIT`, Postgres
`shared_buffers` / `effective_cache_size` / `work_mem`, Redis `maxmemory`, HikariCP
`maximum-pool-size`), is **derived from a production measurement, and that measurement is recorded
in the [sizing ledger](#sizing-ledger) below**. Changing any of them without a measurement to point
at is a defect, even when the direction is "safer".

Amended 2026-09-25 (ADR-0214): the measurements moved from compose comments into this ledger.

This requirement exists because the same error class has shipped four separate times, always by
applying a **percentage or a ratio** where a **budget** was needed:

- `alloy` was raised 192M → 256M → 384M → 512M against a working set that was mostly its own
  memory-mapped binary — page cache expands to fill whatever limit it is given, so the ratio always
  crept back (ADR-0085).
- The JVM limits were raised on the reasoning that "the alert keys off the working_set/limit ratio,
  so it auto-adjusts" — but `MaxRAMPercentage` is a ratio too, so the heap ceiling scaled with the
  limit and the watched fraction never moved (PR #1419).
- Postgres `shared_buffers` was scaled up alongside the container RAM for a projected
  "5000-account working set", reaching **4.75× the size of the entire database** (#937).
- `prometheus` and `tempo` carry deliberate deviations from the `GOMEMLIMIT = 75 %` convention;
  both are recorded *as exceptions with their measurement* rather than silently normalised.

**The binding rules:**

1. **Size from a measured peak over a window of at least seven days, and state the multiple.**
   "1536M is 5.2× the measured 295 MB working-set peak" is a sizing; "2/3 of the limit" is not.
2. **Pick the right metric for the runtime.** `container_memory_rss` (anonymous) for Go and JVM
   services — never `container_memory_working_set_bytes`, which counts the reclaimable mapped
   binary and cannot cause an OOM. **Postgres is the exception**: `shared_buffers` is POSIX shared
   memory and lands in the cgroup `cache` term, so a DB container is read from its working set.
3. **Never re-apply a percentage to a limit that moved.** Re-derive the absolute budget:
   `ceiling + measured overhead ≤ ~80 % of the limit`.
4. **Check the floor before tuning a ratio.** If `used + overhead` already exceeds the target, no
   percentage exists that fits and the container is simply undersized.
5. **CPU is sized from absolute throttled seconds**, not from a peak or an average throttle ratio
   (they routinely disagree by two orders of magnitude). A quota is a burst ceiling, not a
   reservation: overcommitting the sum past the physical core count is permitted and expected.
6. **Record what would trigger a re-review** — the growth that would invalidate the multiple.
   And when a re-review finds a rise, **attribute it before reading it as load**: split the peak by
   container generation first, because a resident set that stepped at an image bump did not grow
   with traffic. Tempo's 2026-08-29 re-measurement (#1705) is the worked example — its three
   **3.0.2** container generations peaked at 479.8 / 486.0 / 523.1 MiB and both **3.0.3**
   generations at 595.3 / 665.3 MiB, a ~27 % step at the version boundary that the previous reading
   had attributed to trace volume. (Bare versions on purpose: written as a full `image:tag` this
   sentence reads as a pin, and the image-pin gate would rewrite the historical 3.0.2 to today's
   tag — erasing the very comparison it makes.) `max_over_time(container_memory_rss{name="…"}[30d])` returns one
   series per generation, so the split costs nothing.
7. **The sum of all limits across both compose files stays under the host budget with real
   headroom** (ADR-0085's `~14 GB` review trigger on the 16 GB CPX42). Exceeding it is not
   forbidden but requires an explicit owner decision recorded in ADR-0085, because the sum bounds
   the pathological simultaneous-spike case the limits exist to survive.

#### Sizing ledger

Host: Hetzner CPX42, 8 vCPU / 16 GB (15.24 GiB MemTotal). Unless a row says otherwise, figures are
the 7-day production peaks ending 2026-08-03 (#937); "2026-09-13" rows are the CPU re-measurement
under real use, whose stall is `throttled seconds / throttled periods`. A CPU quota is a burst
ceiling (rule 5), so its headroom is the stall it removes, not a multiple.

**Sum of memory limits** (recomputed 2026-09-25 from the declared values): app 9792 MiB +
monitoring 4208 MiB = **14 000 MiB (13.67 GiB)**, under ADR-0085's ~14 GB trigger. The 2026-09-13
figure of 4368 MiB monitoring still counted `cadvisor` (128M) and the docker-socket proxy (32M),
both removed 2026-09-22. CPU quotas total 17.0 vCPU on 8 physical (overcommit is intended).

`docker-compose.yml`

| Service     | Knob                                        | Value                | Measured figure                                                                                                   | Headroom                                                                                   |
|-------------|---------------------------------------------|----------------------|-------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------|
| db-backend  | `memory`                                    | 1536M                | working-set peak 295 MB; database 107.7 MB                                                                        | 5.2× peak; not cut further (a DB OOM costs a recovery cycle)                               |
| db-backend  | `cpus`                                      | 4.0                  | 2026-09-13: 161.1 s throttled over 703 events in 29 h (229 ms stall) at 0.024 cores average                       | burst room for one backend process per Hikari connection                                   |
| db-backend  | `shared_buffers`                            | 384MB                | database 107.7 MB, cache hit 99.990 %                                                                             | 3.6× the database; re-review when the DB quadruples                                        |
| db-backend  | `effective_cache_size`                      | 1024MB               | host MemAvailable ≥ 9.84 GiB throughout                                                                           | 9.5× the database (planner estimate, not an allocation)                                    |
| db-backend  | `work_mem` / `maintenance_work_mem`         | 8MB / 64MB           | 0 temp files                                                                                                      | proven sufficient; unchanged                                                               |
| db-backend  | `max_connections`                           | 150                  | peak 31 / average 4.7                                                                                             | covers the 100-slot Hikari pool + exporter, Flyway, admin                                  |
| db-keycloak | `memory`                                    | 512M                 | working-set peak 87 MB; database 39.4 MB                                                                          | 5.9× peak                                                                                  |
| db-keycloak | `cpus`                                      | 1.0                  | 23 s throttled in 7 days, peak ratio 1.5 %, average 0.132 %                                                       | unchanged                                                                                  |
| db-keycloak | `shared_buffers` / `effective_cache_size`   | 128MB / 384MB        | database 39.4 MB, cache hit 99.998 %                                                                              | 3.3× / ~10× the database                                                                   |
| db-keycloak | `work_mem` / `maintenance_work_mem`         | 4MB / 32MB           | 0 temp files                                                                                                      | proven sufficient; unchanged                                                               |
| db-keycloak | `max_connections`                           | 120                  | peak 20 / average 3.0                                                                                             | covers Keycloak's ~100 pool + exporter, admin                                              |
| keycloak    | `memory` (the image's heap is ~70 % of it)  | 2560M                | working-set peak 922 MB (36 %), `rss` 32.7 %; heap 729 committed / 400 used of ~1792 MB; GC ≤ 0.07 %              | 2.8× working set; kept for the 5000-account token/refresh burst                            |
| keycloak    | `cpus`                                      | 3.0                  | 2026-09-13: 34.3 s over 216 events in 13 h (159 ms stall)                                                         | burst ceiling; each stall is login latency                                                 |
| backend     | `memory`                                    | 2048M                | working set 1108 MB (2026-07-25); `rss` 55.6 %                                                                    | 1.8× working set; the 2048M → 1792M lever (ADR-0085) stays un-taken                        |
| backend     | `MaxRAMPercentage` / `InitialRAMPercentage` | 57 / 35              | heap 684 committed / 647 used; overhead 438 MB (nonheap 230 + other native 208, 2026-07-25)                       | ceiling 1167 MB = 1.7× committed; 1167 + 438 = 1605 MB = 78 % of limit; initial 717 MB     |
| backend     | `cpus`                                      | 3.0                  | 2026-09-13: 19.2 s over 418 events in 13 h (46 ms stall)                                                          | burst ceiling                                                                              |
| backend     | Hikari `maximum-pool-size` (application-prod.yml) | 100            | active peak 10, total 30, pending 0, timeouts 0, acquire max 0.444 s                                              | ~10× demand; kept for the ADR-0078 live-update burst; re-open only if pending stays 0 through one |
| redis       | `memory`                                    | 512M                 | `rss` peak 28.5 MB (5.6 %)                                                                                        | above `maxmemory` for the RDB/AOF-rewrite fork's copy-on-write pages                       |
| redis       | `maxmemory` (`noeviction`)                  | 384mb                | used peak 7.07 MB (1.8 %), 8191 keys, 0 evictions; linear projection to 5000 accounts ≈ 43 MB                      | ~9× the projection; at the ceiling writes are refused (ADR-0079)                           |
| redis       | `cpus`                                      | 1.0                  | 545 s throttled in 7 days (0.661 % average); use 0.015 cores at peak                                              | burst room for the single-threaded loop plus AOF/RDB threads                               |
| frontend    | `memory`                                    | 1792M                | heap 529 committed (2026-07-25), 493 committed / 472 used (#937); overhead ~406–420 MB                            | at HotSpot's 1792 MB server-class line (ADR-0175); G1 is also set explicitly               |
| frontend    | `MaxRAMPercentage` / `InitialRAMPercentage` | 50 / 35              | as above                                                                                                          | ceiling 896 MB = 1.7× committed; 896 + ~420 = 73 % of limit; initial 627 MB                |
| frontend    | `cpus`                                      | 2.0                  | 1506 s throttled in 7 days (0.725 % average, 66.7 % peak ratio); 5-minute peak 0.18 cores                         | burst ceiling for render + JIT bursts                                                      |
| ingest      | `memory`                                    | 512M                 | working set 337 MB (66 %, 2026-07-25); `rss` 69.9 %                                                               | 77 % worst case (below)                                                                    |
| ingest      | `MaxRAMPercentage` / `InitialRAMPercentage` | 60 / 50              | heap 248 committed / 118 used; overhead 89 MB                                                                     | ceiling 307 MB = 1.2× committed; 307 + 89 = 396 MB = 77 % of limit; Serial GC by choice    |
| ingest      | `cpus`                                      | 1.5                  | 452 s throttled in 7 days (0.245 % average, 76.7 % peak ratio); 5-minute peak 0.10 cores                          | burst room for handshake + JWT + relay                                                     |
| edge        | `memory` / `cpus`                           | 192M / 1.0           | none recorded                                                                                                     | measure before the next change                                                             |
| acme        | `memory` / `cpus`                           | 128M / 0.5           | none recorded                                                                                                     | measure before the next change                                                             |

JVM overhead is `nonheap + other native` and is budgeted absolutely: `ceiling + overhead ≤ ~80 %` of
the limit (rule 3). Every JVM figure above was measured with 96-bit object headers and on the
collector of its time; REQ-OPS-030's pending re-measurement replaces them.

`docker-compose.monitoring.yml` — every service is Go; `GOMEMLIMIT` is 75 % of the limit unless the
row says otherwise, checked against the live heap (`go_memstats_heap_alloc_bytes`). No service here
has a `cpus:` quota, deliberately: all containers together averaged 0.256 cores (3.2 % of the host)
with this stack under half of it, and a throttled exporter or Prometheus gaps the very series an
incident needs. The memory limit plus `oom_score_adj` is the runaway guard.

| Service                    | `memory` | `GOMEMLIMIT`       | Measured figure                                                                                                              | Headroom                                                               |
|----------------------------|----------|--------------------|------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------|
| prometheus                 | 1024M    | 900MiB (88 %)      | live heap 225.1 MiB, resident 336.0 MiB, `rss` 32.8 %                                                                        | 4.0× heap; the 88 % is a recorded exception for 180-day WAL replay     |
| grafana                    | 1024M    | 768MiB             | working-set peak 528.7 MiB (51.6 %); not scraped, so no `go_*`; 192M and 512M both OOM-cycled                                | 1.9× working set                                                       |
| loki                       | 384M     | 288MiB             | live heap 76.7 MiB; anon 261.3 MiB (68.0 %)                                                                                  | 3.8× heap                                                              |
| tempo                      | 1G       | 768MiB             | 7 days to 2026-08-29 (#1705): `rss` peak 665.3 MiB (65.0 %), working set 819.5 MiB; heap sawtooth peak 641.6 MiB             | 1.5× `rss`; re-measure first at an image or trace-volume change        |
| alloy (local stacks only)  | 512M     | 360MiB (budget)    | 21 days to 2026-08-02: `rss` flat 190–217 MB, heap 154 MiB, non-Go overhead ~47 MiB, anon 40.5 %                              | 360 + 47 = 407 MiB ≈ 80 %; on production a host service, same budget   |
| alertmanager               | 48M      | 36MiB              | live heap 7.7 MiB; anon 23.2 MiB (48.3 %)                                                                                    | 4.7× heap                                                              |
| node-exporter              | 32M      | 24MiB              | live heap 2.6 MiB; anon 10.7 MiB (33.4 %)                                                                                    | 9.2× heap; on production a host service with its own drop-in           |
| postgres-exporter-backend  | 32M      | 24MiB              | live heap 3.6 MiB; anon 13.6 MiB (42.4 %)                                                                                    | 6.7× heap                                                              |
| postgres-exporter-keycloak | 32M      | 24MiB              | live heap 3.4 MiB; anon 42.3 %                                                                                               | 7.1× heap                                                              |
| redis-exporter             | 32M      | 24MiB              | no `go_*` (Go collector disabled); anon 12.8 MiB (40.0 %)                                                                    | 2.5× anon                                                              |
| blackbox-exporter          | 64M      | 44MiB              | live heap 9.8 MiB; anon 47.6 % (was 30.5 MiB = 95.3 % of the former 32M, 2026-08-02)                                         | 4.5× heap                                                              |

**Enforced by:** the [sizing ledger](#sizing-ledger) above · the measurement runbook in
[`monitoring/README.md`](../../monitoring/README.md) → "Container memory sizing" · the capacity rule
and its measured revisions in [ADR-0085](../adr/0085-scale-user-sync-and-stack-capacity-for-5000-accounts.md).

**Acceptance**

- Every limit and derived knob has a row in the sizing ledger naming the measured figure and the
  headroom it retains; a changed limit changes its row in the same PR.
- No sizing change is merged whose justification is a percentage of the container limit, a
  round-number bump, or "to be safe", without a measurement.
- The sum of limits is recomputed and recorded in the ledger whenever any limit changes.

### REQ-OPS-021 — Each image is built once per change of its inputs; the release tag and main pushes re-tag the rest

A release fires `release-images.yml` **twice for one commit**: the release PR merges to `main`, then
`release-publish.yml` tags that same merge commit `vX.Y.Z`. Both pushes are wanted — the first
produces `:edge` / `:sha-<short>`, the second the semver tags — and the
`release-images-${github.sha}` concurrency group serialises them so they cannot contend for the same
GHCR packages. Serialised, the two pipelines **add up**: v1.5.53 spent 6:56 on the main run and a
further 11:40 on the tag run to produce bit-equivalent images.

A commit is therefore built **once**. The tag run resolves the `:sha-<short>` index the main-branch
run pushed and applies the semver tags to that digest with `docker buildx imagetools create` — a
registry-side manifest operation that moves no layer, and the same operation `promote.yml` performs
for `:stable` (REQ-OPS-002). The release tags then point at the exact digest that was built, scanned
and signed on `main`, which is a **stronger** guarantee than two independent builds of one source
tree: `:1.5.53` and `:sha-<short>` can no longer be two different images.

**One consequence has to be handled rather than inherited: the baked version string.** Because the
tag run does not build, everything baked into the image is fixed while `main` builds — *before*
`release-publish.yml` creates the tag. `git describe --tags` on the release commit therefore
answers with the PREVIOUS release plus a commit count, and the footer of v1.8.4 read
`v1.8.3-9-gc2f77a5cb` — a correct description of the commit, and the wrong name for the release
sitting on it. Measured 2026-09-15: main build 12:50:47, tag run 12:51:14 with the build skipped.
This workflow's own comment promised "tag pushes resolve to the tag name verbatim", which had
quietly stopped being true; a version string cannot be wrong loudly, and it was reported twice by
the owner before it was chased.

Rebuilding on the tag would fix it and is the wrong trade: it is the entire saving above, and it
gives up the byte-identity guarantee that makes a rollback to `:1.8.4` or `:sha-<short>` land on the
same content. **The release version is knowable without the tag** — `release-prepare.yml` writes it
into the CHANGELOG, on the very commit being built. `.github/scripts/app_version.py` therefore
prefers the newest dated CHANGELOG section *when it carries no tag yet*, and falls back to
`git describe` in every other case, so only the release commit is affected.

Reuse is an optimisation, never a weakening of the supply chain. It applies only when **every** gate
passes, and the doubt case is always a full build:

1. the run is the **push** of a version tag (`workflow_dispatch` always rebuilds — it is the
   documented manual kick for a release whose images are missing or suspect);
2. the derived source tag has the expected `sha-<hex>` shape;
3. all three app images resolve under that tag in GHCR;
4. each resolved index carries **both** `linux/amd64` and `linux/arm64` children;
5. each digest cosign-verifies against this workflow's identity pinned to `refs/heads/main` and
   anchored (`^…@refs/heads/main$`) —
   deliberately narrower than promote.yml's `(heads/main|tags/vX.Y.Z)`, because the only legitimate
   producer of a reuse candidate is the main-branch run of that commit.

The digest is **re-signed** by the tag run even though gate 5 already proved it is signed, so the
invariant "every digest a release tag points at was signed by the run that applied the tag" holds
independently of the gates. Trivy is **not** re-run: the scan is per digest, and that digest was
scanned by the main run under the same SARIF categories. The `basetool-config` and
`basetool-keycloak-spi` bundles are rebuilt on both runs — they are seconds-scale `FROM scratch`
images off the critical path, and leaving them alone keeps the reuse logic confined to the three
app images.

**A `main` push rebuilds only the images whose inputs it changed, and re-tags the others from the
previous `main` build** (CI-07, 2026-09-23, per module since the same day —
[ADR-0210](../adr/0210-a-main-push-that-changes-no-image-input-re-tags-the-previous-build.md) and its
amendment). Most of what lands on `main` changes no byte of most images, and each push used to rebuild
all three on six runners. The `plan` job's main path applies only to a `push` on `refs/heads/main`.

1. `.github/scripts/image_reuse_plan.py` sorts the files changed between `github.event.before` and
   the pushed commit against the inputs derived from `docker/app/Dockerfile`'s `COPY` lines:
   - a module's **own** inputs rebuild that module only: every `COPY` source written with `${MODULE}`
     (today `<module>/src/main`) and `frontend/oss-bundled-components.json` (copied for every module,
     read only by the frontend's `generateOssLicenses`);
   - everything else is **shared** and rebuilds all three: the Gradle wrapper and `gradle/` (version
     catalog, `verification-metadata.xml`), the root build scripts and `gradle.properties`, **all six
     module build scripts** (Gradle configures every project for every build, and the frontend jar
     embeds the Licensee reports of the backend, ingest and keycloak-spi classpaths),
     `logging-support/src/main`, the Dockerfile itself (base-image digests), the root `.dockerignore`,
     `release-images.yml`, `.github/actions/setup-buildx/` and `.github/scripts/app_version.py`.

   A base that is missing, all-zero or not an ancestor of the pushed commit rebuilds all three;
2. a range containing a **release commit** (a new dated CHANGELOG section) rebuilds all three;
3. each image the script would re-tag must, **on its own**, resolve under `:sha-<short>` of the
   **reuse base** — the newest first-parent ancestor from `github.event.before` (at most 20 commits
   back) whose three images all exist, so a skipped, cancelled or failed predecessor run does not force
   a full build; the diff of steps 1–2 is taken against that base; no base within the bound is a full
   build — carry both architectures, cosign-verify against this workflow's identity pinned to
   `refs/heads/main` (the tag path's gates 3–5), and have been **built within the last 7 days**
   (`org.opencontainers.image.created` — the runtime stage's `apk upgrade` makes an image only as
   patched as its build day). An image that fails any of these is built instead; the others stay
   re-tagged.

**A superseded `main`-push run skips entirely** (ADR-0137 amendment, 2026-09-23). The concurrency
group stays per commit, so every push to `main` queues a run; when `plan` starts and the pushed commit
is already a strict ancestor of `origin/main`'s tip, `plan` sets `skip=true` and every later job
(`build`, `scan`, `merge`, `build-config`, `keycloak-spi-jar`, `build-keycloak-spi`) is skipped, the run
green, with the reason in the job summary. Never for a range containing a release commit, a tag push or
`workflow_dispatch`, and never when the tip cannot be read or is not a descendant (force push).
Decided by `image_reuse_plan.py --skip-check`.

`plan` emits the re-tagged modules, their verified digests and a `build` matrix of only the images to
build; `scan` covers the built images; `merge` assembles the built ones, re-tags the others and signs
all three. A failed build cell stops `merge` for **all three**, so a commit's `:sha-<short>` exists for
every image or for none — which the next push's lookup relies on.

**The three images of one `main` tag need not share a build.** A re-tagged image's
`org.opencontainers.image.revision` label, buildx provenance and — for the frontend — version chip name
the commit that **built** it, whose bytes are, by step 1, what the tagging commit would have built.
What reads that, and why it stays correct:

- **Releases are never mixed.** The release commit rebuilds all three (step 2) and the tag run
  re-tags all three from that one commit or builds all three, so a release's images, labels and footer
  all name the release.
- **`promote.yml`'s `sync-testing`** orders `:testing` against `:stable` by the revision label of the
  **`basetool-config`** bundle, not of an app image: the bundle is rebuilt by every run and never
  re-tagged from another commit, so its label is always the commit the tag was published for. (Reading
  `backend`, as it did until this change, could see the same older revision on both sides and move a
  deliberately-ahead testing back.)
- **`deploy.sh`** resolves and cosign-verifies each image's digest on its own and compares nothing
  across images; a re-tagged digest carries the main-branch signature of the run that built it and of
  the run that re-tagged it.
- **The frontend's version chip** (`app_version.py`) may name an earlier commit than the `:edge` /
  `:sha-<short>` tag it is deployed under — only outside releases, i.e. on a testing host fed from a
  `main` build.

The image build carries **no BuildKit layer cache**. `cache-to: type=gha,mode=max` wrote ~5.3 GB per
release across six scopes; with CodeQL, the `ci.yml` Gradle caches and the Trivy DB the repository
sat at 10.5 GB against GitHub's 10 GB per-repo limit, so LRU eviction removed each run's blobs before
the next run could read them. Measured across twelve build jobs, the dependency layer re-ran in
eleven of them while cache export still cost 40–246 s per job — the cost landed exactly where the
benefit was absent. A build cache is semantically transparent, so removing it cannot change the
produced artifact.

**Acceptance**

- [ ] A `plan` job decides build-vs-reuse per image before any build job starts; on a tag push it
  re-tags all three only when all five gates above pass for all three, and every other outcome
  yields a full build (a failed `plan` tags nothing).
- [ ] A re-tagged image's `merge` cell re-tags the digest `plan` **verified** (no second resolution
  between check and use) and signs it; `build` and `scan` run only for the images being built.
- [ ] A `workflow_dispatch` run always builds — on `main` or a release tag on `main`; any other ref
  is refused by `ref-guard` before anything is built (REQ-OPS-015, since 2026-09-22).
- [ ] The BuildKit image the builds boot is pinned **by digest** in
  `.github/actions/setup-buildx/Dockerfile`, a carrier nothing builds, which the composite action
  reads and Dependabot's `docker` ecosystem bumps; the `mirror.gcr.io` fallback pulls the same
  digest (audit item CI-SEC-13).
- [ ] The version baked on the release commit is the release being cut, not the previous tag plus a
  commit count. `app_version.py --selftest` asserts every branch, including that one, and runs in
  `repo-lint.yml`.
- [ ] `release-images.yml` declares no `cache-from` / `cache-to` for the app images. Before any cache
  is reintroduced, `gh api repos/{owner}/{repo}/actions/cache/usage` is checked against the 10 GB
  limit — at the quota, a new cache consumer only degrades every existing one.
- [ ] Trivy runs in its own job, not inside `build`: a failure of the scan action or of the SARIF
  upload must not skip `merge` and cost the release its tags and signature.
- [x] A `main` push rebuilds exactly the images whose own or shared inputs its range changed (all
  three on a shared input or a release commit), and re-tags each other image whose predecessor
  verifies and is at most 7 days old; an image that fails a gate is built. `image_reuse_plan.py
  --selftest` pins the git half per module and runs in `repo-lint.yml`; the plan script was exercised
  against stubbed registry answers for every gate, per image (2026-09-23); `image_reuse_plan.py
  --dry-run 30` replays the decision over recent `main` history (2026-09-23: 36 of 90 module builds
  re-tagged over the last 30 commits, 156 of 300 over the last 100).
- [x] `promote.yml`'s `sync-testing` reads the `basetool-config` bundle's revision label, the one
  label that always names the published commit.
- [x] A `main`-push run whose commit is no longer `main`'s tip at plan time skips every job after
  `plan` and ends green, except for a range with a release commit; `workflow_dispatch` and tag pushes
  never skip. `image_reuse_plan.py --selftest` covers each branch of the skip decision and of the
  reuse-base search; the plan script was exercised against stubbed git/registry answers for both
  (2026-09-23).

**Enforced by:** `.github/workflows/release-images.yml` (`plan`, `build`, `scan`, `merge`) ·
`.github/scripts/image_reuse_plan.py` · `.github/workflows/promote.yml` (`sync-testing`) · **Decision:** [ADR-0137](../adr/0137-one-image-build-per-commit-and-no-buildkit-layer-cache.md),
[ADR-0210](../adr/0210-a-main-push-that-changes-no-image-input-re-tags-the-previous-build.md)

### REQ-OPS-022 — Non-production environments are fed by their own promotion channel

A non-production environment (today: the PVE testing stack) is delivered by the **same
mechanism** as production, over a **separate tag**. `promote-testing.yml` re-tags an
already-built digest to `:testing`; the host runs `deploy.sh --tag testing` on the standard
timer. Nothing reaches such an environment on a `main` merge — the operator decides what gets
tested, the same principle REQ-OPS-002 applies to production.

Two properties are **identical** to the production path and must stay that way:

1. **Provenance.** The cosign identity is the same anchored regexp pinned to
   `release-images.yml@refs/(heads/main|tags/vMAJOR.MINOR.PATCH)` (REQ-OPS-015). An image built off an arbitrary feature
   branch is not promotable to testing either. A rehearsal that accepts weaker provenance is a
   rehearsal of something else.
2. **Lock-step.** The three app images, `basetool-config` and `basetool-keycloak-spi` move
   together with `fail-fast: true`, so compose, provider JAR and images never skew.

Exactly one property differs, deliberately: the `approve` job binds the `testing` environment
**without a required reviewer**. On `production` the reviewer separates "may fire a workflow"
(`actions:write`) from "may change production" — a stolen or over-scoped PAT can start the run
but cannot approve it. A testing environment holds no production data and serves no users, so
that seam protects nothing and only adds friction. The environment reference itself stays,
because it mints one deployment record per promotion — the durable answer to *which version has
been on testing since when*. Adding the reviewer later is a repository setting, not a workflow
change.

**Testing is never behind production — `testing >= stable` is an invariant, not a habit.**
Until 2026-09-15 the two tags were flipped by two workflows nobody was required to run in order,
so a production promotion could move `:stable` past `:testing` and testing silently became the
**older** environment. Nothing failed, and every "try it on testing first" instruction quietly
meant "try an older build". `promote.yml`'s `sync-testing` job therefore carries `:testing`
forward whenever it would otherwise fall behind — and **only** then. A testing tag deliberately
placed ahead of production is left where it is, which is what `promote-testing.yml` is now for:
putting testing ahead, not keeping it level.

**The ordering signal is the source commit, not the image's timestamp.** Each image carries
`org.opencontainers.image.revision`; the job reads it — from the `basetool-config` bundle, whose
label always names the published commit, because an app image's may name an older building commit
(REQ-OPS-021, per-module reuse; the backend image was read until 2026-09-23) — from both `:testing`
and the promoted tag and asks `git merge-base --is-ancestor`. Creation time looks like an equivalent signal and is not:
the `workflow_dispatch` escape hatch rebuilds an **old** version, giving old code a new timestamp,
and a timestamp comparison would then march testing backwards while reporting success.

**On an unanswerable question, testing is left alone.** If either revision is missing, or
testing's commit is not in this repository's history — a squashed or deleted branch build, which
is exactly the shape of a deliberately experimental testing deployment — the job warns and does
not move the tag. The failure direction is chosen: moving on a guess can only be wrong by
destroying an ahead state, which is the one thing this must never do.

A non-production environment usually sits behind a NAT that does not hairpin, so the **public**
Keycloak name resolves to a WAN address its own containers cannot reach. Backend, frontend and
ingest load the OIDC metadata from that name at startup, so they never become healthy and the
health gate rolls the deploy back — a failure that looks like a broken image and is a
name-resolution problem. `IRI_KEYCLOAK_HOST_ALIAS` supplies a Docker `extra_hosts` entry that
points the name at the host's own reverse proxy; its default, `localhost:127.0.0.1`, is a no-op.

> [!note] Corrected 2026-09-22 — on a rootless host this is not a non-production concern
> Under rootless Podman **no** container can reach its own host through the host's public address,
> production included: measured on the production host at the cutover, ports 22, 80 and 443 on its
> public IPv4 and IPv6 all refused from inside a container while `host-gateway` answered. Quadlet
> units cannot interpolate `IRI_KEYCLOAK_HOST_ALIAS` either — the generator bakes in the no-op
> default. So every rootless host, production first, aliases **all four** of its public names to
> `host-gateway` through a drop-in the Ansible role writes from `basetool_host_public_name_aliases`
> (`<svc>.container.d/10-host-alias.conf` for backend, frontend, ingest, grafana and
> blackbox-exporter), and the first entry must equal `IRI_KEYCLOAK_HOST_ALIAS` in `.env` —
> `check-conformance.py`'s `env-reaches-the-units` fails when they disagree
> ([ADR-0196](../adr/0196-a-rootless-host-aliases-its-own-public-names-to-the-container-gateway.md)).
> The sentence this replaces said the default left production untouched, which stopped being true
> the day production became rootless.

Resolving the name is not the same as trusting what answers on it. That metadata fetch runs on a
plain `RestTemplate` and therefore validates against the **JVM default trust store** — the
`backend-trust` / `keycloak-trust` SSL bundles cover the inter-service clients only (the frontend's
WebClients, the backend's Keycloak admin client, the ingest's `RestClient`s — ADR-0204). An
environment whose proxy presents a self-signed certificate fails with `PKIX path building failed`
at exactly the same point. `IRI_TRUSTSTORE_HOST_PATH` mounts a trust store at
`/run/secrets/truststore.p12` and `IRI_EXTRA_JAVA_OPTS` appends the `javax.net.ssl.trustStore*`
switches that select it. Both default to no-ops: the mount resolves to the same file as the
keystore, and with no extra options nothing reads it. Under Quadlet the mount path is baked into the
units — since the per-service internal TLS (REQ-SEC-070) at the CA-only
`/var/iri/secrets/tls/truststore.p12`, so the shared keystore's private key is no longer mounted into
every app through this path — so a rootless host with a privately signed edge gets its truststore from a
second role-written drop-in instead (`basetool_host_jvm_truststore_path` →
`/run/secrets/jvm-truststore.p12`, `20-jvm-truststore.conf`), and `IRI_EXTRA_JAVA_OPTS` names that
path. Production's edge certificate is publicly signed and sets neither.

That trust store **must be built as a copy of the JVM's own anchors plus the environment's
certificate**, not from the keystore. Two reasons, both load-bearing: `-Djavax.net.ssl.trustStore`
*replaces* the default anchors rather than adding to them, so a bare keystore would leave the app
unable to verify any public certificate; and a file holding no private key can carry the throwaway
`changeit` password, which is what keeps the keystore password — which protects a private key —
off the command line where `ps` would show it.

Because a non-production environment runs the **same promoted config bundle** under a different
domain, the public identity `docker-compose.yml` bakes in is a variable with a production default:
`IRI_KEYCLOAK_HOSTNAME`, which becomes Keycloak's `KC_HOSTNAME`. Unset, it resolves to the
production value, so production behaviour is unchanged. `KC_HOSTNAME_STRICT` stays `true` in every
environment — Keycloak keeps rejecting requests under any name other than the configured one.

**That hostname is the single source, and the issuer is derived from it** (ADR-0167): the backend,
frontend and ingest service templates each read
`${IRI_KEYCLOAK_ISSUER_URI:-${IRI_KEYCLOAK_HOSTNAME:-<production>}/realms/iri}`. Until then the two
were independent values that had to be kept in agreement by hand — `.env.example` said so in as many
words, and nothing checked it. The failure when they disagree is the one ADR-0166 measured: Keycloak
reports **healthy** while backend, frontend and ingest each die at start-up on `The Issuer "…" did
not match the requested issuer "…"`, which reads as a backend fault and is a failed deploy.
`IRI_KEYCLOAK_ISSUER_URI` remains as an override for a deployment whose advertised issuer genuinely
differs from `KC_HOSTNAME`; setting it wins over the derivation.

**The monitoring stack derives from the same variable.** Grafana's `generic_oauth` provider has no
discovery option, so `docker-compose.monitoring.yml` names the authorize, token and userinfo URLs
individually; all three now share one `${IRI_KEYCLOAK_HOSTNAME:-…}` expansion. Under Compose both
projects run with the same `--project-directory`, so they read the same `.env`; under Quadlet
`render-env-d.py` renders every service's environment, Grafana's included, from that one `.env`.
Either way one value moves the app stack and Grafana together. A monitoring stack started from
elsewhere falls back to the production default, unchanged.

**The agreement is gated rather than documented.** `scripts/check-keycloak-issuer.py` runs in
`repo-lint.yml` and renders every stack through `docker compose config` — compose's own
interpolation, not a re-implementation of it — then asserts that a full-URL `KC_HOSTNAME` carries
exactly the path `KC_HTTP_RELATIVE_PATH` serves under, that every service's issuer is the one that
Keycloak will advertise, that the services in one stack agree with each other, that the
`application*.yml` fallback defaults still name the deployed issuer, that Grafana's three OIDC
endpoints sit on that issuer and follow an override, and that the Prometheus identity probe targets
name the deployed base with all three required probes present (REQ-OBS-012). Its regression suite,
`scripts/check-keycloak-issuer.test.sh`, breaks each of those in turn and requires the gate to say
so, and runs first so the gate cannot pass vacuously.

`monitoring/prometheus/prometheus.yml` is the one identity surface that **cannot** be derived —
nothing interpolates it — which is why it is compared instead, in both directions. See REQ-OBS-012
for why the missing-probe half is not redundant.

**Acceptance**

- [ ] `promote-testing.yml` is `workflow_dispatch`-only, writes **only** the `:testing` tag, and
  never touches `:stable`.
- [ ] Its concurrency group is distinct from `promote-stable`, so a testing promotion neither
  queues behind nor cancels a production one — and `promote.yml`'s `sync-testing` job joins the
  **testing** group, so the two writers of `:testing` cannot interleave.
- [ ] A production promotion leaves `testing >= stable`: it moves `:testing` when testing's source
  commit is an ancestor of the promoted one, and leaves it untouched when testing is ahead.
- [ ] The comparison reads `org.opencontainers.image.revision`, never the image timestamp.
- [ ] An unreadable or unknown revision leaves `:testing` untouched and emits a warning.
- [ ] It cosign-verifies against the **same** release-images identity regexp as `promote.yml`.
- [ ] The `approve` job declares `environment: testing` on its own single job (not the matrix),
  so one deployment record is minted per run.
- [ ] `IRI_KEYCLOAK_HOSTNAME` and `IRI_KEYCLOAK_ISSUER_URI` unset ⇒ the rendered compose is
  byte-identical to the pre-change production values.
- [ ] `IRI_KEYCLOAK_HOSTNAME` set **alone** ⇒ the issuer of all three apps moves with it, in the
  rendered compose. This is what distinguishes a derived value from two literals that happen to
  agree today, and it is the case `check-keycloak-issuer.py`'s `prod-hostname-override` scenario
  exists for.
- [ ] `IRI_KEYCLOAK_ISSUER_URI` set as well ⇒ it wins over the derivation.
- [ ] A full-URL `KC_HOSTNAME` whose path differs from `KC_HTTP_RELATIVE_PATH` ⇒ CI fails before
  the configuration can be deployed (ADR-0166's measured broken row).
- [ ] Every `application*.yml` fallback default for `KEYCLOAK_ISSUER_URI` names the issuer
  `docker-compose.yml` deploys, so an app started without the variable does not trust a retired one.
- [ ] `IRI_KEYCLOAK_HOSTNAME` unset ⇒ the rendered `docker-compose.monitoring.yml` Grafana OIDC URLs
  are byte-identical to the pre-change values; set ⇒ all three follow it.
- [ ] Every `monitoring/prometheus/prometheus.yml` target under the identity path sits on the
  deployed identity base, and the discovery document, `/auth/health` and `/auth/metrics` are all
  still probed.
- [ ] `IRI_KEYCLOAK_HOST_ALIAS` unset ⇒ the only `extra_hosts` entry is `localhost:127.0.0.1`,
  which resolves to what `localhost` already resolves to (Compose; the generated units carry exactly
  that default).
- [ ] A rootless host's inventory lists every public name the containers dial in
  `basetool_host_public_name_aliases`; the role writes the drop-ins and removes them when the list
  is empty, and `env-reaches-the-units` fails when its first entry and `IRI_KEYCLOAK_HOST_ALIAS`
  disagree.
- [ ] `IRI_EXTRA_JAVA_OPTS` unset ⇒ `JAVA_TOOL_OPTIONS` is character-identical to before, and the
  truststore mount points at the same file as the keystore mount (read, but never consulted).
- [ ] The documented trust store recipe starts from `$JAVA_HOME/lib/security/cacerts`, so selecting
  it does not strip the public anchors; the keystore password appears in no process argument.
- [ ] No production credential, realm export or Discord application is reused by a
  non-production environment (`CLAUDE.md`: never use production credentials in test stacks).

**Enforced by:** `.github/workflows/promote-testing.yml` · `docker-compose.yml`
(`KC_HOSTNAME`, `KEYCLOAK_ISSUER_URI`) · `.env.example` · `ansible/roles/basetool_host/tasks/50-podman.yml`
(alias and truststore drop-ins) · `scripts/check-conformance.py` (`env-reaches-the-units`) ·
**Runbook:** `docs/deployment.md` → *Promoting to testing* · **Decision:** ADR-0196

### REQ-OPS-023 — Build provenance is anchored outside the registry

Every artifact this project publishes carries a **SLSA build provenance attestation in GitHub's
attestation store**, binding the artifact's digest to the workflow, the run and the source commit:
the three app images, the `basetool-config` and `basetool-keycloak-spi` bundles, and the CycloneDX
SBOM files attached to the GitHub Release. This is **in addition to**, never instead of, the
in-registry provenance — the buildx `mode=max` attestation manifest and the cosign keyless
signature — which REQ-OPS-015 continues to gate on.

The reason for a second record of the same fact is *where it is kept*, not *what it says*. The
buildx provenance manifest and the cosign signature layer are ordinary manifests in the GHCR
repository, siblings of the image itself. A credential holding `packages: write` on the
organisation can delete a package version and push a replacement, and the provenance and signature
go with it, because they live in the repository that credential controls. REQ-OPS-015 closes the
case where `:stable` is **moved** to point at a different digest — the host re-resolves and
re-verifies on every tick — but it does not close the case where a package is **rewritten as a
coherent set**, provenance included. The attestation store is reached through `attestations: write`
and the `/repos/{owner}/{repo}/attestations` API, neither of which `packages: write` grants, so it
is a second answer to "who built this digest?" with an independent trust root.

For the release assets the gap was absolute rather than partial: the SBOMs (four at the time,
eight since REQ-OPS-025) carried **no** provenance of any kind. A release asset is a bare file behind a URL, indistinguishable from one
uploaded by anyone who ever held `contents: write` — and an SBOM is exactly the artifact worth
doctoring, because it is what a consumer reads *instead of* unpacking the image.

**The attestation is deliberately not pushed back into the registry** (`push-to-registry: false`).
Doing so would place the independent copy in the mutable location whose mutability motivated it,
and would add a referrer manifest to an index whose composition `plan` and `merge` reason about
explicitly (REQ-OPS-021).

**This does not change the host gate, and is not intended to.** `deploy.sh` still verifies with
cosign and nothing else. Verifying a GitHub attestation requires a GitHub API credential, and the
production host deliberately holds only a **read-only GHCR pull token** (REQ-OPS-001); giving it a
GitHub credential to strengthen a verification would weaken the property that credential-minimalism
buys. The attestation store therefore serves the **auditor and the CI half** of the supply-chain
seam — anyone verifying a published artifact after the fact — while the host half stays cosign,
fail-closed, exactly as REQ-OPS-015 specifies.

**Acceptance**

- [ ] `merge` (per module), `build-config` and `build-keycloak-spi` each attest the **manifest
  digest** they just signed — the subject is a digest, never a tag, so every tag pointing at that
  digest (including a later `:stable` promotion) is covered by one attestation.
- [ ] `release-publish.yml` attests **every** SBOM asset it uploads -- the set REQ-OPS-025 defines,
  not a hardcoded count -- **before** `gh release create/upload`, so no published asset is ever
  downloadable in an unattested window.
- [ ] `push-to-registry` is `false` at every call site.
- [ ] The attestation runs on a reuse run too (REQ-OPS-021), so every digest a release tag points
  at was attested by the run that applied the tag, not only by the run that built it.
- [ ] Both workflows declare `id-token: write` **and** `attestations: write`; `release-publish.yml`
  additionally keeps `contents: write` for the release itself. In `release-images.yml` both are
  declared **per job, on the signing jobs only** (`merge`, `build-config`, `build-keycloak-spi`);
  the jobs that run the project's build (`build`, `keycloak-spi-jar`) hold neither, so no build step
  can mint a certificate under the release identity (audit item CI-SEC-04, 2026-09-22).
- [ ] A published artifact verifies with `gh attestation verify` alone — no cosign, and no copy of
  the signer-identity regexp that `deploy.sh` and `promote.yml` must keep in sync.
- [ ] `deploy.sh` is **unchanged**: the host-side gate remains the cosign verification of
  REQ-OPS-015, and the host acquires no GitHub credential.

**Enforced by:** `.github/workflows/release-images.yml` (`merge`, `build-config`,
`build-keycloak-spi`) · `.github/workflows/release-publish.yml` · **Runbook:**
`.github/SECURITY.md` → *Verifying Releases* · **Decision:** ADR-0145

### REQ-OPS-024 — A promotion is gated on the promoted digest's vulnerability scan

`promote.yml` scans the digest it is about to promote and **fails on a fixed HIGH/CRITICAL
finding** in any of the three app images, on either architecture, before the human-approval gate is
reached. The scan uses settings **identical** to the advisory scan in `release-images.yml`
(`severity: HIGH,CRITICAL`, `ignore-unfixed: true`), so the Security tab and the gate can never
disagree about what counts.

The same finding is therefore advisory at build time and blocking at promotion, and the split is
deliberate. A build is speculative: most images produced are never promoted, and the cost of
refusing one is that a fix cannot be shipped at all — which is why `exit-code: 0` and the move of
Trivy out of the `build` job stand unchanged (REQ-OPS-021, ADR-0137). A promotion is the single
moment a specific digest is chosen to run in production, it already requires a human, and refusing
it costs only the promotion. Until this requirement existed the answer to "what does a Trivy finding
stop?" was "nothing, at any stage", while a vulnerable Java *library* blocked a merge outright at
`failBuildOnCVSS = 7.0` — an ordering no threat model produces on purpose.

**The first run of this gate refused a promotion, and the fix was not a digest bump.** On
2026-09-04 all six scan legs failed on **8 fixed HIGH** findings, every one of them an Alpine
package in the base image — `libcrypto3`/`libssl3`, `libexpat`, `p11-kit` — and none an application
dependency. The pinned base was five weeks stale, so the obvious answer was to bump the digest that
`FROM` names. Measured, that answer is **wrong**: the newest upstream `eclipse-temurin:25-jre-alpine`
still carried 5 of the 8. Eclipse Temurin rebuilds on its own cadence, so between two of its
rebuilds the image lags Alpine's security updates, and **a faster Dependabot cannot close that
window — the newest tag is the thing that lags.**

So the three runtime stages now run `apk upgrade --no-cache`, folded into the `RUN` that creates the
non-root user rather than added as a layer of its own. Measured against this gate's own threshold:
the pinned digest **8**, the newest upstream digest **5**, the newest digest with the upgrade **0**.

It does not reintroduce drift. The base pins its own `/etc/apk/repositories` to `v3.24`, so the
upgrade stays **within** that Alpine release and never walks onto the next one; the digest still
fixes the starting point and the upgrade patches it. This is also why the `apk` note in those
Dockerfiles still holds: nothing is *installed*, and the HEALTHCHECK still uses BusyBox `wget`.

**Break-glass.** The `allow_vulnerable` workflow input flips the scan back to non-blocking. The scan
still runs and still prints every finding; only the failure is withheld. The bypass is written into
the scan job's step summary and raised as a `::warning::` in the approval record, so it cannot be
exercised invisibly, and the reason belongs in the deployment notes.

**Not scanned:** `basetool-config` and `basetool-keycloak-spi`. Both are `FROM scratch` artifacts
with no base image and no OS package database; the provider JAR's Java dependencies are scanned by
OWASP Dependency-Check at `failBuildOnCVSS = 7.0`. That scan is a signal, not a merge gate: it is
not a required check (corrected 2026-09-22 — this said "gated more strictly", as ADR-0155 did).

**Not gated:** `promote-testing.yml`. A vulnerable build must stay exercisable in an environment
that is not production — gating it would make the bypass routine and wear it out.

**Acceptance**

- [ ] The three app images' runtime stages carry `apk upgrade --no-cache`, because pinning a
  current base digest is measurably not enough to pass this gate.
- [ ] The `scan` job runs on `needs: validate-inputs` and `approve` runs on `needs: [validate-inputs, scan]`,
  so a reviewer is only asked about an image that passed — or about one whose bypass the approval
  step states.
- [ ] Severity and `ignore-unfixed` match `release-images.yml` exactly; a change to one is a change
  to both.
- [ ] `TRIVY_PLATFORM` is pinned to the matrix platform on both legs — the pushed artifact is an OCI
  index (image + provenance + SBOM manifests) and Trivy's default child selection is hardcoded to
  `linux/amd64`, which fails outright on the arm64 leg.
- [ ] The scan job authenticates to GHCR before scanning: the packages are private, and the Trivy
  vulnerability database is itself pulled from GHCR, where an anonymous rate limit would now block a
  promotion rather than lose an advisory scan.
- [ ] `allow_vulnerable` defaults to `false`, and setting it produces a visible warning in the
  approval record and a bypass note in the step summary.
- [ ] `promote-testing.yml` is unchanged and remains ungated.

**Enforced by:** `.github/workflows/promote.yml` (`scan`) · `.github/workflows/release-images.yml`
(the advisory twin, whose settings this gate mirrors) · **Runbook:** `docs/deployment.md` →
*Promoting to production* · **Decision:** ADR-0155

### REQ-OPS-025 — Every shipped module publishes a current SBOM

Each Gradle module whose output reaches a consumer carries a CycloneDX SBOM that is **generated
from the shipped runtime classpath, committed, regenerated at release time, attested and attached
to the GitHub Release**. Today that is `backend`, `frontend`, `ingest` and `keycloak-spi` — four
modules, eight files.

The set is derived from `settings.gradle.kts` by `.github/scripts/check_sbom_coverage.py` and
asserted on every pull request, rather than being four hand-maintained lists in four files that
happen to agree. **A module that is not wired fails the check until somebody decides which it is**;
one that genuinely ships nothing is entered in the script's `NOT_SHIPPED` map with the reason.
`test-support` is the only such entry: a test-only helper library that no image carries, whose BOM
would list JUnit and Mockito as components of the delivered product. A module that ships **only
inside** other modules' artifacts is entered in a second map, `SHIPPED_INSIDE`, with those carriers:
it appears as a component of each carrier's BOM, and the script asserts that every carrier still
declares it as `implementation(project(...))`. `logging-support` is the only such entry (ADR-0205).

Applying the plugin is each module's own decision; **what the BOM contains is not**. Since
2026-09-23 (audit item BLD-SIMP-06) the output path, schema version, serial number, licence text,
build-system reference and the `^runtimeClasspath$` restriction are set once, in the root
`build.gradle.kts` (`subprojects { plugins.withId("org.cyclonedx.bom") }`), for every module that
applies it — four copies of the same block had been maintained by hand until then. Verified at the
move: the component lists of all four BOMs were identical before and after.

**Always a fresh generation, and checked against the classpath (2026-09-23).** cyclonedx-gradle
3.4.1 declares `cyclonedxDirectBom` cacheable, and the only input it gives the dependency graph is
the set of resolved artifact **files**. A project dependency contributes no file there, so adding
`implementation(project(":logging-support"))` to the three applications changed no input: the
task reported `UP-TO-DATE` — or came `FROM-CACHE` out of a build cache, which the release workflow
restores — and the BOM kept its old component list. Reproduced on `:ingest` in both directions
(dependency removed, the BOM still listed it; added back, the BOM still did not) until
`--rerun-tasks`. Two measures close it:

- both SBOM tasks are **untracked** (`doNotTrackState` in the root `build.gradle.kts`), so they
  execute on every invocation and never read or write the build cache; `release-prepare.yml`
  additionally passes `--no-build-cache`, so a release stays fresh even if the build script
  regresses;
- every `cyclonedxBom` is finalized by **`verifyCyclonedxBom`**, which fails unless the written BOM
  lists **exactly** the components of the module's resolved `runtimeClasspath` — every external
  module at its resolved version and every project dependency — in both directions. It runs in
  `release-prepare.yml` and, on every pull request, in `ci.yml` (with the configuration cache), so
  a plugin upgrade that stops seeing a component fails the PR that brings it rather than the next
  release.

**Why an assertion and not a habit.** By v1.7.3 the set had drifted in both directions available to
it, and neither drift failed anything:

- **`ingest`** had the plugin, the configuration and a committed BOM — and no release workflow named
  it. Its file was last written on **2026-07-11** and reached v1.7.3 with **126 of its 180
  components at the wrong version**. It was attached to no Release at any point. A stale SBOM is
  worse than an absent one: it is what a scanner reads *instead of* the image, so it answers
  „does this release carry CVE-X?“ with two-month-old evidence, confidently.
- **`keycloak-spi`** had no SBOM at all, while `promote.yml` pushes its provider-JAR bundle into the
  production Keycloak in lock-step with the app images — an artifact under REQ-OPS-023's provenance
  umbrella with nothing describing its contents.

`keycloak-spi`'s BOM lists **zero components**, and that is the correct answer rather than a broken
generation: every Keycloak SPI dependency is `compileOnly` because the runtime provides it, so the
JAR bundles no third-party code. It is published because „nothing bundled“ is a fact a consumer
wants stated, and because the file turns into a tripwire the day an `implementation` dependency
appears.

**Not covered, deliberately.** `keycloak-theme` ships inside the `basetool-config` bundle and
carries exactly one third-party component — the vendored Lato font (SIL OFL) — but no build system
resolves it, so there is nothing to generate and a hand-written BOM would be a hand-maintained one.
Recorded here rather than left to look like an oversight.

**Acceptance**

- [ ] Every module in `settings.gradle.kts` either applies `libs.plugins.cyclonedx.bom` and writes
  `docs/<module>-bom.{json,xml}`, or appears in `NOT_SHIPPED` with a reason.
- [ ] Each shipped module's `cyclonedxDirectBom` restricts `includeConfigs` to `^runtimeClasspath$`,
  so the BOM describes what ships and not the build tooling.
- [ ] `release-prepare.yml` regenerates every shipped module's BOM and stages every
  `<module>/docs` path — a module missing from either list ages in place unnoticed.
- [ ] `release-publish.yml` lists every BOM file **twice**: as an attestation subject and as a
  release asset. Attested but unpublished is useless; published but unattested is the gap
  REQ-OPS-023 closed.
- [ ] `check_sbom_coverage.py` runs on every pull request via `repo-lint.yml` and fails on any of
  the above.
- [ ] `cyclonedxDirectBom` and `cyclonedxBom` are untracked, and `release-prepare.yml` regenerates
  with `--no-build-cache`: a release SBOM is never `UP-TO-DATE` or `FROM-CACHE`.
- [ ] `verifyCyclonedxBom` finalizes every `cyclonedxBom` and fails when the BOM's components differ
  from the resolved `runtimeClasspath` in either direction; `ci.yml` runs it for all four modules on
  every pull request, and `check_sbom_coverage.py` fails if the untracking, the finalizer or the
  `--no-build-cache` flag is removed.

**Enforced by:** `.github/scripts/check_sbom_coverage.py` · `.github/workflows/repo-lint.yml`
(`sbom-coverage`) · `verifyCyclonedxBom` (root `build.gradle.kts`) · `.github/workflows/ci.yml` ·
`.github/workflows/release-prepare.yml` · `.github/workflows/release-publish.yml`
· **Related:** REQ-OPS-023 (their provenance), REQ-OPS-024 (what is scanned), REQ-OPS-029 (the
release notes that have to name this same set)

### REQ-OPS-026 — A renewed certificate is not delivered until the edge can open it

The ACME client and the edge are separate containers by design (ADR-0162), which means the renewal
only reaches the internet once a **handover** succeeds: `acme` writes into the shared `edge-certs`
volume, and `edge` opens what it finds there as uid 101. The issuance is the easy half. The handover
is the half that fails silently, because lego reports success either way and the edge keeps serving
the certificate it loaded at startup.

Three properties make the handover correct, and each of them has failed in production:

- **Every host the edge reads a certificate for is published.** lego issues **one** multi-SAN
  certificate for its whole `-d` list, so the publishing step is driven by the host list, never by
  the certificate files it produced. Driving it by files writes exactly one directory and leaves
  every other vhost on whatever seeded it.
- **The files are readable by the edge's uid.** Mode is set before ownership is handed over
  (REQ-OPS-014: `cap_drop: [ALL]` removes `FOWNER`), and the directories stay owned by the writing
  process so it can still replace the files on the next pass.
- **A certificate is replaced atomically.** The edge opens these files on every reload; a
  half-written one is a container that does not start, not a retry.
- **The running edge is made to load it.** nginx reads its certificates at startup, so a new file in
  the volume changes nothing on its own. `reconcile_edge` fingerprints them every tick and
  force-recreates the edge when they move — and it has to read them in a way the **deploy user**
  can. The volume's host path is not readable to that user — under `/var/lib/docker`
  (`0710 root:root`) on a Docker host, inside the service user's own container store under rootless
  Podman — so the fingerprint is taken through the edge (`exec` into it), which already mounts them
  read-only.
- **The volumes are never pruned.** `edge-certs` and `edge-acme-state` exist in no image and are
  "dangling" to Podman whenever the stack is down; `podman volume prune` has no anonymous-only mode,
  so the weekly cleanup skips volume pruning on Podman altogether (ADR-0194).

The certificates the cutover seeds from the previous proxy are valid for weeks, which is exactly why
a broken handover is invisible: nothing is observably wrong until they expire.

**Acceptance**

- [ ] The set of hosts `acme` publishes equals the set of hosts the edge names in an
  `ssl_certificate` directive — asserted statically, not by inspection.
- [ ] The publishing step runs twice in a row against a volume that already holds certificates, under
  the container's real capability set, and succeeds both times.
- [ ] Every published file can be opened by the edge's uid, verified in the edge's own image.
- [ ] No temporary file is left behind by a publishing pass.
- [ ] A certificate that changed on disk is loaded by the running edge without manual action;
  one that did not change recreates nothing, and a fingerprint read that fails recreates nothing
  either.

**Enforced by:** `scripts/check-acme-publish.sh` (runs `docker/acme/publish-loop.sh` — the file the
`acme` service and unit actually execute, not a copy of it) · `scripts/check-edge-nginx.sh` (host-list agreement) ·
`scripts/container-cleanup.sh` (no volume prune on Podman) ·
`scripts/deploy.test.sh` (`scenario_edge_reloads_a_renewed_certificate`) ·
`.github/workflows/repo-lint.yml` · **Related:** REQ-OPS-014 (the capability baseline that shapes
it), ADR-0162 (why the two containers are separate)

### REQ-OPS-027 — A label-gated check decides on its gate, never on event ordering

A CI check gated on a pull-request **label** must reach the same verdict no matter in which order
GitHub delivers the events that produced it. Concretely, for the E2E suite: if a PR carries the
`e2e` label, the suite **executes** — whether the label was applied at creation alongside four
others, added an hour later, or already present when the branch was pushed.

The hazard is structural rather than a matter of care. GitHub evaluates `concurrency.group` when a
run is **created** and `jobs.<id>.if` only afterwards, so **a run that will skip every job still
claims the group, and under `cancel-in-progress` cancels the incumbent before skipping.** A single
shared group therefore lets the gate be overruled by arrival order: `gh pr create` with several
`--label` flags fires `opened` plus one `labeled` event per label inside a second, and whichever
label lands last decides. Twice — PR #1537 and PR #1871 — that was not `e2e`, and the suite never
executed while the cancelled siblings rendered as `fail` in `gh pr checks`.

Both halves of that outcome are failures, and the second is not cosmetic:

- **A silently skipped suite is indistinguishable from a passing one.** The `E2E flows` jobs are
  not required checks, so the PR merges with no end-to-end coverage and nothing says so.
- **A cancelled run reads as a failed one.** The board goes red for a reason that is not in the
  diff, which is where the diagnosis time goes.

The remedy is that the concurrency group is derived from **the gate's own verdict**: runs the gate
admits share one group, where `cancel-in-progress` legitimately supersedes a stale suite; runs it
rejects are isolated per run and neither cancel nor are cancelled. Because GitHub cannot share one
expression between the two positions, the gate is duplicated into the group verbatim — and a
duplicate that nothing checks is a duplicate that drifts, so the agreement is itself gated. A
selector *looser* than its gate is the specific regression to prevent: it re-admits a skipping run
to the shared group and restores the defect exactly.

**Acceptance**

- [ ] A `gh pr create` applying several labels including `e2e` in one invocation yields **exactly
  one** E2E run that executes, whatever order the events arrive in. Every run whose gate is false
  reports `skipped` and **none of them is cancelled**; a run that is cancelled must be one whose
  gate was true, superseded by a newer qualifying run.
- [ ] Applying `e2e` to an already-open PR starts the suite without waiting for a further push.
- [ ] Toggling a label other than `e2e` on an `e2e`-labelled PR starts **no** suite.
- [ ] A further push to an `e2e`-labelled PR supersedes the in-flight suite rather than running a
  second one beside it.
- [ ] `concurrency.group` contains `jobs.e2e.if` verbatim, and CI fails when it stops doing so; the
  checker self-tests against a known-drifted workflow first, so it cannot pass vacuously.
- [ ] The `build-stack` job, which builds the E2E images once per run for all matrix cells (since
  2026-09-22), carries the same gate expression, so a run whose gate is false builds nothing.

**Enforced by:** `.github/workflows/e2e.yml` (`concurrency.group`, `jobs.e2e.if`,
`jobs.build-stack.if`) · `.github/scripts/check_e2e_gate_mirror.py` ·
`.github/workflows/repo-lint.yml` (`Repository gates` → `e2e-gate-mirror / …`) · **Decision:** [ADR-0169](../adr/0169-the-e2e-concurrency-group-is-keyed-on-the-gates-own-verdict.md)

## Out of scope

- The deploy script (`deploy.sh`), the other operational scripts and their `iri-*` systemd units
  are **not** delivered via the bundle (self-update hazard). They are installed and updated by the
  Ansible role (`25-scripts.yml`, `27-observability.yml`) as a deliberate, operator-driven host
  change (ADR-0188) — never by a promotion. The Quadlet units of the stack itself, by contrast,
  **are** bundle payload (REQ-OPS-004). Bootstrap, token rotation, keystore rotation and the edge
  mechanics live in `docs/deployment.md` and `ansible/README.md`.
- Application-level configuration delivered as environment variables in `.env` is host-only and
  out of scope here (it is never bundled). The list of env keys lives in `README.md`.

### REQ-OPS-028 — Every JVM container names its garbage collector

A container that passes `-XX:+UseContainerSupport` without a collector flag does not choose its
collector; HotSpot's ergonomics do. G1 is selected only on a *server-class machine* — at least
**2 CPUs and at least 1792 MB** — and under container support that memory figure is the **cgroup
limit**, not the host's. Below either bound it falls back to SerialGC silently: no log line, no
warning, and nothing in any metric that names the collector.

That had been true of `frontend` (1280M) and `ingest` (512M) for the entire life of the limit
scheme, while `backend` (2048M) ran G1 only by the accident of its limit. `keycloak` was the only
module that ever said so, via `-XX:+UseG1GC` in its vendor image.

**Every JVM service therefore names its collector in `JAVA_TOOL_OPTIONS`**, and omitting it is a
defect whether or not ergonomics currently picks the intended one:

|  Service   | Limit |                                                               Collector                                                                |
|------------|-------|----------------------------------------------------------------------------------------------------------------------------------------|
| `frontend` | 1792M | `-XX:+UseG1GC`                                                                                                                         |
| `backend`  | 2048M | `-XX:+UseG1GC`                                                                                                                         |
| `ingest`   | 512M  | `-XX:+UseSerialGC` — deliberate; a relay that is idle between bursts does not benefit from G1, and Serial's native overhead is smaller |

The REQ-OPS-020 sizing rule gains a clause with this: **a memory-limit change that
crosses 1792 MB changes the collector**, with a different heap layout, different native overhead and
different pause behaviour. It must be made deliberately, not discovered afterwards — which is the
whole reason the flag is mandatory rather than advisory.

**Acceptance**

- [x] `frontend`, `backend` and `ingest` each pass an explicit collector flag.
  **Corrected 2026-09-15:** this box was ticked when the requirement was written, and for the
  `backend` it was wrong. `e401742db` wrote the flag onto `frontend` and `ingest` only, so the
  busiest JVM stayed on ergonomics — which picks G1 at its 2048M limit and would have picked Serial
  the moment the limit dropped below 1792M, i.e. exactly the silent switch this requirement exists to
  prevent. Found while evaluating JDK 27 (whose JEP 523 would have made the ergonomics half moot) and
  closed in the same change as REQ-OPS-030.
- [x] The frontend's limit is at or above 1792M, so ergonomics and the explicit flag agree rather
  than contradict each other.
- [x] The sum of limits over all prod-profile services stays under ADR-0085's ~14 GB review trigger
  (9792 MiB app + 4368 MiB monitoring = 14 160 MiB).
- [x] The running collector is verifiable without reading a flag: `jvm_gc_pause_seconds_count`
  carries `gc="G1 Young Generation"` for G1 and `gc="Copy"` + `gc="MarkSweepCompact"` for Serial.

**Code:** `docker-compose.yml` (each service's `JAVA_TOOL_OPTIONS`) · the REQ-OPS-020
[sizing ledger](#sizing-ledger) · **Decision:**
[ADR-0175](../adr/0175-jvm-garbage-collectors-are-set-explicitly.md)

### REQ-OPS-029 — A release announces every artifact it publishes

The GitHub Release body is **generated**, not written: `.github/scripts/extract_release_notes.py`
slices the dated CHANGELOG section and appends a footer naming the container images to pull and the
SBOMs to audit. That footer is the only description of the release most readers ever see, so an
artifact missing from it is not a documentation gap — it is, to every consumer, an artifact the
release does not have.

**The footer's two lists are therefore derived from the pipeline and asserted against it**, not kept
by hand beside it:

- `SERVICE_IMAGES` must equal the `module:` build matrix in `release-images.yml` — the modules whose
  image is actually built, scanned, signed and pushed.
- `SBOM_MODULES` must equal the shipped-module set of REQ-OPS-025 — the modules whose BOM is
  attached and committed under `<module>/docs/`.

Both are checked in **both directions**. A list that omits what the release ships understates it; a
list that names something the release does not build advertises a tag nobody can pull, which is the
worse failure because it looks like a broken registry rather than a stale file.

**Why this is its own requirement.** REQ-OPS-025 made the *set of SBOMs* an assertion across four
places, and the same drift then happened one step further downstream in the fifth. `ingest` was in
the build matrix, the scan matrix, the signing matrix and the SBOM asset list, and absent from the
notes — so **v1.8.1, v1.8.2 and v1.8.3 each announced two of the three images they shipped**, and
pointed readers at two of their four SBOM directories. Nothing failed; the release simply
understated itself three times. The lesson REQ-OPS-025 drew — that a hand-kept list beside a
generated one is a silent-drift machine — did not stop at the artifacts themselves.

**Acceptance**

- [ ] `extract_release_notes.py` builds the image and SBOM lists from `SERVICE_IMAGES` /
  `SBOM_MODULES` rather than from literal lines, so the two sections cannot disagree with each other.
- [ ] `check_sbom_coverage.py` reads `release-images.yml`'s build matrix and fails when it and
  `SERVICE_IMAGES` differ in either direction.
- [ ] The same check fails when `SBOM_MODULES` and the REQ-OPS-025 shipped set differ in either
  direction.
- [ ] Both run on every pull request through `repo-lint.yml` (`sbom-coverage`).

**Enforced by:** `.github/scripts/extract_release_notes.py` ·
`.github/scripts/check_sbom_coverage.py` · `.github/workflows/repo-lint.yml` (`sbom-coverage`) ·
**Related:** REQ-OPS-025 (the set it mirrors), REQ-OPS-023 (provenance for those same assets)

### REQ-OPS-030 — The object layout is set once and matched by the baked startup cache

All three application JVMs run **`-XX:+UseCompactObjectHeaders`** (64-bit object headers instead of
96-bit), a product option since JDK 25 (JEP 519) and the default from JDK 27 (JEP 534). It is taken
on the LTS the stack already runs rather than waited for, because the stack is memory-constrained:
14 160 MiB of declared limits against a 15.24 GiB host, inside ADR-0085's ~14 GB review trigger.

**The flag is set in two places and both are mandatory:**

|                               Place                               |                               Why                                |
|-------------------------------------------------------------------|------------------------------------------------------------------|
| `docker-compose.yml` / `quadlet/env.d` → each `JAVA_TOOL_OPTIONS` | The runtime layout; beside the collector flag of REQ-OPS-028     |
| `docker/app/Dockerfile` → `layout=` in the AOT-cache training run | The image build's training run, which cannot see `JAVA_TOOL_OPTIONS` |

Each image carries a startup cache created during its build — since 2026-09-23 the Java 25 **AOT
cache** `/app/app.aot` (ADR-0209), before that a dynamic AppCDS archive `/app/application.jsa`. The
cache records the object layout it was created with and the JVM validates it at startup, so the two
settings must agree. They cannot be kept in step by accident: the training run happens during the
**image build**, where `JAVA_TOOL_OPTIONS` is not set. A JVM started with the other layout prints
`Unable to use AOT cache. The AOT cache's UseCompactObjectHeaders setting (enabled) does not equal
the current UseCompactObjectHeaders setting (disabled)` and **starts normally without the cache**
(the AppCDS wording was `Unable to use shared archive file` / `Loading dynamic archive failed`).
Setting the flag on one side only therefore costs the startup saving — measured on the three images
in ADR-0209 — with nothing failing. The check rules in both directions.

**Both halves of that failure are now detected** (IMG-CI-13, 2026-09-23):

- **At build time.** The training run must complete the context refresh, the cache must be written,
  and a second start under `-XX:AOTMode=on` with the image's own layout must accept it — otherwise
  the image build fails. Until 2026-09-23 the AppCDS run was best-effort (`|| true`, an entrypoint
  that only tested whether the archive *existed*), and the backend and frontend training runs had in
  fact been dying at the missing keystore for as long as that code existed, unnoticed.
- **At run time.** A deployment that starts with a different layout — including the documented
  rollback `IRI_EXTRA_JAVA_OPTS=-XX:-UseCompactObjectHeaders` — writes the lines above to its
  `<svc>-stdout` stream, and the Loki rule **`JvmStartupCacheRejected`** (warning) fires on them.
  `scripts/check-loki-rule-signatures.py` holds the rule to the verbatim lines.

**The heap saving does not license a smaller budget until it is measured.** Every JVM figure in the
REQ-OPS-020 [sizing ledger](#sizing-ledger) was measured with 96-bit headers and is the *before*
side of this change. Limits and `MaxRAMPercentage` values stay exactly as REQ-OPS-020 and
ADR-0175 left them until a post-deploy re-measurement replaces the table — the same one-variable-at-
a-time rule the collector change had to learn (ADR-0175: the previous table compared figures taken
on two different collectors).

**Acceptance**

- [x] `backend`, `frontend` and `ingest` each pass `-XX:+UseCompactObjectHeaders` in
  `JAVA_TOOL_OPTIONS`.
- [x] The image's training run passes the same flag; the table above names both places.
  Amended 2026-09-25 (ADR-0214): this item used to require a comment on each side naming the other.
- [x] The image build fails when the training run does not complete the context refresh, when no
  cache is written, or when a start under `-XX:AOTMode=on` with the image's layout refuses the cache
  (verified 2026-09-23 with two deliberately broken ingest builds — a verifying start under the
  other layout, and a training run without its JWK-set stub — each failed with its `[AOT] FAILED`
  message).
- [x] The training run succeeds under the release builder (a `docker-container` BuildKit), not only
  under the local default one: every `OTEL_*` variable BuildKit injects into the `RUN` step is unset
  before the JVM starts (ADR-0209 Amendment 1; verified 2026-09-23 by building the ingest image with
  such a builder — `origin/main`'s Dockerfile fails at `otlpGrpcSpanExporter`, the fixed one passes).
- [x] The cache holds no machine code, so an image trained on one CPU starts on any other: training
  runs with `-XX:-AOTAdapterCaching -XX:-AOTStubCaching`, and the image build fails unless the
  verifying start reports `AOT Code Cache is empty` (ADR-0209 Amendment 1; verified 2026-09-23 — the
  image from `origin/main` loaded 723 code entries, the fixed one none, and a build with adapter
  caching switched back on failed at that check).
- [x] `JvmStartupCacheRejected` fires on the JVM's rejection lines and stays silent on an accepted
  start (`check-loki-rule-signatures.py`, run in `repo-lint.yml`).
- [x] No memory limit and no `MaxRAMPercentage` changed in the same unit of work.
- [x] No change to the memory monitoring is required: `jvm_memory_*` and the container working-set
  alert are the measurement instrument, and the 90 % `ContainerMemoryHigh` line can only move
  further away.
- [ ] Re-measured on production after a full week under the new layout, with the snapshot queries in
  `monitoring/README.md`, and the new figures written into the REQ-OPS-020 sizing ledger. Freed
  headroom may be spent only after that.

**Code:** `docker-compose.yml` and `quadlet/env.d/*.env.tmpl` (each service's `JAVA_TOOL_OPTIONS`) ·
`docker/app/Dockerfile` (the training `RUN`) · `monitoring/loki/rules/fake/basetool-log-alerts.yml`
(`JvmStartupCacheRejected`) · **Decision:**
[ADR-0180](../adr/0180-compact-object-headers-on-java-25.md),
[ADR-0209](../adr/0209-the-images-ship-a-java-aot-cache-trained-eagerly-and-verified-at-build.md) ·
**Related:** REQ-OPS-028 (the other JVM flag, same file, same failure class), REQ-OPS-020 (the
measured limits this must not pre-empt)

### REQ-OPS-031 — The image build leaves nothing root-owned behind, and the image starts unmounted

The startup-cache training run of REQ-OPS-030 is a **real application start** — since 2026-09-23 two
of them, the training run and the start under `-XX:AOTMode=on` that verifies its cache — so Logback
builds every
appender `logback-spring.xml` declares and creates `logs/<svc>.log` and `logs/<svc>-error.log`
relative to `WORKDIR /app`. It runs *before* `USER 10001:10001`, and a `USER` switch does not change
an existing file's owner — so without cleanup those files ship inside the image owned by `root`.

**Every artefact the training run writes is removed in the same `RUN` layer.** The same layer is the
whole requirement: a later `RUN rm` only stacks a whiteout on top and still ships the bytes
underneath, so "cleaned up afterwards" would leave the payload in the published image and merely
hide it. `/app/logs` itself must survive, owned by `10001:10001` — it is the mount point the compose
templates bind `/var/iri/<svc>/log` over. The same `RUN` also removes the empty, root-owned
`/tmp/tomcat.*` and `/tmp/tomcat-docbase.*` working directories each start leaves behind (found
2026-09-23; every image since the training run existed carried two of them).

**The property this protects is that a published image starts with nothing mounted over it.** The
baked files broke it, in a way that reads as an application defect: `/app/logs` *is* writable by the
app user, so creating a new file there succeeds, but appending to a root-owned one does not. Logback
escalates the failed `openFile(logs/<svc>.log,true)` to `Logback configuration error detected` and
the container exits 1 — before any application code runs, and therefore before any of the
configuration errors an operator running the image bare would actually be trying to read.

> [!note] Production never saw this, and that is the point
> `docker-compose.yml` bind-mounts `/var/iri/<svc>/log` over `/app/logs` on all three services, so
> the baked files were hidden on every deployed start. The defect was **latent for the life of the
> Alpine images**: the artefact was broken while the deployment was not, and no probe, alert or log
> line could have said so. A mount may supply data; it may never be the thing that makes an image
> work.

**The clean-up happens after training, not instead of it.** Suppressing the files by pointing the
training run at a console-only logging config was considered and rejected: it would keep
`AsyncAppender`, `RollingFileAppender`, `SizeAndTimeBasedRollingPolicy` and the module's
`PiiMaskingPatternLayout` out of the archive although every real startup loads them (measured on the
`ingest` image, 2026-09-16: 56 classes and 278 KB less), and a logging config that fails to parse
guts the entire training run — 14 635 classes down to 3 898 — while still producing a `.jsa`, which
the `ENTRYPOINT`'s `if [ -f ... ]` fallback cannot detect, exactly the silent-degradation shape
REQ-OPS-030 exists to prevent. A bare `-Dlogging.config=` suppresses nothing at all: Spring Boot
reads an empty value as *no explicit config* and falls back to the classpath `logback-spring.xml`.

**Acceptance**

- [x] `backend`, `frontend` and `ingest` clear `/app/logs` inside the training `RUN` and recreate the
  directory owned by `10001:10001`.
- [x] `find /app -user 0` returns nothing in each published image. This is the check, rather than
  "the log directory is empty": it catches the whole class, and it was what showed the log files
  were the only instance of it.
- [x] `docker run` on the image with **no** `/app/logs` mount gets past Logback — it fails on missing
  configuration, not on `(Permission denied)`.
- [x] The training run keeps the full logging configuration, so the REQ-OPS-030 cache stays
  representative: the appender and masking classes every real startup loads are in it. (The other
  training flags changed on 2026-09-23 — an eager refresh with per-module stubs instead of
  `lazy-initialization`, ADR-0209.)
- [ ] The `find /app -user 0` assertion runs in `release-images.yml` against the built image, so a
  future build-stage side effect fails the build instead of waiting to be noticed. Not built — today
  the guarantee rests on `docker/app/Dockerfile` and this requirement.

**Code:** `docker/app/Dockerfile` (the training `RUN`; one file for all three images since
2026-09-23) · **Related:** REQ-OPS-030 (the training run this cleans up
after, and the same silent-degradation failure class), REQ-OPS-014 (the runtime posture the fixed
UID/GID serves)

### REQ-OPS-032 — The host applies security updates unattended, and says when it has not

The production host installs **security advisories** without a person, daily, and reports whether it
did (OPS-SEC-01, 2026-09-22). Until then nothing on the Rocky host patched it: the retired Ubuntu
host had unattended-upgrades and the bootstrap role had no successor, so the kernel, openssh,
haproxy, glibc and the host-native `node_exporter` and `alloy` were fixed only when somebody ran
`dnf upgrade` by hand.

- **`dnf-automatic`, `upgrade_type = security`, `apply_updates = yes`, `reboot = never`**, configured
  as a whole file by the bootstrap role (`tasks/45-updates.yml`). A feature update is never applied
  unattended.
- **The container runtime is excluded** — `podman`, `crun`, `conmon`, `netavark`, `aardvark-dns`,
  `containers-common` and `passt` (pasta) — by the owner's decision of 2026-09-22: they change how
  every container starts, and they move on a tested maintenance, testing host first.
- **The window is 07:00 host-local plus up to 15 minutes**, clear of the backup (04:15, up to an hour),
  the restore drill (Sunday 05:30), the weekly cleanup (Saturday 02:00 UTC) and the certificate
  collector (03:40).
- **A reboot is an owner decision**, never automatic. The host reports that one is due instead:
  `scripts/host-updates-metrics.sh` writes `basetool_host_reboot_required` from `needs-restarting -r`,
  run from `dnf-automatic.service`'s `ExecStopPost=` and again at boot.
- **Every run records itself**: the same script writes the run's timestamp and outcome from systemd's
  `$SERVICE_RESULT`. `HostSecurityUpdatesFailing`, `HostSecurityUpdatesStale` and
  `HostRebootRequired` read them (REQ-OBS-011).

**Acceptance**

- [ ] `check-conformance.py --only security-updates-enabled` passes against the host: the timer is
  enabled and active, the configuration is security-only and applies, and `podman` is excluded.
- [ ] `host-updates-metrics.test.sh` passes: a run is recorded by the run, a boot refresh keeps it,
  and a reboot state that cannot be read is left out rather than written as `0`.
- [ ] `ops_audit_2026_09_alerts_test.yml` passes: each of the three alerts fires and stays silent where
  it must.

**Enforced by:** `ansible/roles/basetool_host/tasks/45-updates.yml` ·
`scripts/host-updates-metrics.sh` · `scripts/iri-host-updates-metrics.service` ·
`monitoring/prometheus/alerts/infrastructure.yml` · `scripts/check-conformance.py` · **Decision:**
[ADR-0199](../adr/0199-the-host-applies-security-updates-unattended-with-the-container-runtime-excluded.md)

### REQ-OPS-033 — A realm gets the Basetool's shape from the provisioner, never by hand

Every environment's Keycloak realm carries the **production shape** of what the Basetool owns, and
that shape is applied by `scripts/provision-keycloak-realm.py` rather than rebuilt from runbook
steps. The realm lives in each host's `db-keycloak` and no artifact carries it, so REQ-OPS-022's
lock-step stops at the realm: on 2026-09-22 the testing realm had no audience mapper, no extractor,
gateway or Android client and no DPoP policy, and the backend's audience gate could not be enabled
there.

- **The desired state is production's**, read with the secret-free
  `scripts/keycloak-config-snapshot.sql` and written into the script: the Basetool's clients and
  their flags, attributes, redirect URIs, web origins, scope assignments and mappers; the
  `extractor-ingest` / `extractor-ingest-only` scopes and their audience mappers; the Android
  client's marker role and realm-role scope; the DPoP client profile and policy; the
  service-account roles; the realm's token and session settings. What production carries that looks
  unintended is reproduced and marked `PROD-AS-IS`, and changes in production first.
- **Environment-specific values are arguments** (`--public-origin`, `--grafana-origin`). No
  production hostname is written into another realm.
- **Dry run by default.** `--apply` writes, then re-plans; the apply fails unless the second plan is
  empty. A realm in shape produces no write at all.
- **Additive.** An object only the target realm has is reported and left alone. The exceptions are
  owner decisions, each named in the script — the Android client's realm-role scope
  (`REQ-SEC-035`), its withheld `offline_access` (ADR-0131), and the three retirements of
  2026-09-22 (below) — and the scope lists of a client the same run created.
- **Three production entries are retired and converge away wherever they are found** (owner
  decision 2026-09-22, ADR-0202 amendment): `basetool-sc-extractor`'s authorization-code flow and
  its two loopback wildcard redirect URIs (`REQ-INGEST-002`); both ingest scopes on
  `basetool-android` (`REQ-INGEST-011`); `basetool-frontend`'s `http://frontend:18081` redirect URI
  and web origin. Gone from production since the owner-approved provisioner apply of 2026-09-23;
  the testing realm keeps them until it is provisioned.
- **`basetool-frontend` carries `baseUrl` = `<--public-origin>/`** (2026-09-25, ADR-0202
  amendment 3, `REQ-SEC-071`) — an added field, not in the 2026-09-22 production snapshot, so that
  Keycloak's error pages for the web login link back to the app. *(Updated 2026-09-25: production
  carries it — the owner set Home URL `https://profit-base.online/` by hand in the Admin Console,
  not through an `--apply`; it equals what the provisioner converges to.)*
- **The DPoP write order holds** (`REQ-SEC-030`): when the Android client or the DPoP profile has to
  change, the policy is detached first and re-attached last, and both client-policy lists are merged
  by name so no other policy or profile is lost.
- **No secret is printed, logged or sent back.** A confidential client the run creates is reported
  with the Admin Console path to its generated secret and the `.env` variables that need it.
- **Built-in Keycloak objects are never touched**; realm-wide hardening stays in
  `KEYCLOAK_HARDENING_RUNBOOK.md`.

**Acceptance**

- [ ] `provision-keycloak-realm.test.sh` passes: a dry run writes nothing; an apply from an empty
  realm builds the shape and verifies clean; a second apply sends no write; an Android edit detaches,
  writes and re-attaches in that order and keeps every foreign policy; target-only objects are
  reported and still present; no secret appears in the output or in any payload.
- [ ] After provisioning, `keycloak-config-snapshot.sql` run on the environment and on production
  diffs only in the lines its header lists as environment-specific.
- [ ] `provision-keycloak-realm.test.sh` section 8: against a realm still carrying the three retired
  entries, the plan removes exactly those (plus the DPoP detach/re-attach the app's scope removal
  needs) and a second apply is empty.

**Enforced by:** `scripts/provision-keycloak-realm.py` · `scripts/provision-keycloak-realm.test.sh`
(`.github/workflows/keycloak-provisioner.yml`) · `scripts/keycloak-config-snapshot.sql` ·
**Decision:**
[ADR-0202](../adr/0202-a-realm-is-brought-to-the-production-shape-by-a-provisioner-that-never-deletes.md) ·
**Runbook:** [`INGEST_KEYCLOAK_SETUP.md` → *New or out-of-date realm*](../INGEST_KEYCLOAK_SETUP.md#new-or-out-of-date-realm-run-the-provisioner)

### REQ-OPS-034 — Every artifact the build resolves matches a committed checksum

The build verifies what it downloads, not only which version it asks for (SEC-15, 2026-09-23).
`gradle/verification-metadata.xml` lists a **SHA-256 for every jar, POM and Gradle module file**
the build resolves — the applications' runtime and test classpaths, the Gradle plugins, the tool
configurations (Checkstyle, SpotBugs, PIT, JaCoCo, Spotless, the OWASP scan) and the Node.js
archive the frontend's linters run on — and Gradle refuses an artifact whose bytes do not match, or
that has no entry at all, before it is used.

- **Strict everywhere a product is built**: CI, the E2E and PIT workflows, the three image builds
  (`docker/app/Dockerfile` copies `gradle/`; verified strict on an empty cache in all three builds
  on 2026-09-23) and the release workflows run in the default strict mode.
  `dependency-submission.yml` alone runs lenient — it ships nothing, and its action injects an
  init-script plugin the file does not describe.
- **Checksums only.** PGP signatures are not verified (ADR-0208 says why and when to revisit).
- **`-sources.jar` and `-javadoc.jar` are trusted** by pattern: IDE downloads, never on a build
  classpath.
- **The change that alters the graph carries the regenerated file**, produced by
  `GRADLE_USER_HOME="$(mktemp -d)" ./gradlew --write-verification-metadata sha256 help build :frontend:compileE2eJava`
  ([`CONTRIBUTING.md`](../../CONTRIBUTING.md) → *Dependency verification*). A catalog bump without it
  fails CI with the offending coordinates.
- **The Node.js archive differs per operating system**, so its component carries one artifact per
  platform the build runs on (`win-x64`, `linux-x64` at introduction).

**Acceptance**

- [x] On an empty Gradle user home, in Linux, `--dependency-verification strict` resolves and runs
  `help`, `assemble`, `compileTestJava`, `:frontend:compileE2eJava`, `:frontend:nodeSetup`,
  `spotlessCheck`, `checkstyleMain`, `spotbugsMain`, every `licensee` and every `cyclonedxBom` without
  a verification failure (2026-09-23).
- [x] The full CI build (`build :frontend:compileE2eJava`) on the maintainer's Windows
  workstation with the warm cache.
- [x] An artifact whose checksum is edited in the file fails the build with
  "Dependency verification failed" (the negative case, run once at introduction).

**Enforced by:** `gradle/verification-metadata.xml` (every Gradle invocation) · `ci.yml`,
`e2e.yml`, `pitest.yml`, `release-images.yml`, `docker/app/Dockerfile` (strict by default) ·
**Decision:** [ADR-0208](../adr/0208-gradle-verifies-every-dependency-against-a-committed-sha-256.md)
· **Related:** REQ-OPS-025 (the SBOMs describing what these artifacts become), REQ-OPS-021 (one
image build per commit)

### REQ-OPS-035 — A Dependabot compose bump carries its regenerated units

Dependabot's `docker-compose` ecosystem edits `docker-compose*.yml` only, while production runs the
Quadlet units generated from them (REQ-OPS-004). A compose image bump is therefore **completed on its
own branch before it can merge**: `.github/workflows/dependabot-compose.yml` re-resolves each digest the
bump pins to the index its tag names now, regenerates `quadlet/` with `scripts/generate-quadlet.py`,
fixes the documented monitoring pins with `scripts/check-monitoring-image-pins.sh --fix`, and commits
the result onto the Dependabot branch.

- **Only a compose bump is completed.** The run refuses a branch holding any commit that is not
  Dependabot's (compose files only) or the `basetool-release` App's, and any merge commit; a refused or
  failed run leaves the PR red on `Repository gates`, as before.
- **The commit triggers the required checks.** It is created with a `basetool-release` App token
  (`contents: write`) read from the Dependabot secret `RELEASE_APP_PRIVATE_KEY`, through
  `createCommitOnBranch`, so GitHub signs it; `GITHUB_TOKEN` is never used for it, because its commits
  trigger no workflow.
- **It passes the DCO check as a bot commit**: its author `basetool-release[bot]` is in `dco.yml`'s bot
  list, and its `Signed-off-by` matches the author.
- **A digest that cannot be resolved is kept and warned about**, never dropped; the workflow never
  adds or removes a file.
- **No `pull_request_target` or `workflow_run` trigger** is used for it.

**Acceptance**

- [ ] A Dependabot PR that bumps a compose image gains one `basetool-release[bot]` commit updating the
  affected `quadlet/systemd/*.container` files (and any documented monitoring pin), and `Repository
  gates` is green on the new head without a human commit.
- [ ] A second run on the completed head commits nothing.
- [ ] A Dependabot branch carrying a human commit or a merge commit is refused with an error naming
  the commit.
- [x] `dependabot_compose_followup.py --selftest` passes in `repo-lint.yml` (`quadlet-drift`).

**Enforced by:** `.github/workflows/dependabot-compose.yml` ·
`.github/scripts/dependabot_compose_followup.py` · `.github/scripts/create_signed_commit.py` ·
`.github/workflows/dco.yml` (`bot_emails`) · `repo-lint.yml` (`quadlet-drift`,
`monitoring-image-pins`) · **Decision:**
[ADR-0215](../adr/0215-a-dependabot-compose-bump-carries-its-regenerated-units.md) · **Runbook:**
`docs/deployment.md` → *Dependabot image bumps* · **Related:** REQ-OPS-004 (the units as the delivered
definition)

## Open questions

- Deepening the infra health gate beyond `redis-cli ping` / `pg_isready` (which do not
  exercise the app workload) — e.g. recreating dependents on a config-digest change so their
  healthchecks gate the rollback. Promote to an ADR if pursued.
