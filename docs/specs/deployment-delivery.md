> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-07-07.
> **Owner area:** OPS · **Related ADRs:** [ADR-0049](../adr/0049-config-as-promotable-oci-artifact.md), [ADR-0055](../adr/0055-keycloak-spi-jar-as-promotable-oci-artifact.md), [ADR-0075](../adr/0075-host-side-cosign-signature-verification.md), [ADR-0079](../adr/0079-redis-session-store-aof-and-maxmemory-noeviction.md)

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

A promotion passes two gates before it flips `:stable`: (1) a **human-approval** gate — a dedicated
`approve` job is bound to the `production` GitHub Environment, so a repo-configured required reviewer
must approve the run before the promote matrix starts (workflow_dispatch alone lets anyone with
Actions-write reach prod). The gate lives on its **own single job**, not on the promote matrix, so the
run mints exactly one GitHub deployment record — a matrix-bound environment minted one deployment per
leg, four of which `auto_inactive` then flipped to `inactive`, leaving the repo home page showing a
misleading permanent "Inactive" badge for `production`. Gate (2) is the
**signature** gate — cosign-verify the source digest against the release-images identity, so only an
image built and signed by our own release pipeline can be promoted. `release-images.yml` scans every
build with Trivy (SARIF uploaded to the Security tab for visibility) but the scan blocks neither the
build nor the promotion — a Trivy finding is surfaced, not enforced, at any stage.

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

**Enforced by:** `.github/workflows/release-images.yml` · `.github/workflows/promote.yml` (`environment`, cosign gate) · **Runbook:** `docs/deployment.md` → *Promoting to production*

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

