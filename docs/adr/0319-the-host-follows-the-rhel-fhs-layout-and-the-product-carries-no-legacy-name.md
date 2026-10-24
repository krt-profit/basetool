# ADR-0319 — The host follows the RHEL/FHS layout and the product carries no legacy name

- **Status:** Accepted
- **Date:** 2026-10-24
- **Deciders:** Repository owner (@greluc)
- **Related:** spec REQ-OPS-004, REQ-OPS-016, REQ-OPS-045
  ([`deployment-delivery.md`](../specs/deployment-delivery.md)), REQ-OPS-010, REQ-OPS-011
  ([`backup-recovery.md`](../specs/backup-recovery.md)) ·
  [ADR-0188](0188-the-host-bootstrap-is-an-ansible-role.md) (the role) ·
  [ADR-0247](0247-a-release-carries-the-hashes-of-the-host-scripts-and-the-host-checks-them.md) (script manifest) ·
  runbook [`HOST_LAYOUT_MIGRATION_RUNBOOK.md`](../HOST_LAYOUT_MIGRATION_RUNBOOK.md) ·
  arc42 [§7](../arc42/07-deployment-view.md)

## Context

The service account, the realm, the environment keys, the units and a single tree `/var/iri` still carried
the name of a product that no longer exists. That tree held the release the deployer rewrites, the host
environment file, the secrets, every bind-mounted data directory and every log, so one SELinux rule
(`/var/iri(/.*)? container_file_t`) covered it and the config-tree swap and `.env` lived side by side. A
rename was due either way; the question was whether to rename in place or to put each kind of file where a
RHEL host expects it.

## Decision

We will rename everything and move to the FHS layout in one cutover, with no compatibility layer.

**Layout.**

| Root | Holds | Owner and mode |
|---|---|---|
| `/opt/basetool` | the release tree the deployer rewrites, `scripts/` | `deploy`, scripts `root:root 0755` |
| `/etc/basetool` | `basetool.env`, `env.d/`, `realm-export.json`, `secrets/`, `monitoring/{secrets,certs}`, `ssg-tailoring.xml` | `root:root 0755` |
| `/etc/basetool/deploy` | the deployer's credentials (`backup.env`, `rclone.conf`, pull token) | `deploy:deploy 0700` |
| `/var/lib/basetool` | `db-*`, `redis`, `backup`, `monitoring`, `textfile` | `root:root 0755` |
| `/var/lib/basetool/deploy` | the deployer's state and home | `deploy` |
| `/var/log/basetool` | host-script logs; per-service subdirectories `backend`, `frontend`, `ingest`, `keycloak` | `root:root 0755` |
| `/var/cache/basetool` | the restic cache (disposable) | `root:root 0755` |

**Release and configuration are split, and the environment file lives outside the release tree.**
`BASETOOL_COMPOSE_DIR` (default `/opt/basetool`) is the release; `BASETOOL_CONFIG_DIR` (default
`/etc/basetool`) is host configuration; `ENV_FILE` is `${CONFIG_DIR}/basetool.env`
(`BASETOOL_ENV_FILE` overrides it) and the rendered environment directory is `${CONFIG_DIR}/env.d`.
`apply_config_tree`, the config snapshot and every rollback operate on the release tree only, so **no
config apply or rollback can swap, restore or lose the environment file**; the post-apply guard now asserts
that invariant instead of detecting a loss.

**SELinux.** `container_file_t` on `/opt/basetool`, `/etc/basetool`, `/var/lib/basetool` and on
`/var/log/basetool/(backend|frontend|ingest|keycloak)`; `bin_t` on `/opt/basetool/scripts`; `etc_t` on
`/etc/basetool/deploy` and `var_lib_t` on `/var/lib/basetool/deploy`, because the deployer's credentials
and state are never mounted into a container. The **host-script logs in `/var/log/basetool` stay
`var_log_t`**: logrotate may only read `container_file_t`, and it rotates them by `copytruncate`. The
two old `/var/iri` rules are deleted, not left beside the new ones.

**Accounts.** The service user and group are renamed `iri` → `basetool` with uid, gid and the subuid/subgid
range unchanged, so no file owned by a container uid is touched; the deploy account keeps its name, its
home moves. The rootless Podman store is **reset**, not bridged by a symlink from the old home (which would
keep the old name on the host): the three named volumes are exported, the store is reset, and the volumes
are re-created from their Quadlet units and imported.

**Keycloak realm `iri` → `basetool`, a hard cutover.** The realm and its default role
(`default-roles-basetool`) are renamed in place by `scripts/rename-keycloak-realm.py`; ids, users and
client secrets survive, every token and browser session does not, so everyone signs in again once. The
new Discord redirect URI is registered **before** and the old one removed **after** the soak. External
exchange clients are told the new issuer in advance. No fallback accepts the old issuer.

**Environment keys.** `IRI_<NAME>` becomes `BASETOOL_<NAME>` (`IRI_BASETOOL_VERSION` → `BASETOOL_VERSION`),
a hard cut: the old names are read nowhere. `BASETOOL_MONITORING_DIR` splits into
`BASETOOL_MONITORING_DATA_DIR` and `BASETOOL_MONITORING_CONFIG_DIR` because data and configuration now live
in different roots.

**What stays.** The squadron IRIDIUM and its seeded data and shorthand are data, not a legacy name. Applied
Flyway migrations, accepted ADRs, the changelog archive and archived runbooks keep their text; only their
links are repaired.

**The host is migrated by scripts that refuse rather than guess.** `scripts/migrate-host-layout.sh` runs
phase by phase as root, each idempotent, `--dry-run`-able and exit-coded, and stages everything it removes
under `/root/basetool-migration`; `scripts/migrate-host-env.py` rewrites the host-local files without
printing a value. The procedure is the runbook.

## Consequences

- The release tree is replaceable wholesale and the host configuration is not part of it; a config-bundle
  rollback can no longer take the environment file with it. The bind-mount surface per SELinux type is now
  stated per root, and `/var/log/basetool` mixes two labels deliberately.
- The deploy unit's `ReadWritePaths` names exact directories (REQ-OPS-016), never `/var/lib/basetool` as a
  whole, so the database and redis directories stay out of the sandbox.
- **Cost, accepted:** one maintenance window in which the site is down, every member signs in again,
  external clients must switch issuers, and old restic snapshots carry the old keys, paths and realm. The
  runbook documents the restore of such a snapshot; the migration tools stay in the repository until the
  last of them ages out.
- **Rollback is a Hetzner server snapshot taken immediately before the window** (owner decision). The
  scripts have no undo phase; their staging directory supports a partial manual undo only. Rejected as
  the primary path: tested reverse phases, which would double the surface to rehearse and still not restore
  the databases or the realm.
- The migration tools are not in `scripts/host-scripts.list`: they run once, before the role has installed
  anything, and would otherwise sit under a drift alert for the host's life.

## Alternatives considered

- **Rename in place, keep `/var/iri` under a new name.** One SELinux rule and no config split — but it
  keeps the deployer's rewritable tree and the host secrets in one directory, which is what the split
  removes.
- **Compatibility symlink `/home/iri → /home/basetool` for the Podman store.** Satisfies Podman's path
  check without a reset, but leaves the old name on the host and was never tested; the reset costs only
  three exports and an image re-pull.
- **Keep the old realm name, rename only the host.** No re-login, but the old name stays in every issuer URL,
  client configuration and runbook.
- **Accept `IRI_*` as a fallback for a release.** Rejected: a silent fallback hides a missed rename, and the
  rewrite tool and the sandbox/e2e files are changed in the same release.
