# Podman cutover runbook — `ubuntu-8gb-nbg1-1` → `rocky-16gb-nbg1-1`

Doc type: **operational runbook**. The decision is [ADR-0163](adr/0163-the-container-runtime-becomes-rootless-podman-on-debian-13.md);
the analysis, the measured state and the ordering are in
[`PODMAN_MIGRATION_PLAN.md` §23](PODMAN_MIGRATION_PLAN.md). **This file carries only the steps.**

Two properties shape every step below, and both were measured rather than assumed:

1. **The data is small and the mechanism is weekly.** Under a gigabyte, moved by `backup.sh` and
   `restore-drill.sh` — the same pair that runs every night and every week in production. The
   cutover's central mechanism is not new code.
2. **Going back is a DNS change only until the first write on the new host.** After that, going back
   loses those writes or needs its own migration. The cheap rollback lives in the gap between the
   cutover and the first user action; it is not a property of the whole soak.

---

## 0. Prerequisites — none of these can be done inside the window

Each line is a gate. If one is not true, stop: the window is not the place to discover it.

### 0.1 A config bundle built from THIS branch is promoted to `:stable`

**This is the hardest prerequisite and the easiest to overlook.** Since the units ride the config
bundle, a bundle older than the units cannot carry them — and it cannot carry the configuration they
expect either.

Measured on the testing host 2026-09-20: the promoted bundle carried `05-default.conf` while the
repository had `05-default.conf.template`, which adds the `listen 127.0.0.1:8081` the edge's own
health probe targets. The edge never became healthy, and the frontend crash-looped behind it against
an unreachable Keycloak. Neither file was wrong; they were from different releases.

```bash
# The promoted bundle must contain quadlet/systemd. If this prints 0, the cutover cannot proceed.
skopeo inspect --no-tags docker://ghcr.io/krt-profit/basetool-config:stable >/dev/null && \
  echo "bundle resolves"   # then confirm the release job's presence guard passed on that build
```

> The release workflow asserts both the presence of `quadlet/systemd` and that it holds at least one
> `.container`. A green `build-config` job on a commit from this branch is the evidence.

### 0.2 The Ansible role has run against the new host, without a tag limit

```bash
cd ~/basetool-run/ansible && ansible-playbook site.yml --limit production
```

Expect `failed=0`, and on a second run `changed=0`. The role is what puts the host in the state the
deployer needs, and the following are all role-owned and were all wrong at some point in this
migration — a partial run leaves one of them behind:

| | why it matters |
|---|---|
| `/var/lib/iri`, `/etc/iri`, `/var/iri/code` owned by **deploy** | the deployer cannot write its state, read its token, or apply the config tree otherwise |
| `/var/iri/code/env.d` **setgid**, group `iri` | 18 units read it as the service user; the renderer writes it as deploy |
| `/var/iri/code/scripts` labelled **`bin_t`** | systemd refuses to execute a `container_file_t` file — `203/EXEC` on all five units |
| `/etc/containers/systemd/users/<uid>` owned by **deploy** | where a release installs the units |
| `/var/lock/iri-*.lock` via **tmpfiles** | `/run/lock` is root-only on Rocky and world-writable on Ubuntu |
| `monitoring/data/{prometheus,alertmanager,grafana,loki,tempo}` | Podman refuses a missing bind-mount source; Docker creates one |

### 0.3 The four operational units are startable

```bash
systemd-analyze verify /etc/systemd/system/iri-{deploy,backup,restore-drill,docker-cleanup}.service
systemctl start iri-deploy.service && systemctl show iri-deploy.service -p Result --value   # success
```

### 0.4 The registry token is readable by the deploy account

```bash
sudo -u deploy test -r /etc/iri/ghcr-pull-token && echo readable
cat /etc/iri/ghcr-pull-token.expiry      # not in the past
```

The token is operator-provided; the role does not write it. It must be `deploy:deploy` and `0600`.

### 0.5 The monitoring secrets exist, owned by the TRANSLATED uid

Four files under `/var/iri/monitoring/secrets/`: `scrape_password`, `prometheus_web_password`,
`prometheus-web.yml`, `alertmanager.yml` — provisioned per
[`MONITORING_ROLLOUT_RUNBOOK.md` §3](MONITORING_ROLLOUT_RUNBOOK.md).

