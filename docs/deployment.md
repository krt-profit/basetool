# Profit Basetool — Deployment Runbook

## Overview

Production deployment runs as a closed loop between three actors:

```
┌──────────────────────────────┐       ┌────────────────────────────┐
│  GitHub Actions              │       │  GitHub Container Registry │
│                              │       │                            │
│  .github/workflows/          │  push │  ghcr.io/krt-profit/          │
│    release-images.yml ───────┼──────►│    basetool-backend:1.4.2  │
│      plan   (build or reuse) │       │    basetool-frontend:1.4.2 │
│      build  + push           │       │    basetool-ingest:1.4.2   │
│      scan   (Trivy SARIF)    │       │    basetool-config:1.4.2   │
│      sign   (cosign keyless) │       │      ... :latest, :edge,   │
│                              │       │      :sha-abc1234, :stable │
│                              │       │                            │
│  .github/workflows/          │       │                            │
│    promote.yml      ─────────┼──────►│    (re-tags app images +   │
│      manual dispatch         │       │     config to :stable)     │
└──────────────────────────────┘       └────────────────────┬───────┘
                                                            │
                                                            │ docker pull
                                                            │
                                       ┌────────────────────▼───────┐
                                       │  Production host           │
                                       │                            │
                                       │  /var/iri/code/            │
                                       │    docker-compose.yml      │
                                       │    .env                    │
                                       │    scripts/deploy.sh ──┐   │
                                       │                        │   │
                                       │  /var/iri/secrets/     │   │
                                       │    keystore.p12        │   │
                                       │                        │   │
                                       │  /etc/iri/             │   │
                                       │    ghcr-pull-token     │   │
                                       │                        │   │
                                       │  /var/lib/iri/         │   │
                                       │    current-digest-pin.yml  │
                                       │    previous-digest-pin.yml │
                                       │    last-deployed.digests   │
                                       │                            │
                                       │  systemd: iri-deploy.timer │
                                       │    OnUnitActiveSec=5min    │
                                       └────────────────────────────┘
```

**What gets pushed to GHCR carries no secrets.** The keystore, `.env`,
`realm-export.json`, and the keycloak theme directory all live on the host
filesystem and are bind-mounted into the containers at runtime. The
`.dockerignore` at the repo root is a belt-and-suspenders guard against ever
including them in a build context.

**The host pulls; nothing pushes to the host from GitHub.** The deploy timer
holds a read-only GHCR token. There is no inbound SSH, no webhook, no
GitHub-issued credential capable of running shell commands on the box.

**Tag promotion is deliberate.** `release-images.yml` publishes versioned
tags (`:1.4.2`, `:latest`, `:edge`, `:sha-abc1234`) on every main push and
git tag. None of those flips the `:stable` pointer that the server polls.
That happens only when an operator runs the `promote.yml` workflow with an
explicit version.

**Host config travels the same channel as the images.** The compose file and
its bind-mounted asset trees (the NPM maintenance page under `docker/maintenance/`,
the Keycloak login theme under `keycloak-theme/`) are packaged as the
`basetool-config` OCI image (`docker/config/Dockerfile`, a `FROM scratch` bundle),
signed, and promoted to `:stable` **in lock-step** with the app images. So a
promoted compose change — e.g. a bumped redis/npm image pin — reaches the host
and is applied automatically, with no manual `cp docker-compose.yml` and no
hand-run `docker compose up -d`. The one carve-out is a **postgres/Keycloak**
image change, which stays operator-gated (see *Stateful-infra upgrades* below).
`deploy.sh` and the systemd units are **not** in the bundle (self-update hazard)
— they remain the manual bootstrap concern below. Full rationale: [ADR-0049](adr/0049-config-as-promotable-oci-artifact.md),
binding requirements: [REQ-OPS-*](specs/deployment-delivery.md).

---

## Initial server bootstrap (one-time)

The current "copy the whole `basetool` folder to the server" workflow becomes
a small, deliberate set of files that live on the host. After bootstrap, no
further file sync between developer machines and the server is needed —
updates arrive only as GHCR image pulls.

### 1. System packages

```bash
sudo apt update
sudo apt install --no-install-recommends \
    docker.io docker-compose-v2 \
    logrotate \
    acl \
    curl ca-certificates
```

`acl` provides `setfacl`, used below to grant the keystore to two container uids
without making it world-readable.

Docker Engine ≥ 23.x is required for `docker compose up --wait`.

**cosign** is required for the host-side signature gate (REQ-OPS-015 — `deploy.sh`
verifies every image before it runs it). It is not in Ubuntu's default repos, so
install the pinned release binary and verify its checksum before installing:

```bash
COSIGN_VERSION=v3.1.3
arch=$(dpkg --print-architecture)          # amd64 or arm64
cd /tmp
# Download under the SAME name the checksum file lists — `sha256sum -c` resolves
# the name from its input line against the cwd, so saving it as /tmp/cosign makes
# the check fail with "No such file or directory" instead of verifying anything.
curl -fsSLo "cosign-linux-${arch}" "https://github.com/sigstore/cosign/releases/download/${COSIGN_VERSION}/cosign-linux-${arch}"
curl -fsSLo cosign_checksums.txt   "https://github.com/sigstore/cosign/releases/download/${COSIGN_VERSION}/cosign_checksums.txt"
# Verify the download against the published checksum, then install:
grep " cosign-linux-${arch}\$" cosign_checksums.txt | sha256sum -c -
sudo install -m 0755 "cosign-linux-${arch}" /usr/local/bin/cosign
rm -f "cosign-linux-${arch}" cosign_checksums.txt
cosign version
```

Expect `cosign-linux-<arch>: OK` from the checksum line and the new version from
`cosign version`. Chain the steps with `&&` if you paste them as a one-liner, so a
failed checksum cannot fall through to `install`.

