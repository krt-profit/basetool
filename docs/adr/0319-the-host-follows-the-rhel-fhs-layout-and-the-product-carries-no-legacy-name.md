# ADR-0319 — The host follows the RHEL/FHS layout and the product carries no legacy name

- **Status:** Accepted
- **Date:** 2026-10-24
- **Deciders:** Repository owner (@greluc)
- **Related:** spec REQ-OPS-004, REQ-OPS-016, REQ-OPS-045
  ([`deployment-delivery.md`](../specs/deployment-delivery.md)), REQ-OPS-010, REQ-OPS-011
  ([`backup-recovery.md`](../specs/backup-recovery.md)) ·
  [ADR-0188](0188-the-host-bootstrap-is-an-ansible-role.md) (the role) ·
  [ADR-0247](0247-a-release-carries-the-hashes-of-the-host-scripts-and-the-host-checks-them.md) (script manifest) ·
  arc42 [§7](../arc42/07-deployment-view.md)

## Context

The service account, the realm, the environment keys, the units and a single tree under `/var` still carried
the name of a product that no longer exists. That tree held the release the deployer rewrites, the host
environment file, the secrets, every bind-mounted data directory and every log, so one SELinux rule
covered it and the config-tree swap and `.env` lived side by side. A
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
`var_log_t`**: logrotate may read `container_file_t` but not write it, and it rotates these files by
`copytruncate`, which writes. The
two rules of the former tree are deleted, not left beside the new ones.

**Accounts.** The service user and group are renamed to `basetool` with uid, gid and the subuid/subgid
range unchanged, so no file owned by a container uid is touched; the deploy account keeps its name, its
home moves. The rootless Podman store is **reset**, not bridged by a symlink from the old home (which would
keep the former name on the host): the three named volumes are exported, the store is reset, and the volumes
are re-created from their Quadlet units and imported.

**The Keycloak realm becomes `basetool`, a hard cutover.** The realm and its default role
(`default-roles-basetool`) are renamed in place; ids, users and
client secrets survive, every token and browser session does not, so everyone signs in again once. The
new Discord redirect URI is registered **before** and the old one removed **after** the soak. External
exchange clients are told the new issuer in advance. No fallback accepts the old issuer.

**Environment keys.** Every host environment key carries the `BASETOOL_` prefix (`BASETOOL_VERSION`),
a hard cut: the former names are read nowhere. `BASETOOL_MONITORING_DIR` splits into
`BASETOOL_MONITORING_DATA_DIR` and `BASETOOL_MONITORING_CONFIG_DIR` because data and configuration now live
in different roots.

**What stays.** The squadron IRIDIUM and its seeded data and shorthand are data, not a legacy name. Applied
Flyway migrations, accepted ADRs, the changelog archive and archived runbooks keep their text; only their
links are repaired.

**The host was migrated once, by hand-run scripts that refused rather than guessed.** They were removed
after production and testing had been migrated; the tag `v1.20.2` still carries them.

## Consequences

- The release tree is replaceable wholesale and the host configuration is not part of it; a config-bundle
  rollback can no longer take the environment file with it. The bind-mount surface per SELinux type is now
  stated per root, and `/var/log/basetool` mixes two labels deliberately.
- The deploy unit's `ReadWritePaths` names exact directories (REQ-OPS-016), never `/var/lib/basetool` as a
  whole, so the database and redis directories stay out of the sandbox.
- **Cost, accepted:** one maintenance window in which the site is down, every member signs in again,
  external clients must switch issuers, and restic snapshots taken before the cutover carry the former
  keys, paths and realm, so the current restore procedure does not apply to them; such a snapshot is
  restored with the tools of the tag `v1.20.2`.
- **Rollback is a Hetzner server snapshot taken immediately before the window** (owner decision). The
  migration had no undo phase. Rejected as
  the primary path: tested reverse phases, which would double the surface to rehearse and still not restore
  the databases or the realm.

## Alternatives considered

- **Rename in place, keep one tree under `/var`.** One SELinux rule and no config split — but it
  keeps the deployer's rewritable tree and the host secrets in one directory, which is what the split
  removes.
- **Compatibility symlink from the former home to `/home/basetool` for the Podman store.** Satisfies Podman's path
  check without a reset, but leaves the former name on the host and was never tested; the reset costs only
  three exports and an image re-pull.
- **Keep the old realm name, rename only the host.** No re-login, but the old name stays in every issuer URL,
  client configuration and runbook.
- **Accept the former environment keys as a fallback for a release.** Rejected: a silent fallback hides a
  missed rename, and the sandbox/e2e files are changed in the same release.