> [!important] The owner is **not** 65534 on a rootless host
> The runbook says `chown 65534:65534`, which is right under Docker, where the container's uid is
> the host's. Under rootless Podman container uid N is host uid **`subuid_base + N - 1`**. Verified
> on `rocky-16gb-nbg1-1` 2026-09-20 — `iri:100000:65536`, the same base as the testing host:
>
> | container | host | who |
> |---|---|---|
> | 65534 | **165533** | prometheus, alertmanager, the postgres exporters |
> | 472 | **100471** | grafana |
> | 10001 | **110000** | loki, tempo, and the three app modules |
> | 70 | **100069** | the postgres clusters |
>
> Derive it, never transcribe it: `B=$(grep '^iri:' /etc/subuid | cut -d: -f2); echo $((B + 65534 - 1))`.

> [!note] The service user's own uid differs between the hosts
> 992 on testing, **994** on `rocky-16gb-nbg1-1`. It is what `/etc/containers/systemd/users/<uid>`
> is keyed by, so every path below takes it from the host rather than from this page:
> `UD=/etc/containers/systemd/users/$(id -u iri)`. The subuid BASE is the same on both, which is why
> the table above is not host-specific and this line is.

`prometheus-web.yml` is built with `htpasswd` from a throwaway container. Use **podman**:

```bash
BCRYPT="$(sudo -u iri podman run --rm docker.io/httpd:2.4-alpine htpasswd -nbBC 10 "" "${WEB_PW}" | tr -d ':\n')"
```

### 0.6 Grafana's server certificate is on the host

`grafana.container` mounts `/var/iri/monitoring/certs/grafana.{crt,key}` and will not start without
them. The host needs the pair issued by the internal CA whose certificate is already at
`/var/iri/monitoring/certs/basetool-ca.crt`. **Measured 2026-09-20: the testing host has the CA and
not the pair, and Grafana is the one monitoring unit that stays down because of it.**

### 0.7 The hand-placed units are gone from the service user's home

> [!warning] The home directory shadows the delivered units — silently and permanently
> `podman-systemd.unit(5)` searches `~/.config/containers/systemd/` **before**
> `/etc/containers/systemd/users/$(UID)`. A unit of the same name left in the home wins, so a host
> brought up by hand keeps running its hand-placed definition and every release is ignored.

```bash
sudo ls -1 ~iri/.config/containers/systemd/ 2>/dev/null | wc -l    # expect 0
```

On a host that has them, move them aside (do not delete until the soak is over):

```bash
sudo -u iri mkdir -p ~iri/.config/containers/systemd.pre-cutover
sudo -u iri sh -c 'mv ~/.config/containers/systemd/* ~/.config/containers/systemd.pre-cutover/'
sudo -u iri XDG_RUNTIME_DIR=/run/user/$(id -u iri) systemctl --user daemon-reload
```

### 0.8 Rehearsed on testing, against real data

`backup.sh` and `restore-drill.sh` under Podman, with one run against a real dump; and a deploy that
moves a digest, showing the **re-pinned service restarted and the databases untouched**.

---

## 1. In the window

### 1.1 Stop the deploy timer on BOTH hosts

It fires every five minutes and will pull an image mid-migration.

```bash
# old host
systemctl stop iri-deploy.timer && systemctl is-active iri-deploy.timer   # inactive
# new host
systemctl stop iri-deploy.timer && systemctl is-active iri-deploy.timer   # inactive
```

### 1.2 Quiesced backup on the old host

```bash
systemctl start iri-backup.service
systemctl show iri-backup.service -p Result --value      # success
```

`backup.sh` stops `frontend`, `backend` and `ingest` first, so the dump is a point in time with no
write in flight. **Note the snapshot id** — step 1.4 restores *that* one, not the newest.

### 1.3 Record the baseline, per table

Row counts per table for both databases, the Flyway count and latest version, and the realm's user,
client and credential counts. Per table, not in total: a total that matches can hide two errors that
cancel.

### 1.4 Restore on the new host, from that snapshot

> [!caution] `iri-restore-drill.service` is NOT the restore path
> `restore-drill.sh` proves recoverability: it pulls the **latest** snapshot into a **throwaway**
> Postgres and touches nothing else. Running it here would verify a different snapshot and restore
> nothing. The real procedure is [`backup.md` → *Restoring (disaster recovery)*](backup.md), and its
> commands are Docker-shaped — the Podman equivalents are below.

```bash
# 1. fetch THE snapshot from step 1.2, by id -- not `latest`
restic snapshots
restic restore <snapshot-id> --target /var/iri/backup/restore
```