One apply mode is special: a change to the compose **`networks:` topology** (a re-pinned subnet, a
network added/removed) **cannot** be applied by an in-place `up -d` — Docker can neither move a
running container onto a differently-addressed bridge nor recreate a bridge that still has
endpoints, so an in-place apply silently **strands** container name resolution (the 2026-07
`keycloak`↔`backend` / `keycloak`↔`db-keycloak` incident, #974). `deploy.sh` detects it (the promoted
compose's `networks:` block differs from the live one, comments ignored) and applies it via a **clean
down+up**: the app project *and* the monitoring project that references the shared data nets as
`external` are brought fully down, the stale bridges dropped, then recreated on the (pinned) subnets —
on the forward apply **and** on the rollback. This is a brief full-stack outage, taken *only* on an
actual `networks:` change; every ordinary config/app change keeps the fast rolling in-place `up`. It
is **not** operator-gated (unlike the stateful-infra carve-out, REQ-OPS-006) — no data migration is
involved, and the subnet pinning keeps the recreated gateways stable, so the NPM SSH-tunnel admin
allow-list stays valid.

**Acceptance**

- [ ] After a promotion whose only change is an infra pin bump, the next timer tick applies
  the new compose and recreates the affected container without operator file copying.
- [ ] `deploy.sh`'s idempotence marker carries five fields
  (`backend|frontend|ingest|config|keycloak-spi`); a changed config OR provider-JAR digest alone
  makes the marker differ and triggers an apply.
- [ ] A missing/unresolvable `basetool-config` artifact degrades to the legacy app-only deploy
  rather than failing the loop.
- [ ] A promotion that changes the compose `networks:` block is applied via a clean down+up (not an
  in-place `up`) on both apply and rollback, so name resolution is not stranded; a
  `networks:`-unchanged config bump keeps the in-place `up`.

**Enforced by:** `scripts/deploy.sh` (config-delivery block, `EXPECTED_MARKER`, `network_block` /
`clean_slate_recreate`) · `docker/config/Dockerfile` · `.github/workflows/release-images.yml`
(`build-config`) · **Runbook:** `docs/deployment.md` → *Infra / host-config bumps*

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
actual content change (config edits are rare, so the brief scrape gap is negligible). It is a no-op
on a host without the monitoring stack (`IRI_MONITORING_ENABLED` unset).

**Acceptance**

- [ ] A matching marker over a converged, healthy stack exits 0 without pulling or restarting
  anything ("no change", running stack verified).
- [ ] On a host with monitoring enabled, a tick whose on-disk Prometheus config differs from the
  last applied snapshot force-recreates Prometheus — including on the converged no-op fast-exit —
  without pulling or re-applying the app stack; a component whose on-disk config already matches its
  snapshot is left untouched, and the reconcile never gates the deploy.
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

**Enforced by:** `scripts/deploy.sh` (`running_stack_drift`, idempotence check, `reconcile_monitoring_reload(s)`) · `scripts/deploy.test.sh` (self-tests, run by `.github/workflows/deploy-script.yml`) · **Runbook:** `docs/deployment.md` → *What happens on the server*, *Restarting the stack manually*

### REQ-OPS-014 — Every prod service runs with a hardened runtime baseline

**Every** `prod`-profile container runs with the runtime-hardening baseline, not just the edge
proxy: `security_opt: no-new-privileges:true`, `cap_drop: [ALL]` with an explicit, minimal
`cap_add` allow-list, and a `pids` ceiling. The intent is defence-in-depth against a
container-escape or in-container compromise — a service that cannot escalate privileges and holds no
capabilities it does not need is a far smaller blast radius, and it matters most on the two
internet-reachable edges (`npm`, `ingest`).

The capability add-back set is **per service**, defined by what each image's entrypoint actually
needs:

- **App services (`backend`, `frontend`, `ingest`) and `keycloak`** run as a fixed non-root uid
  (10001 for the JVM apps, 1000 for Keycloak/Quarkus) and bind only high ports, so they need **no**
  capabilities — `cap_drop: [ALL]` with an empty add-back.
- **`postgres` (db-backend, db-keycloak) and `redis`** boot as root to chown their data dir and then
  drop to their service user via gosu, so they keep exactly `CHOWN`/`DAC_OVERRIDE`/`FOWNER` +
  `SETGID`/`SETUID`. `no-new-privileges` still holds because gosu drops via the `CAP_SETUID` syscall,
  not a setuid binary.
- **`npm`** keeps its empirically-verified s6-overlay set (`NET_BIND_SERVICE`, `CHOWN`, `SETUID`,
  `SETGID`, `FOWNER`, `DAC_OVERRIDE`, `KILL`).

Because the add-back set is not upstream-documented for the third-party images, it **must be
re-verified on every image bump** of that service before the bump is promoted (a clean boot, its
healthcheck passing, and — for `npm` — a working `nginx -s reload`). The deploy health-gate is the
safety net: a wrong cap set fails the container at start and is rolled back rather than shipped. A
read-only root filesystem is explicitly **out** of this baseline (the `npm` s6 prepare step and the
JVM/DB working dirs write across the filesystem).

**Acceptance**

- [ ] Every `prod`-profile service in `docker-compose.yml` (backend, frontend, ingest, keycloak,
  redis, db-backend, db-keycloak, npm) sets `no-new-privileges:true`, `cap_drop: [ALL]` with an
  explicit (possibly empty) `cap_add`, and a `pids` ceiling.
- [ ] `backend`/`frontend`/`ingest`/`keycloak` carry no `cap_add`; `postgres`/`redis` carry only the
  chown + privilege-drop set; `npm` carries only its verified s6 set.
- [ ] An image bump for any of these services is only promoted after its capability set has been
  re-verified against the new image (boot + healthcheck [+ `nginx -s reload` for npm]).

**Enforced by:** `docker-compose.yml` (all `prod` services) · verification recipe in the service comments

### REQ-OPS-015 — Host-side signature verification before apply

The production host **cryptographically verifies** every artifact it is about to run. Before
`deploy.sh` pulls, extracts or applies any resolved digest — the three app images and, when
present, the `basetool-config` and `basetool-keycloak-spi` bundles — it `cosign verify`s that
`image@digest` against the **release-images** workflow's keyless (Fulcio/OIDC) signature. This is
the **host half** of the supply-chain seam; `promote.yml`'s pre-flight verify (REQ-OPS-002) is the
CI half. Neither alone is sufficient: `promote.yml` verifies at promotion time in CI, but the host
re-resolves `:stable` independently on every tick, so a `:stable` tag moved out-of-band — a leaked
`packages:write` credential retagging an arbitrary digest, or a registry-side tag manipulation —
would otherwise be pulled and run **unverified** (and the deploy user is in the `docker` group, i.e.
root-equivalent). The host gate closes that TOCTOU: the tag verified at promote time is no longer
assumed to be the artifact the host pulls later.

The trusted signer identity is pinned to `…/release-images.yml@refs/(heads/main|tags/v.+)` — a
main-branch or tagged build only, never a `workflow_dispatch` build off an arbitrary branch — and
the **same** identity is used by `promote.yml` and by `deploy.sh` so the two halves cannot diverge.
The gate is **fail-closed**: a host without `cosign` (with verification enabled) aborts the tick
rather than falling back to trusting an unverified image. A single break-glass override
(`IRI_COSIGN_VERIFY=false`) exists **only** to ride out a Sigstore public-good outage that is
blocking every deploy; it is logged loudly on every skipped verification.

**Version compatibility.** cosign is not backward-compatible across majors: cosign 3.x verifies
both 2.x and 3.x keyless signatures, but cosign 2.x **cannot** verify 3.x signatures. The CI signs
with the cosign that `sigstore/cosign-installer` pins (currently 3.0.6 via `@v4.1.2`), so the host
cosign must be **≥ that version and never a lower major** — otherwise the fail-closed gate blocks
every deploy. The host tracks the current 3.x release (3.1.1); when the CI installer pin is bumped,
the host is kept ≥ it.

**Acceptance**

- [ ] `deploy.sh` runs `cosign verify` (identity `…/release-images.yml@refs/(heads/main|tags/v.+)`,
  issuer `token.actions.githubusercontent.com`) against every resolved `image@digest` — backend,
  frontend, ingest, and the config + keycloak-spi bundles when resolved — **before** the first
  `pull`/`docker create`/`docker cp`/`up`, and aborts the tick non-zero on any verification failure.
- [ ] A `:stable` digest that is unsigned or signed by any other identity is never pulled, extracted
  onto the host, or applied; the failure records a deploy-failure metric (surfacing `DeployFailed`).
- [ ] Verification does not run on the steady-state idempotence no-op tick (on the apply path it
  runs only once the tick is committed to applying, past the no-op and the bad-digest backoff).
- [ ] `deploy.sh --check-only` verifies every resolved digest as a dry-run signature preflight —
  reporting per artifact and exiting non-zero on failure — **without** writing a deploy metric or
  applying anything (so it does not trip `DeployFailed`); it runs even over a converged no-op stack.
- [ ] `cosign` missing on the host with `IRI_COSIGN_VERIFY=true` fails the pre-flight; the sole
  documented override is `IRI_COSIGN_VERIFY=false` for a Sigstore outage.
- [ ] `promote.yml` verifies against the identical `@refs/(heads/main|tags/v.+)` identity regexp.
- [ ] The host `cosign` is a major **≥** the cosign the CI signs with (`cosign-installer` pin, 3.x);
  a host on cosign 2.x cannot verify the 3.x signatures and is a mis-bootstrap, not a supported mode.

**Enforced by:** `scripts/deploy.sh` (`verify_signature`, `verify_digest_or_die`, cosign pre-flight)
· `scripts/deploy.test.sh` (signature-gate self-tests) · `.github/workflows/promote.yml` (pinned
identity) · **Runbook:** `docs/deployment.md` → *Signature verification (cosign)* · **Decision:** ADR-0075

### REQ-OPS-016 — Deploy host runtime hardening

The deploy path is hardened at the host layer, beyond running as an unprivileged user:

- **Systemd sandbox.** `iri-deploy.service` confines the `deploy.sh` process with a defence-in-depth
  baseline: `NoNewPrivileges`, `ProtectSystem=strict`, `ProtectHome`, `PrivateTmp`, `PrivateDevices`,
  `ProtectKernelTunables`/`Modules`/`Logs`, `ProtectControlGroups`, `ProtectClock`, `ProtectHostname`,
  `ProtectProc=invisible`, `RestrictNamespaces`, `RestrictRealtime`, `RestrictSUIDSGID`, `RemoveIPC`,
  `LockPersonality`, an **empty** `CapabilityBoundingSet`, `RestrictAddressFamilies` limited to
  AF_UNIX/AF_INET/AF_INET6/AF_NETLINK, `SystemCallArchitectures=native`, and a
  `SystemCallFilter=@system-service` seccomp allow-list. `ReadWritePaths` is the **narrow** set the
  script actually writes (`/var/lib/iri /var/log /var/lock /var/iri/code /var/iri/monitoring`) — NOT
  the whole `/var/iri`, whose DB/redis/npm bind mounts are written by the containers via the root
  daemon, out-of-band from this sandbox. It is understood that docker-group membership remains
  root-equivalent (a socket-proxy / rootless-Docker reduction is deferred, documented in the runbook).
- **Keystore not world-readable.** The shared `keystore.p12` is delivered `0640 root:10001` with a
  POSIX ACL granting read to uid 1000 (`setfacl -m u:1000:r`) — so both container uids read it (10001
  via group, 1000 via ACL) without the private-key material being readable by `other`. The previous
  `0644` made it readable by every account on the host.
- **Token expiry is monitored (opt-in).** When the pull token **expires**, `deploy.sh` emits
  `basetool_ghcr_token_expiry_timestamp` from an operator-recorded `${TOKEN_FILE}.expiry` on every
  tick (incl. the no-op); `GhcrPullTokenExpiring` (warning, <14 d) and `GhcrPullTokenExpired`
  (critical) alert before/at the lapse, whose expiry would otherwise silently stop all deploys.
  A deliberately **non-expiring** token omits the `.expiry` file — no metric, and the alerts do
  **not** fire on absence (no `absent()` guard), so a non-expiring token is not falsely warned on.

**Acceptance**

- [ ] `iri-deploy.service` sets the sandbox baseline above, an empty `CapabilityBoundingSet`, a
  seccomp `SystemCallFilter`, and a `ReadWritePaths` that excludes the DB/redis/npm bind mounts.
- [ ] The keystore is `0640` with a `user:1000:r` ACL, not world-readable `0644`; the runbook +
  restore procedure re-apply the ACL.
- [ ] `deploy.sh` writes `basetool_ghcr_token_expiry_timestamp` when `${TOKEN_FILE}.expiry` exists
  (and nothing when it does not); `ops-automation.yml` alerts on <14 d / expired only — **not** on
  absence, so a non-expiring token is not false-warned.

**Enforced by:** `scripts/iri-deploy.service` (sandbox) · `scripts/deploy.sh` (`write_token_expiry_metric`,
`HOME` for the cosign cache) · `monitoring/prometheus/alerts/ops-automation.yml` (token alerts) ·
**Runbook:** `docs/deployment.md` → *5.2 PKCS12 keystore*, *Docker access hardening*, *Token rotation*

### REQ-OPS-018 — Redis session store: durable persistence and a session-safe memory ceiling

The Redis instance backing Spring Session (frontend) and the ingest handoff staging runs with a
durability and memory posture matched to a store whose loss forces users to re-login — **not** a
throwaway cache (Redis is session-store only, ADR-0074):

- **Durable persistence — RDB + AOF.** `--appendonly yes --appendfsync everysec` makes AOF the
  primary durability layer (~1 fsync/s regardless of write volume; ~1 s worst-case loss on a crash),
  and `--save "60 1"` keeps a compact RDB snapshot for fast restart and to keep the `RedisRdbStale`
  probe green. On restart Redis loads the AOF. Both files live on the `/var/iri/redis` bind mount and
  are **excluded from off-site backups** (REQ-OPS-010 — sessions transparently re-login). The
  `appendfsync always` mode (one fsync per write, the pre-M-7 pathology) is deliberately **not** used.
- **Bounded memory — explicit ceiling below the cgroup.** `--maxmemory 192mb` sits below the 256 MB
  container limit, leaving copy-on-write headroom for the RDB / AOF-rewrite forks and fragmentation,
  so Redis manages the boundary itself instead of ceding it to the kernel OOM-killer.
- **Session-safe eviction — `noeviction`.** `--maxmemory-policy noeviction` is **mandatory**:
  evicting a session key is a silent logout, so at the ceiling Redis refuses **new** writes (a failed
  login) while every live session survives. An evicting policy (`allkeys-*` / `volatile-*`) is a
  defect here.

Both command lines — the `redis-dev` template and the `redis` prod override (which additionally
carries `--aclfile`) — stay in lockstep on these persistence/memory flags. The posture is
**observable**: because `--maxmemory` is set (`redis_memory_max_bytes > 0`), the `RedisMemoryHigh`
leading-indicator alert is functional (it self-guards on that being non-zero and was inert while
maxmemory was unset), and `RedisEvictions` is a misconfiguration tripwire (any eviction under
`noeviction` means the policy was wrongly changed) — both in the alert catalog (REQ-OBS-005).

**Acceptance**

- [ ] Both redis command lines in `docker-compose.yml` set `--appendonly yes --appendfsync everysec`,
  `--save "60 1"`, `--maxmemory 192mb`, and `--maxmemory-policy noeviction`; the prod override keeps
  `--aclfile` and the two lines carry identical persistence/memory flags.
- [ ] `--maxmemory` (192mb) is strictly below the container memory limit (256M) so a snapshot /
  AOF-rewrite fork has copy-on-write headroom.
- [ ] The eviction policy is `noeviction`; no `allkeys-*` / `volatile-*` policy is configured.
- [ ] `RedisMemoryHigh`, `RedisEvictions`, and `RedisRdbStale` exist in `infrastructure.yml` and
  their descriptions match this posture (192mb maxmemory, noeviction semantics, AOF-primary durability).

**Enforced by:** `docker-compose.yml` (`x-redis` template + `redis` prod override) ·
`monitoring/prometheus/alerts/infrastructure.yml` (Redis memory/persistence alerts) · **Decision:** ADR-0079

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