> **Use cosign 3.x, and never a lower major than the CI signs with.** The CI signs
> images with the cosign that `sigstore/cosign-installer@v4.1.2` pins — currently
> **cosign 3.0.6** — and **cosign 2.x cannot verify cosign 3.x keyless signatures**
> (3.x verifies 3.x and 2.x; 2.x does not verify 3.x). A host on cosign 2.x would
> therefore fail this gate on every deploy (fail-closed). Keep the host on the
> current 3.x release (3.1.3 verifies the CI's 3.0.6 signatures), and when the CI's
> `cosign-installer` pin is bumped, keep the host **≥** that cosign version. Already
> installed an older cosign? See [Updating cosign](#updating-cosign).
>
> **Why 3.1.3 and not 3.1.2.** cosign ≤ 3.1.2 (and ≤ 2.6.4) carries
> [GHSA-fx35-mq7g-6g98](https://github.com/sigstore/cosign/security/advisories/GHSA-fx35-mq7g-6g98)
> (High, CVSS 7.4): when a legacy JSON bundle's `cert` field fails X.509 parsing,
> cosign silently falls back to treating it as a raw public key, which skips
> certificate-chain validation and makes `--certificate-identity` /
> `--certificate-oidc-issuer` no-ops. **This gate was never exposed** — the flaw
> reaches only `verify-blob` / `verify-blob-attestation` with legacy bundles, while
> `deploy.sh` and `promote.yml` verify OCI images, which upstream states is not
> affected. Take 3.1.3 anyway: a host binary that ignores identity pinning under
> *any* input shape is not something to keep around for the sake of it.

`deploy.sh` fails its pre-flight if `cosign` is missing while the gate is enabled
(`IRI_COSIGN_VERIFY=true`, the default) — see *Signature verification (cosign)*.

### 2. Dedicated `deploy` user

A non-root user with no shell and group membership only in `docker`:

```bash
sudo useradd --system --no-create-home --shell /sbin/nologin --groups docker deploy
```

The systemd unit runs as this user. It has no sudo, no SSH access, and
cannot escape the docker-group blast radius.

### 3. Directory layout

```bash
sudo mkdir -p /var/iri/code            # compose file, scripts
sudo mkdir -p /var/iri/secrets         # keystore.p12 lives here
sudo mkdir -p /var/iri/backend/log     # backend log dir (uid 10001)
sudo mkdir -p /var/iri/frontend/log    # frontend log dir (uid 10001)
sudo mkdir -p /var/iri/ingest/log      # ingest log dir (uid 10001)
sudo mkdir -p /var/iri/db-backend      # postgres data
sudo mkdir -p /var/iri/db-keycloak     # keycloak postgres data
sudo mkdir -p /var/iri/keycloak/log    # keycloak file log
sudo mkdir -p /var/iri/redis           # redis AOF
sudo mkdir -p /var/iri/npm/data        # nginx-proxy-manager state
sudo mkdir -p /var/iri/npm/letsencrypt
sudo mkdir -p /var/lib/iri             # deploy state
sudo mkdir -p /etc/iri                 # token

# Log dirs need to be writable by the in-container uid 10001 (set in the
# backend / frontend / ingest Dockerfiles).
sudo chown -R 10001:10001 /var/iri/backend/log /var/iri/frontend/log /var/iri/ingest/log
sudo chown -R deploy:docker /var/lib/iri /var/iri/code

# Docker config dir for the deploy user. `docker login` writes its
# credentials.json into $DOCKER_CONFIG (default $HOME/.docker), and the
# deploy user has no $HOME because it was created with --no-create-home in
# step 2. deploy.sh sets DOCKER_CONFIG=/var/lib/iri/.docker explicitly; we
# pre-create the dir here with 0700 so the credentials file is exclusive
# to the deploy user.
sudo install -d -m 0700 -o deploy -g docker /var/lib/iri/.docker
```

### 4. Compose file + scripts

Copy from the repository — only these trees, never the rest:

```bash
sudo cp docker-compose.yml      /var/iri/code/
sudo cp -r scripts/             /var/iri/code/
sudo cp -r keycloak-theme/      /var/iri/code/
sudo cp -r docker/              /var/iri/code/   # maintenance page assets
sudo chown -R deploy:docker     /var/iri/code
# 0750 (rwx owner deploy, rx group docker, none for other) — consistent with
# docker-cleanup.sh below. systemd and the manual `sudo -u deploy` invocations run
# as the owner, so world-exec is unnecessary.
sudo chmod 0750                 /var/iri/code/scripts/deploy.sh
```

This is a **one-time bootstrap**. After the first `:stable` promotion, the
compose file, the maintenance page (`docker/maintenance/`) and the Keycloak theme
(`keycloak-theme/`) are kept in sync automatically: `deploy.sh` pulls the promoted
`basetool-config` bundle and applies them (see *Infra / host-config bumps* below).
You only re-copy by hand if you change **`scripts/`** (`deploy.sh` or the systemd
units) — those are deliberately excluded from the bundle so a promotion can never
overwrite the running deployer.

`docker-compose.build.yml` does **not** belong on the production host. It
has no purpose there and removing it eliminates any risk of an accidental
`docker compose ... --build` that would attempt to rebuild from a
non-existent source tree.

### 5. Production secrets

#### 5.1 `.env`

```bash
sudo cp .env.example /var/iri/code/.env
sudo chmod 0640 /var/iri/code/.env
sudo chown deploy:docker /var/iri/code/.env
sudo nano /var/iri/code/.env
```

Fill in every `CHANGE_ME`. `IRI_KEYSTORE_HOST_PATH` should point at
`/var/iri/secrets/keystore.p12`. Leave `IRI_BASETOOL_VERSION` unset (the
default `stable` is what the deploy script wants).

#### 5.2 PKCS12 keystore

Place the production `keystore.p12` at the canonical path. It is
read-only-mounted as `/run/secrets/keystore.p12` into every service that
serves or trusts the internal cert — backend, frontend, ingest **and**
Keycloak:

```bash
sudo install -m 0640 -o root -g 10001 /path/to/keystore.p12 /var/iri/secrets/keystore.p12
# The backend/frontend/ingest images run as uid 10001 (covered by the group), but
# the Keycloak (Quarkus) image runs as uid 1000, which no single owner/group can
# also cover. Grant uid 1000 read via a POSIX ACL instead of widening the mode to
# world-readable 0644 — so the private-key material is NOT readable by `other`:
sudo setfacl -m u:1000:r /var/iri/secrets/keystore.p12
# Verify: user::rw-, group::r--, user:1000:r--, other::--- (no world read).
getfacl /var/iri/secrets/keystore.p12
```

The two container uids read it (10001 via the group, 1000 via the ACL entry); the
bind mount preserves the host inode's ACL, and the images run at those literal
host uids (no userns-remap). A plain `0640` without the ACL makes Keycloak fail to
start with `AccessDeniedException /run/secrets/keystore.p12`; the old `0644` made
it readable by every account on the box. Owner root keeps the deploy user from
rewriting it; rotation is a deliberate sudo action. (The cert here is a self-signed
*internal* cert on a single-purpose host — the public Let's Encrypt cert lives in
NPM, not here — but not being world-readable is cheap defence-in-depth.)

#### 5.3 Keycloak realm export

```bash
sudo install -m 0640 -o deploy -g docker /path/to/realm-export.json /var/iri/code/realm-export.json
```

#### 5.4 GHCR pull token

Generate a fine-grained PAT in GitHub:
- Repository access: `krt-profit/basetool` only
- Permissions: `Packages: Read` (no other scopes)
- Expiry: 90 days (recommended — bounds leak exposure; fine-grained PATs must
expire. A classic PAT can be non-expiring, but a token that never expires is
valid forever if leaked, so prefer an expiry + rotation.)

```bash
# Fine-grained PATs are prefixed `github_pat_` (NOT the classic `ghp_`).
sudo install -m 0600 -o deploy -g deploy /dev/stdin /etc/iri/ghcr-pull-token <<< 'github_pat_xxxxxxxx'

# OPTIONAL, only if the token EXPIRES: record its expiry date so the deploy loop
# warns ~2 weeks ahead instead of failing on expiry day (deploy.sh emits
# basetool_ghcr_token_expiry_timestamp; the GhcrPullTokenExpiring alert reads it).
# A NON-expiring token skips this file entirely — no metric, no alert.
sudo install -m 0640 -o deploy -g deploy /dev/stdin /etc/iri/ghcr-pull-token.expiry <<< '2026-10-01'
```

The token file is owner-only readable. The deploy user uses `cat` against
it (via the systemd unit), nothing else touches it. The optional `.expiry`
sidecar holds only the (non-secret) rotation date.

### 6. Install the systemd timer

```bash
sudo cp /var/iri/code/scripts/iri-deploy.service /etc/systemd/system/
sudo cp /var/iri/code/scripts/iri-deploy.timer   /etc/systemd/system/
sudo cp /var/iri/code/scripts/iri-deploy.logrotate /etc/logrotate.d/iri-deploy

sudo touch /var/log/iri-deploy.log
sudo chown deploy:adm /var/log/iri-deploy.log
sudo chmod 0640        /var/log/iri-deploy.log

sudo systemctl daemon-reload
sudo systemctl enable --now iri-deploy.timer
```

Verify:

```bash
systemctl status iri-deploy.timer
systemctl list-timers iri-deploy.timer
```

### 7. First deploy

The timer's first firing is `OnBootSec=5min` after install. To not wait:

```bash
sudo systemctl start iri-deploy.service
tail -f /var/log/iri-deploy.log
```

This pulls `:stable`, applies, waits for health, then exits. The stack is
live.

> **Not `journalctl`.** The unit redirects stdout **and** stderr with
> `StandardOutput=append:` / `StandardError=append:`, and `append:` *replaces*
> journald for those streams instead of teeing to it. `journalctl -u
> iri-deploy.service` therefore shows only systemd's own unit records (Starting /
> Succeeded / Failed / the exit code) — never a line the script printed. The same
> applies to `iri-backup`, `iri-docker-cleanup` and `iri-restore-drill`. Once the
> monitoring plane is up, the off-host equivalent is Grafana → Explore → Loki with
> `{app="ops-deploy"}` (`ops-backup` / `ops-cleanup` / `ops-restore-drill`).

### 8. Weekly Docker housekeeping (optional)

Every `deploy.sh` run already does a best-effort prune of dangling images, but
over time unused image layers, build cache and stopped containers still
accumulate and can fill the disk. [`scripts/docker-cleanup.sh`](../scripts/docker-cleanup.sh)
is a stand-alone janitor that prunes unused images (`-a`), build cache, stopped
containers, unused networks and anonymous volumes — each only when no container
references it, and each gated by an age window so freshly pulled images survive
(image default: 14 days, comfortably outliving the `deploy.sh` rollback anchor).
It is safe by construction: all persistent production data lives in `/var/iri/...`
bind mounts, which the Docker daemon does not manage and `docker volume prune`
cannot touch.

The job runs as the **`deploy`** user (not root), consistent with `deploy.sh`
and the `iri-deploy.timer` pipeline — `deploy` is in the `docker` group, so it
can reach the Docker socket. The unit sets `DOCKER_CONFIG=/var/lib/iri/.docker`
because `deploy` has no usable `$HOME` (`--no-create-home`), the same reason
`deploy.sh` pins it.

Preview what it would reclaim, then install the weekly systemd timer (Saturday
02:00 UTC):

```bash
sudo -u deploy /var/iri/code/scripts/docker-cleanup.sh --dry-run   # show plan + disk usage

sudo chown deploy:deploy /var/iri/code/scripts/docker-cleanup.sh   # owner = deploy
sudo chmod 0750          /var/iri/code/scripts/docker-cleanup.sh   # rwx for deploy, none for others

sudo touch /var/log/iri-docker-cleanup.log
sudo chown deploy:adm /var/log/iri-docker-cleanup.log
sudo chmod 0640       /var/log/iri-docker-cleanup.log
sudo cp /var/iri/code/scripts/iri-docker-cleanup.logrotate /etc/logrotate.d/iri-docker-cleanup

sudo cp /var/iri/code/scripts/iri-docker-cleanup.service /etc/systemd/system/
sudo cp /var/iri/code/scripts/iri-docker-cleanup.timer   /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now iri-docker-cleanup.timer     # arm the weekly tick
```

Force an immediate run with `sudo systemctl start iri-docker-cleanup.service`;
follow it with `tail -f /var/log/iri-docker-cleanup.log` (not `journalctl` — see the
note under *First deploy*), or in Loki with `{app="ops-cleanup"}`. The `UTC` suffix on
the timer's `OnCalendar` pins the schedule to UTC regardless of the host's local
timezone. Retention windows and the volume-prune toggle are overridable via
`IRI_CLEANUP_*` environment variables — see the script header or
`docker-cleanup.sh --help`.

> **Migrating from the old cron drop-in?** This job used to run from
> `/etc/cron.d/iri-docker-cleanup`. Remove it once the timer is armed so the
> cleanup does not run twice: `sudo rm -f /etc/cron.d/iri-docker-cleanup`.

---

## Normal deploy flow

### Cutting a release

Releases are cut through a two-phase, PR-based GitHub Actions flow — there is
**no** hand-pushed git tag and no tag is ever force-moved. Tidying the CHANGELOG
and regenerating the CycloneDX SBOMs happens automatically as part of the run.
The SBOMs are a **release-only** artefact: an ordinary `./gradlew build` (local
or CI) never regenerates them, so the committed `*/docs/*-bom.{json,xml}` only
ever change through this flow (or a deliberate `./gradlew :<module>:cyclonedxBom`).

**Phase 1 — prepare.** Trigger *Actions → Release · Prepare → Run workflow* and
enter the version **without** the leading `v` (e.g. `1.4.3`). Off `main` the
workflow:
- reconciles any historical `[Unreleased]` CHANGELOG drift and **cuts**
`[Unreleased]` into a dated `## [v1.4.3]` section;
- regenerates `*/docs/*-bom.{json,xml}` (pure serial-number / timestamp churn is
discarded — only a real dependency change is kept);
- commits the result on a `release/v1.4.3` branch and opens a
`chore(release): v1.4.3` PR carrying a preview of the release notes.

**Phase 2 — merge.** Review and **merge** that PR. The merge *is* the release:
`release-publish.yml` then
- creates the `v1.4.3` tag **once**, at the merge commit (which already carries
the refreshed CHANGELOG + SBOM, so the tag is never moved afterwards);
- publishes the GitHub Release (notes + image links + the four SBOM files as
assets);
- and the tag push fires `release-images.yml`.

**The tag run does not rebuild.** The tag sits on the merge commit that `main`
already built, so `release-images.yml` re-tags that commit's `:sha-<short>`
digest with the semver tags instead of producing a second set of images
(REQ-OPS-021, [ADR-0137](adr/0137-one-image-build-per-commit-and-no-buildkit-layer-cache.md)).
It does so only after cosign-verifying each digest against the workflow's own
main-branch identity and confirming both architectures are present; any doubt —
a missing tag, a bad signature, a single-arch index — falls back to a full
build. Two operational consequences: a release takes ~5 minutes instead of
~12:45, and `:1.4.3` and `:sha-<short>` are now the **same digest**, so a
rollback to either lands on identical bytes.

Closing the prep PR without merging cancels the release cleanly — no tag, no
orphan commit on `main`.

> **`RELEASE_TOKEN` is required for the images to build automatically.** A tag
> created by CI with the default `GITHUB_TOKEN` does not trigger other workflows
> (GitHub's anti-recursion rule), so `release-publish.yml` creates the tag with
> the `RELEASE_TOKEN` secret (a fine-grained PAT or GitHub App with `contents:
> write`) so that `release-images.yml` fires. If the secret is absent the tag and
> the GitHub Release are still produced, but you must start the image build by
> hand (*Actions → Release Images → Run workflow → `v1.4.3`*); the publish job
> logs a warning saying exactly this. A `workflow_dispatch` run always performs
> a **full build**, never the tag-run re-tag — it is the escape hatch for a
> release whose images are missing or suspect, so it must not reuse them. `RELEASE_TOKEN` is a CI secret, separate
> from the server's GHCR-pull PAT under [Token rotation](#token-rotation).

Within ~10 minutes the images are built, scanned, signed, and available in GHCR
as:

```
ghcr.io/krt-profit/basetool-backend:1.4.3   (also :1.4, :1, :latest)
ghcr.io/krt-profit/basetool-frontend:1.4.3
ghcr.io/krt-profit/basetool-ingest:1.4.3
```

At this point **nothing is deployed yet**. `:stable` still points at the
previously promoted version. Production is unaffected.

### Promoting to production

```bash
gh workflow run promote.yml -f version=1.4.3
```

(or use the GitHub Actions UI: *Actions → Promote to stable → Run
workflow → version `1.4.3`*)

The promotion passes two gates before it flips `:stable` (REQ-OPS-002):

1. **Approval.** The `promote` job is bound to the `production` GitHub
   Environment. A configured **required reviewer** must approve the run in the
   Actions UI before any `:stable` flip happens — so workflow_dispatch alone does
   not reach prod. *One-time setup:* **Settings → Environments → `production` →
   Required reviewers** (add the operator[s]). Until that is configured the
   environment reference is a harmless no-op.
2. **Signature.** cosign-verify of the exact digest against the release-images
   identity (the same identity the host re-verifies — REQ-OPS-015).

`release-images.yml` still scans every built image with Trivy and uploads the
findings to the repository's Security tab, but the scan does not gate the
promotion. Coverage is per **digest**, not per run: a tag run that re-tags the
main run's digest (REQ-OPS-021) publishes no new SARIF, because that digest was
already scanned under the same categories.

This re-tags the existing 1.4.3 image digest as `:stable` in GHCR. No
rebuild. Same digest, two tags.

### What happens on the server

Within at most 5 minutes (timer interval + RandomizedDelaySec):
1. `iri-deploy.service` fires.
2. `deploy.sh` resolves `:stable` → digests for backend + frontend + ingest
**and** the `basetool-config` + `basetool-keycloak-spi` bundles, and compares
the five-field marker with `/var/lib/iri/last-deployed.digests`. When the
marker matches, it additionally verifies the **running** stack before trusting
the no-op: every app service must have a container that is running, healthy
(one still inside its healthcheck start period counts as converged for the
tick; one-off `compose run` containers are ignored) and created from an image
whose RepoDigest equals the target digest. A converged stack exits 0
("no change"); any divergence — a manually-started outdated build, a crash
loop, a half-down stack — is logged as `drift: <service>: <reason>` and
re-applied (REQ-OPS-013).
3. If different (or the running stack drifted): **cosign-verifies every resolved
digest** against the release-images signature (REQ-OPS-015) — a verification
failure aborts here, before anything is pulled or applied — then writes
`current-digest-pin.yml` with the new app digest set.
If the **config** digest moved, it also extracts the promoted bundle, asserts it
carries no secrets, snapshots the live config tree into `config-previous/`, and
copies the new `docker-compose.yml` + maintenance/theme trees into
`/var/iri/code`. Then runs `docker compose pull && docker compose up -d --wait`,
which recreates only the services whose effective spec changed.
4. On all-healthy: updates `last-deployed.digests`, clears the failed/blocked
markers, logs success.
5. On any-unhealthy within 180 s: restores `previous-digest-pin.yml` **and**
the previous config tree, re-ups, logs failure, exits non-zero (the non-zero exit
is what journald and `OnFailure=` hooks see — the *reason* is in the log file, not
in journald).
6. If the promoted config changes a **postgres/Keycloak** image pin, step 3 does
**not** apply it — the run records `config-blocked.marker`, alerts once, then
skips quietly until a new promotion or a `--force` (see *Stateful-infra upgrades*).

You see the result in `/var/log/iri-deploy.log` (`tail -n 100`), or off-host in
Grafana → Explore → Loki with `{app="ops-deploy"}`. `journalctl -u
iri-deploy.service` will **not** show it — see the note under *First deploy*.

### Promoting to testing

The PVE testing stack (`basetool.greluc.me`) is fed by its own tag and its own
workflow (REQ-OPS-022). Nothing reaches it on a `main` merge either:

```bash
gh workflow run promote-testing.yml -f version=sha-abc1234
```

(or *Actions → Promote to testing → Run workflow*)

It accepts any tag `release-images.yml` produced — a `sha-<short>` from a main
merge, a semver from a release, or `stable` when you want the testing stack to
mirror what production currently runs in order to reproduce something.

Same signature gate, same lock-step matrix, same re-tag-not-rebuild mechanics as
the production path. **One difference:** the `testing` environment carries no
required reviewer, so the run does not stop for approval. That is deliberate — the
reviewer on `production` separates *may fire a workflow* from *may change
production*, and a stack with no production data and no users has nothing for that
seam to protect. The environment reference stays because it still records one
deployment per promotion, which is how you answer "which version has been on
testing since when".

The testing host runs the identical `deploy.sh` on the identical timer, with one
line of override:

```ini
# /etc/systemd/system/iri-deploy.service.d/override.conf
[Service]
ExecStart=
ExecStart=/var/iri/code/scripts/deploy.sh --tag testing
```

Signature verification, digest pinning, the health gate and auto-rollback all
behave exactly as they do in production — the only thing that changed is which tag
is resolved.

> A testing environment serves the app under its own domain from the **same**
> promoted config bundle. That works because `IRI_KEYCLOAK_HOSTNAME` and
> `IRI_KEYCLOAK_ISSUER_URI` override the two public-identity values baked into
> `docker-compose.yml`. **Set both or neither** — a half-set pair fails every
> request with `issuer does not match`, which reads like a token bug and is in fact
> a configuration one. Production sets neither and is unaffected.

### Forcing an immediate run

```bash
sudo systemctl start iri-deploy.service
```

This is also the runbook step you take after a manual `:stable` promotion
if you do not want to wait for the next tick.

---

## Infra / host-config bumps (redis, npm, compose edits)

A change to the **general** containers (`redis`, `npm`) or to any other part of
`docker-compose.yml` / the maintenance page / the Keycloak theme reaches prod
through the **same** promote-and-pull flow as an app release — no manual file
copy, no hand-run `docker compose up -d`. Worked example, bumping the pinned
redis image:

1. Edit the `redis` image pin in `docker-compose.yml` (Dependabot opens this PR
   for you on its weekly run), get it reviewed, and merge to `main`.
2. **Cut a release** (`Release · Prepare` → merge the prep PR) — `release-images.yml`
   then builds `basetool-config:<version>` carrying the new compose, signs it, and
   pushes it alongside the app images.
3. **Promote** (`gh workflow run promote.yml -f version=<version>`) — flips the app
   images **and** `basetool-config` to `:stable` together.
4. Within ~5 minutes the timer fires: `deploy.sh` sees the config digest moved,
   stages the new bundle, swaps `docker-compose.yml` into `/var/iri/code`, and
   `docker compose up -d --wait` recreates **redis** (and any other changed
   service). A health failure rolls back the compose file *and* the image pin.

You see it in `/var/log/iri-deploy.log` — or in Loki, `{app="ops-deploy"}` — as
`config changed → staging …` followed by `config applied` and `deploy successful`.
The bundle is content-addressed and signed, so what ran is exactly what was
promoted.

> A config-only bump still rides a release **promotion** — that is the deliberate
> human gate (REQ-OPS-002). If you want the redis bump out without waiting for the
> next feature release, cut+promote a patch release containing just that change.

The state files `deploy.sh` adds for this flow live under `/var/lib/iri/`:
`config-stage/` (extraction scratch), `config-previous/` (rollback snapshot of the
config tree) and `config-blocked.marker` (the stateful-infra gate, below).

**A compose `networks:` edit forces a clean recreate.** A change to the network
topology (a re-pinned subnet, a net added/removed) cannot be rolled onto a running
stack — Docker can't move a live container onto a differently-addressed bridge, so
an in-place `up` silently strands `keycloak`<->`backend` / `keycloak`<->`db-keycloak`
name resolution (the 2026-07 incident, #974). When `deploy.sh` sees the promoted
`networks:` block differ from the live one, it logs `network topology changed ->
clean recreate` and takes the whole stack **fully down** — the app project *and* the
monitoring project (which holds the shared data nets as `external`) — prunes the
stale bridges, then `up`s, on both apply and rollback. That is a brief full-stack
outage, taken **only** on an actual `networks:` change; ordinary bumps keep the fast
in-place `up`. The pinning keeps the recreated gateways stable, so the NPM `/admin`
allow-list stays valid.

**A Postgres `command:` flag edit recreates the database containers — and is _not_
operator-gated.** The stateful-infra carve-out below matches only `image:` lines
(`infra_image_pins()` greps `postgres:` / `quay.io/keycloak/keycloak:`), so a change to a
`-c shared_buffers=…` / `-c work_mem=…` / `-c max_connections=…` flag looks like any other
compose edit and **auto-applies on the next 5-minute tick**, recreating `db-backend` and/or
`db-keycloak` in place. That is safe — the flags are runtime settings, `PGDATA` is untouched,
and no migration is involved — but it is still a database restart, so treat it as a
maintenance moment rather than letting it land unattended:

1. **Pause the timer** before promoting, so the apply happens when you are watching:

   ```bash
   sudo systemctl stop iri-deploy.timer
   ```
2. Promote the release as usual (`gh workflow run promote.yml -f version=<version>`).
3. **Apply deliberately** and watch it through the health gate:

   ```bash
   sudo -u deploy /var/iri/code/scripts/deploy.sh --force
   ```
4. Confirm the flags actually reached the running server — a compose edit that never got
   applied has bitten this stack before:

   ```bash
   docker exec db-backend psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -p 15432 \
     -c "SHOW shared_buffers; SHOW effective_cache_size; SHOW work_mem; SHOW max_connections;"
   ```
5. **Restart the timer:**

   ```bash
   sudo systemctl start iri-deploy.timer
   ```

Expect a short connection blip: `backend` and `keycloak` are **not** recreated, so their
pools reconnect against the new server (HikariCP retries within `connection-timeout: 5000`,
Keycloak's Agroal likewise). `depends_on: service_healthy` only orders a *fresh* `up`, it
does not restart dependents when only the DB is recreated. The blip is why this belongs in a
maintenance moment even though nothing is destructive.

**Rollback** is the ordinary config rollback — promote the previous version, or pin the
config bundle back — because the flags live in `docker-compose.yml`. A health failure during
step 3 already rolls the compose file back automatically. Lowering `shared_buffers` needs no
data migration in either direction, so there is no one-way door here.

**Manual recovery, if a stack is ever stranded** (a hand-run `docker compose up`
after a networks edit, or an older `deploy.sh` without the recreate). Symptoms:
`UnresolvedAddressException` / `UnknownHostException` for `keycloak` / `db-keycloak`
in the backend/keycloak logs, a `Http5xxRateHigh` alert, and 500s on authenticated
endpoints. Recreate from a clean slate:

```bash
cd /var/iri/code
# Release the shared data nets the monitoring project holds as external:
docker compose -p iri-monitoring -f docker-compose.monitoring.yml down --remove-orphans
# App project: remove its containers AND the pinned bridges:
docker compose --profile prod down --remove-orphans
# Drop any stale-subnet bridge a stray endpoint kept alive:
docker network prune -f
# Redeploy from the clean slate (bypasses the bad-digest backoff):
sudo -u deploy /var/iri/code/scripts/deploy.sh --force
```

## Stateful-infra upgrades (operator-gated: postgres, Keycloak)

A **postgres** or **Keycloak** image change is the one carve-out that does **not**
auto-apply. A Postgres major recreated against the existing `PGDATA` bind mount
won't start without `pg_upgrade`; a Keycloak major needs host-staged provider JARs
and a keystore whose SAN carries `dns:keycloak` (see *Keycloak behind NPM* and
*Keycloak custom providers* above). A blind `up -d` would fail these and the health
gate would then roll back on a 5-minute loop.

So when a promoted bundle changes a `postgres:` or `quay.io/keycloak/keycloak:`
pin, `deploy.sh` refuses to apply it: it logs a `CARVE-OUT: …` line, writes
`/var/lib/iri/config-blocked.marker`, exits non-zero **once** (so journald /
`OnFailure=` alert you), and then skips that target quietly on subsequent ticks
until you either promote a different version or force it. To take the upgrade:

1. Perform the documented manual upgrade for that component (the Postgres major
   `pg_upgrade` runbook, or the Keycloak keystore/provider steps above).
2. Force the gated deploy through:

   ```bash
   sudo -u deploy /var/iri/code/scripts/deploy.sh --force
   ```

   `--force` bypasses the carve-out (and the bad-digest backoff) for the current
   target; the app images in the same promotion are applied along with it.

redis and npm image bumps are **not** gated — they auto-apply as in the previous
section. Every `prod` service runs the hardened runtime baseline (REQ-OPS-014:
`no-new-privileges` + `cap_drop: [ALL]` + a minimal `cap_add` + a `pids` ceiling),
and the third-party images (`postgres`, `redis`, `npm`) do not officially document a
reduced capability set — so when you bump one of them, **re-verify its `cap_add` set
against the new image before merging the bump PR**: boot the new image once with the
`security_opt`/`cap_drop`/`cap_add`/`pids` values from that service in
`docker-compose.yml` and confirm a clean boot and a passing healthcheck (for **npm**
additionally the s6 `/data/nginx` sed pass, `/usr/bin/check-health`, a working
`nginx -s reload`, and a clean restart). An upstream change (e.g. a new s6 prepare
step, or a changed privilege-drop path in the postgres/redis entrypoint) can silently
require an additional capability. The deploy health-gate is the backstop — a wrong cap
set fails the container at start and rolls back — but catching it in review is cheaper
than a rolled-back deploy.

---

## Maintenance page

While `deploy.sh` cycles `backend`, `frontend` and `ingest` out and the new
images are booting, the upstream behind `nginx-proxy-manager` (NPM) momentarily returns
`502 Bad Gateway`. NPM intercepts those failures and serves a branded
maintenance page in their place, so a user hitting the site mid-deploy sees a
deliberate "we'll be right back" screen instead of nginx's default error page.

### How it works

NPM's per-server hook (`/data/nginx/custom/server_proxy.conf`, included in
every proxy host's `server { }` block) wires up:

```nginx
error_page 502 503 504 =503 @maintenance;

location @maintenance {
    internal;
    root /usr/share/nginx/html;
    rewrite ^.*$ $maintenance_target break;

    types {
        application/problem+json json;
        text/html                html;
    }

    add_header Retry-After   "60"       always;
    add_header Cache-Control "no-store" always;
}
```

`$maintenance_target` is set by a small `map` in `/data/nginx/custom/http.conf`
that branches on the request's `Accept` header:

|                    `Accept`                    |      Response      |               Content-Type                |
|------------------------------------------------|--------------------|-------------------------------------------|
| `text/html`, `*/*`, missing                    | `maintenance.html` | `text/html; charset=utf-8`                |
| `application/json`, `application/problem+json` | `maintenance.json` | `application/problem+json; charset=utf-8` |

Both responses are returned with HTTP `503 Service Unavailable` and
`Retry-After: 60`. The HTML page auto-refreshes every 30 seconds, so a user
who lands on it during a deploy is back on the real app as soon as the new
containers pass their healthcheck. AJAX/fetch calls from the live frontend
receive an RFC 7807 `application/problem+json` document and can render their
existing "backend unreachable" toast cleanly.

### Where the files live

All assets are part of the repo and mounted into the NPM container via
`docker-compose.yml`. The static assets directory is mounted read-only;
the two nginx snippets are mounted read-write because NPM's startup
script `s6-rc.d/prepare/50-ipv6.sh` runs `sed -i` against every `*.conf`
under `/data/nginx/` to add IPv6 listeners — with `:ro` the write fails
with `EROFS` and the container refuses to boot. Our snippets contain no
`listen` directives, so `sed` produces byte-identical content; the file
is touched but unchanged.

```
docker/maintenance/
├── nginx/
│   ├── http.conf            -> /data/nginx/custom/http.conf
│   └── server_proxy.conf    -> /data/nginx/custom/server_proxy.conf
└── static/
    ├── maintenance.html     -> /usr/share/nginx/html/maintenance/maintenance.html
    └── maintenance.json     -> /usr/share/nginx/html/maintenance/maintenance.json
```

Nothing in `scripts/deploy.sh` touches these files. They are static, the
trigger is purely an upstream `5xx`, so the page appears for the exact window
between "old container gone" and "new container healthy" — the same window
`docker compose up -d --wait` is already gating on.

### Verifying after a config change

After editing any file under `docker/maintenance/`, restart the NPM container
so the new bind-mounts take effect and ask nginx to re-parse its config:

```bash
sudo -u deploy /usr/bin/docker compose \
    -f /var/iri/code/docker-compose.yml --profile prod \
    up -d npm

sudo -u deploy /usr/bin/docker compose \
    -f /var/iri/code/docker-compose.yml --profile prod \
    exec npm nginx -t          # must report "syntax is ok"
```

Then simulate an upstream failure to confirm the page is wired up:

```bash
sudo -u deploy /usr/bin/docker compose \
    -f /var/iri/code/docker-compose.yml --profile prod \
    stop frontend

curl -i https://profit-base.online/                  # expect HTTP/1.1 503 + HTML
curl -i -H 'Accept: application/json' \
        https://profit-base.online/api/v1/missions   # expect HTTP/1.1 503 + JSON

sudo -u deploy /usr/bin/docker compose \
    -f /var/iri/code/docker-compose.yml --profile prod \
    start frontend
```

The page is intentionally not scoped per virtual host — any proxy host behind
NPM (including `keycloak.profit-base.online`) will fall back to the same screen if
its upstream ever serves a `5xx`. The wording is kept generic ("System
maintenance") so it reads correctly for both.

---

## Edge rate limiting

The same two custom snippets carry a version-controlled per-IP safety net
(REQ-SEC-023) that applies to **every** proxy host:

- `docker/maintenance/nginx/http.conf` defines the shared-memory zones
  (`krt_req_perip`, `krt_conn_perip`) and the `$krt_limit_key` map: the limiter
  keys on the full IPv4 address, or an IPv6 client's `/64` network prefix.
- `docker/maintenance/nginx/server_proxy.conf` applies them in each proxy
  host's `server { }` block: **20 r/s** sustained with **burst 80** (`nodelay`)
  and at most **500 concurrent connections** per client IP.

These are flood/brute-force ceilings, not fairness limits — a worst-case page
load (~40 uncached asset requests at once) fits inside the burst, while
hammering the Keycloak login form or an API endpoint does not. Rejections use
status **429** on purpose: the nginx default (503) would be intercepted by the
maintenance-page `error_page` wiring above and a flooding client would receive
the maintenance page with the wrong semantics. Rejected requests appear in the
per-host access logs (and via `limit_req_log_level warn` in the error log), so
a sustained flood raises the `EdgeRateLimitSpike` Loki alert.

> **Note — real client IP restored (ADR-0112).** The IPv6-specific masking is
> fixed for every vhost: `net-proxy-frontend` is dual-stack (`fd00:28:3::/64`) so
> the kernel `ip6tables` DNAT preserves the client IPv6, and — because all vhosts
> share the one published `:443` ingress on NPM's `net-proxy-frontend` leg — both
> v4 and v6 clients reach nginx with their real IP on keycloak/ingest/grafana too,
> not just `profit-base.online`. `limit_conn` is tightened from the 10000 stopgap
> to **500** per client, and IPv6 is keyed on its `/64` (the `$krt_limit_key` map).
> The bridge-gateway addresses (`172.28.3.1` / `fd00:28:3::1`) that dominate those
> hosts' logs are internal hairpin traffic (blackbox probes + OIDC hairpins), not
> masked external clients — so those bridges need no IPv6 subnet. Do **not**
> disable userland-proxy. See REQ-SEC-023 / ADR-0112.

Stricter per-endpoint limits (e.g. on the Keycloak token/login paths) remain
possible per proxy host in the NPM UI's Advanced tab, referencing the same
zones — those are unversioned host state and deliberately not part of the
baseline.

Verification after changing the limits (same flow as the maintenance page —
restart NPM, `nginx -t`, then exercise it):

```bash
# a burst above rate+burst must yield 429s, not the maintenance page
for i in $(seq 1 120); do
  curl -s -o /dev/null -w '%{http_code}\n' https://profit-base.online/ &
done | sort | uniq -c        # expect a mix of 200s and 429s, no 503
```

---

## Keycloak behind NPM over HTTPS

Keycloak no longer serves plain HTTP in production. The `keycloak` service starts with
`--http-enabled=false --https-port=18443`, so **both** edges that reach it are now TLS:

- **NPM → Keycloak** — `nginx-proxy-manager` terminates the public Let's Encrypt cert for
  `keycloak.profit-base.online` and **re-encrypts** to `https://keycloak:18443` on the internal
  `net-proxy-keycloak` network.
- **backend → Keycloak** — the scheduled user sync (`KeycloakService`) reaches the same connector
  directly over the isolated `net-backend-keycloak` network via `KEYCLOAK_ADMIN_URL=https://keycloak:18443`,
  pinning the shared self-signed cert through the `keycloak-trust` SSL bundle.

The management/health interface (port 9000) is deliberately kept on **HTTP**
(`--http-management-scheme=http`): the Quarkus image ships no TLS-capable CLI client (only `java`),
and the container `HEALTHCHECK` opens a plain-HTTP socket to `/health/ready` via bash `/dev/tcp`.
That port is container-loopback only — it is never published nor placed on a proxy network.

### Prerequisite — the shared keystore must carry `dns:keycloak`

The backend keeps **hostname verification on** for the admin call (the synchronous JDK `HttpClient`
cannot disable it reliably per-client), so the cert presented on `https://keycloak:18443` must list
`keycloak` in its SAN. The shared `keystore.p12` historically did **not**. Regenerate it (this is
the only cert the internal services present to each other; NPM's public Let's Encrypt cert is
separate and untouched):

The prod host has **no JDK** (containers only), so there is no host `keytool`.
Don't install one — the backend image is `eclipse-temurin:25-jre-alpine`, which
bundles `keytool`; run it as a throwaway container with the secrets dir mounted
read-write:

```bash
cd /var/iri/code

# Keystore password (= the value in /var/iri/code/.env) and the backend image
# already on the host. Falls back to the upstream base if the stack image can't
# be resolved.
PW=$(sudo grep -E '^SERVER_SSL_KEY_STORE_PASSWORD=' .env | cut -d= -f2-)
IMG=$(sudo docker compose --profile prod config --images | grep basetool-backend | head -1)
IMG=${IMG:-eclipse-temurin:25-jre-alpine}

# Back up + remove the old keystore so keytool creates a fresh one (it refuses
# otherwise: alias 'basetool' already exists).
sudo cp /var/iri/secrets/keystore.p12 /var/iri/secrets/keystore.p12.bak
sudo rm -f /var/iri/secrets/keystore.p12

# Generate via the JRE inside the image: --user 0 so it can write into the
# root-owned secrets dir; --entrypoint keytool overrides the app entrypoint.
sudo docker run --rm --user 0 --entrypoint keytool -v /var/iri/secrets:/work "$IMG" \
  -genkeypair -alias basetool -storetype PKCS12 -keystore /work/keystore.p12 \
  -storepass "$PW" -keypass "$PW" \
  -keyalg RSA -keysize 2048 -validity 3650 \
  -dname "CN=basetool, OU=IRIDIUM, O=DAS KARTELL, C=DE" \
  -ext "san=dns:localhost,ip:127.0.0.1,dns:backend,dns:frontend,dns:ingest,dns:keycloak"

# Readable by BOTH the uid-10001 JVM services (via the group) AND the uid-1000
# Keycloak image (via a POSIX ACL), WITHOUT world-read (see "5.2 PKCS12 keystore").
# A plain 0640 with no ACL makes Keycloak crash with AccessDeniedException.
sudo chown root:10001 /var/iri/secrets/keystore.p12
sudo chmod 0640        /var/iri/secrets/keystore.p12
sudo setfacl -m u:1000:r /var/iri/secrets/keystore.p12

# Confirm the SAN carries dns:keycloak before restarting anything.
sudo docker run --rm --entrypoint keytool -v /var/iri/secrets:/work "$IMG" \
  -list -v -keystore /work/keystore.p12 -storepass "$PW" | grep -i keycloak
```

### Host steps

1. **Ship the release that contains this change.** Cut + promote a version whose `basetool-backend`
   image carries the `keycloak-trust` bundle and the `KeycloakService` TLS wiring (see
   [Normal deploy flow](#normal-deploy-flow)). An *old* backend image pointed at the HTTPS admin URL
   would fail the handshake.
2. **Update the compose file on the host** — the new `keycloak` command/volumes and the backend
   `KEYCLOAK_ADMIN_URL` live in `docker-compose.yml`, which is copied manually:

   ```bash
   sudo cp docker-compose.yml /var/iri/code/docker-compose.yml
   sudo chown deploy:docker   /var/iri/code/docker-compose.yml
   ```
3. **Regenerate the keystore** with `dns:keycloak` (previous section).
4. **Reconfigure the NPM proxy host** for `keycloak.profit-base.online` (NPM admin UI on
   `127.0.0.1:10081`): set **Forward Scheme = `https`** and **Forward Port = `18443`** (Forward
   Hostname stays `keycloak`). nginx does **not** verify the upstream certificate by default, so the
   self-signed cert is accepted with no extra toggle. The public SSL tab (Let's Encrypt) is unchanged.
5. **Apply.** First re-create the services whose compose definition / image changed (`keycloak`
   recreates for the new command + keystore mount; `backend` for the new image + `KEYCLOAK_ADMIN_URL`):

   ```bash
   sudo -u deploy /usr/bin/docker compose \
       -f /var/iri/code/docker-compose.yml --profile prod \
       up -d keycloak backend
   ```

   Then **restart `frontend` and `ingest`** so they reload the regenerated `keystore.p12`. This is
   easy to miss: the keystore is a bind mount, so `up -d` does **not** recreate these two — but the
   shared cert is loaded once at JVM start, both as each service's own HTTPS server cert *and* as its
   `backend-trust` truststore. Without the restart they keep pinning the **old** cert and every
   frontend/ingest → backend call fails with `PKIX path building failed` once the backend presents the
   new one:

   ```bash
   sudo -u deploy /usr/bin/docker compose \
       -f /var/iri/code/docker-compose.yml --profile prod \
       restart frontend ingest
   ```

   The NPM proxy-host change (step 4) is applied live by nginx on save; restarting the `npm` container
   is not required.

### Verify

```bash
# Keycloak is healthy (proves the HTTP management healthcheck still works after the HTTPS flip).
sudo -u deploy /usr/bin/docker compose -f /var/iri/code/docker-compose.yml --profile prod ps keycloak

# Public OIDC discovery still resolves through NPM (NPM → keycloak:18443 re-encryption works).
curl -fsS https://keycloak.profit-base.online/realms/iri/.well-known/openid-configuration >/dev/null && echo OK

# Backend user sync succeeds over TLS — no recurring "Failed to fetch users from Keycloak".
sudo -u deploy /usr/bin/docker compose -f /var/iri/code/docker-compose.yml --profile prod \
    logs --since 5m backend | grep -i "fetch users from keycloak" || echo "no sync errors"
```

A `PKIX path building failed` or `No subject alternative DNS name matching keycloak` in the backend
log means the keystore was not regenerated with `dns:keycloak` — redo the prerequisite. As an
emergency fallback only, revert `KEYCLOAK_ADMIN_URL` to `http://keycloak:18080` **and** drop
`--http-enabled=false` from the `keycloak` command, then re-`up`; fix the cert and switch back.

---

## Keycloak Admin Console via SSH tunnel

The Keycloak Admin Console (`https://keycloak.profit-base.online/admin`) is served on the public
vhost but must never be reachable from the open internet. It is locked to an operator SSH tunnel:
NPM allows the console **only** for connections that originate from the host itself, and the
operator reaches the host over SSH.

### How the lock-down works

The operator opens a local port-forward to NPM's published `443` through the host loopback:

```bash
ssh -N -L 443:127.0.0.1:443 root@178.104.94.14
```

and adds a hosts entry so the browser resolves the vhost to the tunnel and SNI/cert still match:

```
127.0.0.1  keycloak.profit-base.online
```

Then `https://keycloak.profit-base.online/admin` reaches the console through the tunnel.

The access control is an nginx `allow … / deny all` on the `/admin` **custom location** of the
`keycloak.profit-base.online` proxy host, configured in the NPM admin UI (`127.0.0.1:10081` →
*Proxy Hosts → keycloak → Custom locations → `/admin` → Advanced*):

```nginx
# Keycloak Admin Console — reachable only through the operator SSH tunnel.
# Host-origin traffic (the tunnel hitting NPM's published 443 via 127.0.0.1) is
# SNAT'd by Docker to the gateway of the net-proxy-* bridge, so nginx sees a
# 172.28.x.1 source, NOT the operator's real IP. External clients keep their
# real public IP and hit `deny all`. The gateways are stable because the bridge
# subnets are pinned in docker-compose.yml (172.28.0.0/16).
allow 172.28.3.1;   # net-proxy-frontend gateway
allow 172.28.4.1;   # net-proxy-keycloak gateway
allow 172.28.7.1;   # net-proxy-ingest gateway
deny all;
```

Docker decides which of NPM's three proxy-network gateways the published-port DNAT resolves to,
and that choice can differ across Docker versions or attachment order — so all three are listed.
Equivalently you may use a single `allow 172.28.0.0/16;` (the whole pinned range); both are safe
because no external client can present a `172.28.x` source over a completed TCP handshake, and NPM
does not trust an inbound `X-Forwarded-For` for `allow`/`deny` (`$remote_addr` is the real TCP
peer).

### Why the allowed IP used to change — and no longer does

Before the subnets were pinned, Docker drew each bridge's subnet from its dynamic address pool, so
a `docker compose down` / restart reassigned `net-proxy-*` a fresh subnet and moved its gateway
(e.g. `172.24.0.1` → something else). The `/admin` allow-list then matched nothing and silently
locked the console out until the new gateway was looked up by hand. The `ipam` blocks in
`docker-compose.yml` now pin every bridge to a fixed `/24` under `172.28.0.0/16`, so the gateway
the allow-list depends on is constant across restarts. **If you ever change those pinned subnets,
update this `/admin` block to match** — they move together.

> This is an operator convenience, not a security boundary on its own: the console is still behind
> Keycloak's own admin login. The IP lock-down is defence-in-depth so the admin login form is not
> even reachable from the public internet.

---

## Keycloak custom providers — Discord login SPI (epic #720)

Discord login ships as a Keycloak provider JAR built from the `keycloak-spi` module
(`DiscordIdentityProvider`, the fail-closed first-login membership gate, and the fail-open
account-existence gate). Keycloak loads it from `/opt/keycloak/providers`, bind-mounted from the host
at `/var/iri/code/keycloak/providers`.

**It is delivered automatically** (REQ-OPS-007,
[ADR-0055](adr/0055-keycloak-spi-jar-as-promotable-oci-artifact.md)). The JAR rides the same
pull-only, digest-pinned, deliberately-promoted GHCR channel as the app images and the config bundle,
as its own cosign-signed `basetool-keycloak-spi` artifact (a `FROM scratch` image carrying only
`keycloak-spi.jar`). It is a **separate** artifact from `basetool-config` because REQ-OPS-005 bars
provider JARs from the config bundle. On promotion (`promote.yml`, in lock-step with the app images +
config), the next `deploy.sh` tick resolves the `basetool-keycloak-spi:stable` digest,
cosign-verifies it, stages the JAR into `/var/iri/code/keycloak/providers/keycloak-spi.jar`, and
recreates **only** keycloak (`up -d --no-deps --force-recreate keycloak`, health-gated) so its
`start` re-runs the provider build and loads the new JAR. On a health failure the previous JAR is
restored and keycloak brought back, and the bad target backs off. A combined Keycloak-**image** +
provider-JAR change stays operator-gated by the postgres/Keycloak carve-out above (the image change
blocks the tick until `--force`).

The JAR is compiled to **Java-21 bytecode** to match the Keycloak runtime JVM; a mismatch surfaces as
`UnsupportedClassVersionError` at provider load (caught by the deploy health gate, which then rolls
the JAR back). The Discord OAuth application, the realm identity provider, the `discord_user_id`
mappers, the membership-gate flow config and the account-existence precheck env vars are one-time
operator steps in [`docs/keycloak/DISCORD_KEYCLOAK_SETUP.md`](keycloak/DISCORD_KEYCLOAK_SETUP.md). An
empty or missing providers dir is harmless (Keycloak simply finds no extra providers).

**Manual staging** (fallback, or first bootstrap before the `basetool-keycloak-spi` artifact has been
promoted):

```bash
# 1. Build the provider JAR (on a build host with the JDK 25 toolchain).
./gradlew :keycloak-spi:jar      # -> keycloak-spi/build/libs/keycloak-spi-<version>.jar

# 2. Stage it on the Keycloak host, world-readable (0644) so the uid-1000 Keycloak image can read it
#    — the same lesson as the shared keystore.p12; 0640 makes Keycloak ignore (or fail to read) it.
sudo install -D -m 0644 keycloak-spi-*.jar /var/iri/code/keycloak/providers/keycloak-spi.jar

# 3. Recreate Keycloak so the `start` command re-runs the provider build and discovers the JAR.
sudo -u deploy /usr/bin/docker compose -f /var/iri/code/docker-compose.yml --profile prod \
    up -d --no-deps --force-recreate keycloak

# 4. Verify the provider registered (no SPI load error in the log; "Discord" selectable as a Social IdP).
sudo -u deploy /usr/bin/docker compose -f /var/iri/code/docker-compose.yml --profile prod \
    logs --since 2m keycloak | grep -iE "error|exception|providers" | head
```

---

## Manual deploy / rollback

### Pin to a specific version (forward or backward)

```bash
sudo -u deploy /var/iri/code/scripts/deploy.sh --tag 1.4.2
```

Any tag the registry resolves works: `latest`, `edge`, `1.4.2`, `1.4`,
`sha-abc1234`. The script then continues to poll `:stable` on the next
timer tick — so a manual `--tag 1.4.2` is **not** persistent. If you want
a sticky rollback, also flip `:stable` itself:

```bash
gh workflow run promote.yml -f version=1.4.2
```

Now subsequent timer ticks pick up `1.4.2` as `:stable` and the rollback is
durable.

### Dry-run check

```bash
sudo -u deploy /var/iri/code/scripts/deploy.sh --check-only
```

Resolves the digest the next deploy would target **and cosign-verifies every
resolved digest** against the release-images identity (REQ-OPS-015), reporting
per artifact. No restarts, no metric written. Exits non-zero if any signature
does not verify — so this doubles as the repeatable signature preflight (it runs
cosign as the `deploy` user, exactly as a real deploy would).

### Force a fresh pull regardless of digest match

```bash
sudo rm /var/lib/iri/last-deployed.digests
sudo systemctl start iri-deploy.service
```

Useful after restoring `/var/lib/iri/` from a backup or for re-applying
after a host migration.

### Restarting the stack manually

If you ever bring the stack up by hand (after a `docker compose down`, a host
reboot with auto-start disabled, …), **always include the digest-pin overlay**:

```bash
sudo -u deploy docker compose \
    -f /var/iri/code/docker-compose.yml \
    -f /var/lib/iri/current-digest-pin.yml \
    --profile prod up -d
```

A plain `docker compose --profile prod up -d` resolves `:stable` from the
**local image cache**, which `deploy.sh` never refreshes (it always pulls by
digest) — so it can silently start an outdated build against a newer database
(schema-validation crash loop; this is exactly the 2026-07-02 incident). The
pin overlay starts the digests the last successful deploy applied. The simplest
safe restart is usually just:

```bash
sudo systemctl start iri-deploy.service
```

Since the drift verification (REQ-OPS-013), a stack started off the wrong
image — or left down or unhealthy — self-heals on the next timer tick: the
matching marker no longer short-circuits the run when the running containers
do not match the pinned digests. Corollary: for **planned downtime**, stop the
timer first, or the next tick will bring the stack back up. Stopping the timer
does **not** stop an already-running deploy — wait for the in-flight run (the
`flock` barrier blocks on the same lock `deploy.sh` holds for its whole
lifetime, covering a manual invocation too) before taking the stack down:

```bash
sudo systemctl stop iri-deploy.timer      # before the maintenance window
sudo flock /var/lock/iri-deploy.lock true # wait for an in-flight deploy run
sudo systemctl start iri-deploy.timer     # after the maintenance window
```

---

## Signature verification (cosign)

Every image the host is about to run is cosign-verified on the box before it is
pulled, extracted or applied (REQ-OPS-015, [ADR-0075](adr/0075-host-side-cosign-signature-verification.md)).
This is the **host half** of the supply-chain seam; `promote.yml`'s pre-flight
verify is the CI half. The host re-resolves `:stable` independently on every
timer tick, so the CI verify alone does not bind what `:stable` points at when
the host later pulls it — a `:stable` tag moved out-of-band (a leaked
`packages:write` credential retagging an arbitrary digest, bypassing
`promote.yml`) would otherwise be pulled and run unverified.

What `deploy.sh` does, once a tick is committed to applying (past the idempotence
no-op, `--check-only` and the bad-digest backoff) and **before** the first
`pull` / `docker create` / `docker cp` / `up`:

```
verifying image signatures (cosign keyless)
  backend: signature OK
  frontend: signature OK
  ingest: signature OK
  config: signature OK
  keycloak-spi: signature OK
```

Each `image@digest` is verified against the **release-images** workflow identity
`…/release-images.yml@refs/(heads/main|tags/v.+)` and the GitHub OIDC issuer —
the same identity `promote.yml` uses. The `@refs/` pin accepts only main-branch
and tagged builds, so an image built by a `workflow_dispatch` run of
`release-images` off an arbitrary feature branch is **not** trusted for prod.

- **Fail-closed.** A verification failure aborts the tick non-zero, records a
  deploy-failure metric (so the `DeployFailed` alert fires), and applies nothing.
  A moved `:stable` is a supply-chain incident, not a silent skip.
- cosign reads the registry credential from the same `DOCKER_CONFIG` the
  `docker login` writes; keyless verify additionally reaches the Sigstore
  public-good Fulcio/Rekor roots over the outbound HTTPS the host already uses.
- **Break-glass.** `IRI_COSIGN_VERIFY=false` disables the gate for a run — use it
  **only** to ride out a Sigstore public-good outage that is blocking every
  deploy; every skipped verification is logged as a `WARNING`. Re-enable it the
  moment Sigstore recovers:

  ```bash
  # emergency only — Sigstore outage
  sudo -u deploy IRI_COSIGN_VERIFY=false /var/iri/code/scripts/deploy.sh --force
  ```

The trusted identity is overridable for a fork via `IRI_COSIGN_REPO` /
`IRI_COSIGN_IDENTITY_REGEXP` / `IRI_COSIGN_OIDC_ISSUER` (see `deploy.sh --help`);
the defaults match this repository's `release-images.yml`.

### Updating cosign

cosign is a single static binary, so an update is an in-place replace with the
**same download + checksum-verify pattern** used at bootstrap, pointing at the new
version. No service restart is needed — the next `iri-deploy.timer` tick picks up
the new `/usr/local/bin/cosign`. Back the old binary up first so a rollback is a
single `mv`, then replace it:

```bash
sudo cp -a /usr/local/bin/cosign /usr/local/bin/cosign.bak

COSIGN_VERSION=v3.1.3
arch=$(dpkg --print-architecture)
cd /tmp
rm -f "cosign-linux-${arch}" cosign_checksums.txt        # clear any earlier attempt
curl -fsSLo "cosign-linux-${arch}" "https://github.com/sigstore/cosign/releases/download/${COSIGN_VERSION}/cosign-linux-${arch}"
curl -fsSLo cosign_checksums.txt   "https://github.com/sigstore/cosign/releases/download/${COSIGN_VERSION}/cosign_checksums.txt"
grep " cosign-linux-${arch}\$" cosign_checksums.txt | sha256sum -c -   # expect: cosign-linux-<arch>: OK
sudo install -m 0755 "cosign-linux-${arch}" /usr/local/bin/cosign     # overwrites the old binary
rm -f "cosign-linux-${arch}" cosign_checksums.txt
cosign version                                                        # expect v3.1.3
```

Chain the steps with `&&` when pasting them as a one-liner, so a failed checksum
aborts instead of falling through to `install`. Rolling back is
`sudo mv /usr/local/bin/cosign.bak /usr/local/bin/cosign`.

**Do not check the download against a differently-named file.** `sha256sum -c`
takes the filename from the checksum line (`cosign-linux-amd64`) and resolves it
against the working directory, so downloading to `/tmp/cosign` makes the check
report `No such file or directory` — a *skipped* verification, not a passed one.
That bug sat in both blocks of this runbook until 2026-08-06. It never installed
anything unverified: the skipped check still exits non-zero, so it fails the same
way a real checksum mismatch does — but only if the steps are `&&`-chained or run
one at a time with the output actually read.

Then **prove the host can verify a real CI-signed image** before relying on the
gate — verify the live `:stable` against the release-images identity (any artifact;
backend shown). cosign reads the registry credential from your docker login:

```bash
# Authenticate to GHCR (reuse the deploy pull token, or your own read:packages PAT):
sudo cat /etc/iri/ghcr-pull-token | docker login ghcr.io -u deploy-bot --password-stdin

cosign verify ghcr.io/krt-profit/basetool-backend:stable \
  --certificate-identity-regexp 'https://github.com/krt-profit/basetool/\.github/workflows/release-images\.yml@refs/(heads/main|tags/v.+)' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
```

A clean verify (the certificate + Rekor details print, exit 0) confirms the host
cosign validates the CI's signatures — this is exactly the check `deploy.sh` runs
internally (REQ-OPS-015). If it instead errors with a bundle/format complaint, the
host cosign is **older** than the version the CI signed with — bump it further.
Run `sudo -u deploy /var/iri/code/scripts/deploy.sh --check-only` as a final smoke
test — it cosign-verifies the current `:stable` as the `deploy` user without
applying anything, and exits non-zero if verification fails.

## Docker access hardening

`iri-deploy.service` runs as the unprivileged `deploy` user, but that user is in
the `docker` group so it can drive `docker compose`. Be clear-eyed about what that
bounds: **docker-group membership is effectively root on the host** — the group
can `docker run -v /:/host …` and read or overwrite anything. So the `deploy`
user is not a hard privilege boundary against a *fully compromised* `deploy.sh`.

What actually contains the risk today:

- **The host-side signature gate (REQ-OPS-015).** `deploy.sh` refuses to run any
  image not signed by our `release-images` identity, so a moved `:stable` tag or a
  stolen GHCR pull token cannot get attacker-controlled code onto the box in the
  first place — which is the realistic threat, not the operator's own script
  turning malicious.
- **The systemd sandbox** on the unit (`NoNewPrivileges`, `ProtectSystem=strict`,
  the seccomp `SystemCallFilter=@system-service`, an empty `CapabilityBoundingSet`,
  a narrow `ReadWritePaths`, restricted address families) confines the `deploy.sh`
  *process* itself.

**Deferred, would shrink the docker-group surface itself** (a larger change, not
yet done): put a **read-restricted docker-socket-proxy**
(`tecnativa/docker-socket-proxy`) in front of the daemon and point `deploy.sh` at
it via `DOCKER_HOST`, exposing only the API verbs the deploy needs; or move to
**rootless Docker**. Both re-architect how `deploy.sh` reaches the daemon and need
host validation, so they are tracked separately rather than bundled here.

## Token rotation

GHCR pull tokens are scoped to the basetool repo with `Packages: Read`
only. Rotate every 90 days, or immediately on any suspicion of leak. If the token
**expires**, you do not have to watch the calendar: `deploy.sh` publishes the
recorded expiry as `basetool_ghcr_token_expiry_timestamp`, and the
**GhcrPullTokenExpiring** alert warns ~2 weeks out (**GhcrPullTokenExpired** is
critical). The expiry alerts are **opt-in** — they only fire when a `.expiry`
sidecar records a date; a deliberately **non-expiring** token has no `.expiry`
file and is not alerted on (rotate it on your own schedule instead). Rotate:

```bash
# 1. Generate a new fine-grained PAT in GitHub (same scopes as before).

# 2. Replace the token file atomically.
sudo install -m 0600 -o deploy -g deploy /dev/stdin /etc/iri/ghcr-pull-token.new <<< 'github_pat_new_token'
sudo mv /etc/iri/ghcr-pull-token.new /etc/iri/ghcr-pull-token
# ...and update the expiry sidecar IF the token expires (skip for a non-expiring token):
echo '2027-01-01' | sudo install -m 0640 -o deploy -g deploy /dev/stdin /etc/iri/ghcr-pull-token.expiry

# 3. Force a deploy run to verify the new token works (and refresh the metric).
sudo systemctl start iri-deploy.service
tail -n 50 /var/log/iri-deploy.log      # NOT journalctl — the unit appends here instead

# 4. Revoke the old token in GitHub's PAT page only AFTER step 3 succeeded.
```

> A GitHub App installation token (auto-refreshing, no 90-day cliff) could replace
> the PAT entirely, but a pull-only host has no clean way to run the App-token
> exchange without adding a credential-refresh service — deferred as a larger
> change. The expiry alert makes the PAT's cliff non-silent in the meantime.

---

## Troubleshooting

**"Where to look" means Loki, not journald.** `deploy.sh`'s output goes to
`/var/log/iri-deploy.log` via `StandardOutput=append:`, which *replaces* journald
for that stream — `journalctl -u iri-deploy.service` shows the unit's start/exit
records and nothing the script wrote. Alloy tails that file into Loki as
`{app="ops-deploy"}` (Grafana → Explore → Loki), which is reachable without an SSH
session to the production host and is where the deploy alert annotations point.
`/var/log/iri-deploy.log` remains the on-host equivalent, and is the only place the
GHCR account name is unmasked.

|                         Symptom                          |                               Where to look                               |                                                                                                                                  Common cause                                                                                                                                   |
|----------------------------------------------------------|---------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Timer fires but image never updates                      | Loki `{app="ops-deploy"}` (or `/var/log/iri-deploy.log`)                  | `:stable` not yet promoted. Run `gh workflow run promote.yml -f version=...`.                                                                                                                                                                                                   |
| `docker login` fails                                     | Loki `{app="ops-deploy"} \|~ "logging in to\|docker login"`               | Expired or revoked PAT. See *Token rotation*. The GHCR account name is masked in Loki (REQ-OBS-004); the unmasked line is in `/var/log/iri-deploy.log`.                                                                                                                         |
| Health check times out                                   | `docker compose ps`, `docker logs <service>`                              | New version broken; the script auto-rolls back. Inspect the rolled-back container's logs for the root cause.                                                                                                                                                                    |
| Service stays "unhealthy" after rollback                 | `docker logs db-backend` etc.                                             | Infrastructure-side problem (disk full, DB corruption). Not caused by the deploy.                                                                                                                                                                                               |
| Keystore mount fails / Keycloak `AccessDeniedException`  | `docker compose logs keycloak`, `getfacl /var/iri/secrets/keystore.p12`   | Keystore missing, or the uid-1000 ACL entry is gone. The file is `0640 root:10001` (JVM services read via the group) plus `user:1000:r--` for Keycloak. Re-add it: `sudo setfacl -m u:1000:r /var/iri/secrets/keystore.p12`. A rewrite that dropped the ACL is the usual cause. |
| `IRI_KEYSTORE_HOST_PATH` referenced but file not present | `.env`                                                                    | Sync `.env` and `/var/iri/secrets/keystore.p12` between path and contents.                                                                                                                                                                                                      |
| Compose pulls but does not restart                       | Loki `{app="ops-deploy"}`                                                 | All target digests match the last-deployed digests **and** the running stack was verified against them — that is the idempotent no-op path. Force-clear `/var/lib/iri/last-deployed.digests` if you want a forced restart.                                                      |
| Stack comes back up on its own after a manual `down`     | Loki `{app="ops-deploy"} \|~ "drift:"` (`drift: <service>: no container`) | The drift verification (REQ-OPS-013) self-heals a down/drifted stack on the next tick. For planned downtime, `systemctl stop iri-deploy.timer` first and wait for an in-flight run. See *Restarting the stack manually*.                                                        |
| `CARVE-OUT: postgres/Keycloak image pin changed`         | Loki `{app="ops-deploy"} \|~ "CARVE-OUT"`, `config-blocked.marker`        | A promoted bundle bumps a Postgres/Keycloak image. Auto-apply is gated by design. Do the manual upgrade (see *Stateful-infra upgrades*), then `deploy.sh --force`.                                                                                                              |
| Redis pin bump on `main` never reaches prod              | Loki `{app="ops-deploy"}`                                                 | Not yet promoted. Cut a release and run `promote.yml` — the new compose ships as `basetool-config` and applies on the next tick. See *Infra / host-config bumps*.                                                                                                               |

---

## Why this design

A few decisions worth keeping in mind when you touch any of the pieces:

- **Pull, not push.** The server never accepts inbound connections from
  GitHub. A compromised Actions workflow or stolen `GITHUB_TOKEN` cannot
  drive code execution on the production host. The PAT on the server can
  only *read* images that were already published.
- **Digest pin between resolution and apply.** `deploy.sh` resolves
  `:stable` to concrete digests (backend + frontend + ingest), writes them
  into a compose override, and applies *that*. A `:stable` flip in GHCR mid-deploy cannot
  partially apply a half-promoted release; it would only be picked up by
  the next timer tick.
- **Verify before apply (host-side signature gate).** Resolving `:stable` to a
  digest is not the same as trusting it. `deploy.sh` cosign-verifies every
  resolved digest against the `release-images` keyless signature before it pulls
  or applies anything ([REQ-OPS-015](specs/deployment-delivery.md), [ADR-0075](adr/0075-host-side-cosign-signature-verification.md)) —
  so a `:stable` tag moved out-of-band to an untrusted digest (a leaked
  `packages:write` credential, not the operator running `promote.yml`) is
  rejected on the box, not deployed. `promote.yml`'s CI verify and this host
  verify are the two halves of one seam; the host half closes the TOCTOU between
  "verified at promote time" and "re-resolved on the next tick". See *Signature
  verification (cosign)*.
- **Health gate + auto-rollback.** `docker compose up --wait
  --wait-timeout 180` exits non-zero if any service is not healthy in
  three minutes. The script holds the previous digest pin **and** a snapshot of
  the previous config tree, and restores both before exiting non-zero, so a bad
  release self-heals to the last known-good revision within roughly five minutes.
- **Config rides the image channel, not a side channel.** The compose file and
  its asset trees ship as the signed, digest-pinned `basetool-config` artifact
  promoted in lock-step with the images ([ADR-0049](adr/0049-config-as-promotable-oci-artifact.md)).
  This keeps the host on a single read-only GHCR credential (no git key, no SSH,
  no new tool), makes the running config content-addressed instead of an untracked
  manual copy, and folds the config digest into the idempotence marker so a
  config-only change (a redis pin bump) is detected — closing the gap where the
  app-only marker silently skipped it. The stateful-infra carve-out keeps a
  Postgres/Keycloak major from being applied by a blind `up -d`.
- **No image holds the keystore.** This is checked twice — by
  `.gitignore` (CI's checkout never has the file) and by `.dockerignore`
  (no local `docker build` accidentally bakes it in). The production
  keystore lives only on the server, in a root-owned `0640` file with a POSIX
  ACL granting read to the two container uids (10001 via the group, 1000 via
  `setfacl -m u:1000:r`) — so both the JVM services and the Keycloak image read
  the shared self-signed cert without it being world-readable; root ownership
  still blocks the deploy user from rewriting it.