```bash
# 2. host config, from .../restore/.../config/ :
#      dotenv            -> /var/iri/code/.env        (then: chown deploy:deploy, chmod 640)
#      keystore.p12      -> /var/iri/secrets/keystore.p12
#      realm-export.json -> /var/iri/code/
#      providers.tar.gz  -> extract into /var/iri/code/keycloak/
# The keystore's mode and ACL are re-applied by hand; restic does not carry POSIX ACLs.
```

> The `npm.tar.gz` step in `backup.md` is **obsolete** — Nginx Proxy Manager was retired by
> ADR-0162 and the backup captures the edge's certificate volumes instead. Restore those to the
> `edge-certs`, `edge-acme-state` and `edge-acme-webroot` volumes; see step 1.6.

```bash
# 3. databases only, then the dumps. `podman exec`, not `docker compose exec`:
IRIUID=$(id -u iri)
sudo -u iri XDG_RUNTIME_DIR=/run/user/${IRIUID} systemctl --user start db-backend.service db-keycloak.service

sudo -u iri podman exec -i db-backend sh -c \
  'PGPASSWORD="$POSTGRES_PASSWORD" dropdb   -U "$POSTGRES_USER" -h 127.0.0.1 -p 15432 --if-exists "$POSTGRES_DB"'
sudo -u iri podman exec -i db-backend sh -c \
  'PGPASSWORD="$POSTGRES_PASSWORD" createdb -U "$POSTGRES_USER" -h 127.0.0.1 -p 15432 "$POSTGRES_DB"'
sudo -u iri podman exec -i db-backend sh -c \
  'PGPASSWORD="$POSTGRES_PASSWORD" pg_restore -U "$POSTGRES_USER" -h 127.0.0.1 -p 15432 --no-owner -d "$POSTGRES_DB"' \
  < .../krt_basetool.dump

# repeat for db-keycloak on port 15433 with keycloak.dump
```

> Restore Keycloak from **`keycloak.dump`**, never from `realm-export.json` — the dump carries the
> live client secrets and users; the export only seeds an empty realm.

### 1.5 Compare against the baseline

Table-by-table row counts · Flyway count and latest version · realm users, clients, credentials.
Any difference stops the cutover; the old host is still serving.

### 1.6 Carry what a restore does not

- **Certificates**, seeded from the old host rather than re-issued. Let's Encrypt allows five
  duplicate certificates per week for this SAN set; a re-issue during a cutover spends one and a
  rollback spends another.
- **`.env`**, and afterwards `chown deploy:deploy` + `chmod 640` — the deployer reads it and
  `render-env-d.py` renders every `env.d` file from it.
- **The redis ACL** (`users.acl`).

> [!note] The uid translation applies to the restored data too
> A `pg_restore` into a cluster the container initialises itself never raises the question, which is
> exactly why the move is a restore and not a file copy: production writes that cluster as host uid
> 70, and a rootless host writes it as `subuid_base + 70 - 1` = 100069.

### 1.7 First deploy on the new host

```bash
systemctl start iri-deploy.service
systemctl show iri-deploy.service -p Result --value      # success
tail -40 /var/log/iri-deploy.log
```

Expect, in order: `container runtime: podman` · five `signature OK` · the config bundle staged ·
`quadlet units: N installed/updated, 0 retired` · `applying` · `deploy successful`.

Then confirm the release actually landed — the pin on disk is not the same claim as the running
container:

```bash
sudo -u iri podman ps --format '{{.Names}}|{{.Status}}'
sudo -u iri podman container inspect backend --format '{{.ImageName}}'
```

### 1.8 Conformance against the new host BY IP, old host still serving

The whole point of the long window is that steps 1.2–1.8 happen without time pressure, with the old
host still answering every user.

### 1.9 Only then, DNS

### 1.10 Conformance against the public names · both hosts alive · soak

Re-enable the deploy timer on the new host only:

```bash
systemctl start iri-deploy.timer
```

---

## 2. The way back

**Before the first write on the new host:** revert DNS. Both hosts are alive; the old one has never
stopped serving.

**After the first write:** the writes on the new host must be migrated back, or accepted as lost.
Decide *before* the window which of those is acceptable, and for how long the cheap rollback is
assumed to exist.

---

## 3. What is knowingly left behind

Ruled by @greluc on 2026-09-17: **the 15 GB of monitoring history does not move.** Prometheus, Loki
and Tempo start empty.

The consequence is worth stating rather than discovering: **every alert whose expression looks back
over a window is blind until that window has filled.** A rule reading `[7d]` says nothing useful for
seven days. That is a deliberate, time-boxed gap in coverage, not an outage.
