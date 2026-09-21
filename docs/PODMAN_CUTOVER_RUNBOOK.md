# Podman cutover runbook — `ubuntu-8gb-nbg1-1` → `rocky-16gb-nbg1-1`

Doc type: **operational runbook**. The decision is [ADR-0163](adr/0163-the-container-runtime-becomes-rootless-podman-on-debian-13.md);
the analysis, the measured state and the ordering are in
[`PODMAN_MIGRATION_PLAN.md` §23](PODMAN_MIGRATION_PLAN.md). **This file carries only the steps.**

Two properties shape every step below, and both were measured rather than assumed:

1. **The data is small and the mechanism is weekly.** Under a gigabyte, moved by `backup.sh` and
   `restore-drill.sh` — the same pair that runs every night and every week in production. The
   cutover's central mechanism is not new code.
2. **There is no soak. The two hosts never serve at the same time.** The old host is shut DOWN at
   the cutover. A rollback means shutting the new one down and bringing the old one back up — on the
   configuration it already has on disk, with its deploy timer stopped so nothing promoted
   afterwards can reach it.

   That has one consequence worth stating plainly, because it is the real limit on going back:
   **the rollback is cheap only until the first write on the new host.** After that, going back
   either loses those writes or needs its own migration in the opposite direction. Decide before the
   window how long that window is assumed to last.

   It also means this repository's configuration only ever has to be right for the NEW host. Nothing
   below keeps a Docker-shaped compromise alive for the benefit of a host that will be switched off.

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

Expect `failed=0`, and on a second run `changed=0`.

> [!warning] A `--tags` run does not satisfy this gate, and it looks like it does
> Audited on `rocky-16gb-nbg1-1` 2026-09-20, after an earlier `--tags scripts,selinux,observability`
> run: the scripts were there, the SELinux labels were right, five timers were enabled — and
> `/var/lib/iri`, `/etc/iri` and `/var/iri/code` were still owned by **`iri`** rather than `deploy`,
> `/etc/iri` was `755` rather than `0700`, and `env.d`, `/var/iri/backup`,
> `/etc/containers/systemd/users/994` and the lock tmpfiles did not exist at all. Everything a tag
> selected was correct; everything it skipped was absent. The parts that make a host *look*
> provisioned and the parts the deployer actually needs are selected by different tags.

Read the gate back rather than inferring it from `changed=0` — these are the four that were wrong:

```bash
stat -c '%n %U:%G %a' /var/lib/iri /etc/iri /var/iri/code /var/iri/code/env.d /var/iri/backup
ls -d /etc/containers/systemd/users/$(id -u iri)
ls /etc/tmpfiles.d/iri-locks.conf
rpm -q restic rclone
``` The role is what puts the host in the state the
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
systemd-analyze verify \
  /etc/systemd/system/iri-{deploy,backup,restore-drill,docker-cleanup,container-metrics,cert-expiry}.service
systemctl start iri-deploy.service && systemctl show iri-deploy.service -p Result --value   # success
```

Six, not four: `iri-container-metrics` (the cAdvisor replacement) and `iri-cert-expiry` (§0.6c) are
delivered by `27-observability.yml` rather than `25-scripts.yml`, so a run that skipped the
`observability` tag leaves both behind while the other four verify cleanly.

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
them. It is **self-signed and not CA-issued**, deliberately: the edge's grafana vhost is the one
that does not `include upstream-tls.conf`, and says why — Grafana presents its own leaf, so
verifying would mean pinning something regenerated whenever the container is.

The procedure is [`MONITORING_ROLLOUT_RUNBOOK.md` §3.7](MONITORING_ROLLOUT_RUNBOOK.md), with two
changes on a rootless host — the owner uid is translated, and the SAN comes from this host's `.env`
rather than the runbook's hardcoded production domain:

> [!important] `.env` is not on the host yet, and this step reads it
> `.env` arrives **in the window**, with the restore (§1.6) — so on a host that has not cut over,
> the `grep` below returns nothing, `GH` is empty, and the certificate is minted with a SAN of
> `DNS:grafana,DNS:` . Audited on `rocky-16gb-nbg1-1` 2026-09-20: no `.env`, and the certs directory
> empty.
>
> The value is not a secret and does not have to come from `.env`: it is the public Grafana
> hostname, and `.env.example` in this repository carries it. The command below falls back to it, so
> the step works before the window and still prefers the host's own value once there is one.

```bash
cd /var/iri/monitoring/certs
GH="$(grep -m1 '^EDGE_HOST_GRAFANA=' /var/iri/code/.env 2>/dev/null | cut -d= -f2- | tr -d '"')"
GH="${GH:-grafana.profit-base.online}"                               # .env.example's value
test -n "${GH}" || { echo "no grafana hostname; refusing to mint a certificate with an empty SAN"; exit 1; }
OWNER=$(( $(grep '^iri:' /etc/subuid | cut -d: -f2) + 472 - 1 ))     # container uid 472
sudo openssl req -x509 -newkey rsa:2048 -nodes -keyout grafana.key -out grafana.crt   -subj "/CN=grafana" -addext "subjectAltName=DNS:grafana,DNS:${GH}" -days 825
sudo chown ${OWNER}:${OWNER} grafana.crt grafana.key
sudo chmod 640 grafana.key && sudo chmod 644 grafana.crt
```

Done on the testing host 2026-09-20; Grafana came up healthy and the monitoring plane reached 9/9.
**Done on `rocky-16gb-nbg1-1` 2026-09-21**: SAN `DNS:grafana, DNS:grafana.profit-base.online` from
`.env.example`'s value (no `.env` on the host yet), owner 100471, `container_file_t`, valid to
2028-12-24 — and the expiry collector picked it up on the same run.

### 0.6a `certs/basetool-ca.crt` — the one Prometheus scrapes THROUGH

Done on `rocky-16gb-nbg1-1` 2026-09-21 for the Grafana half above; **this half is still open**, and
it was not listed anywhere in these prerequisites until now. `prometheus.yml` scrapes the three JVM
apps and Keycloak over **https** with

```yaml
    tls_config:
      ca_file: /etc/prometheus/certs/basetool-ca.crt
```

and no `insecure_skip_verify`. Without that file on the host, all four application scrape targets
fail — `scrape-targets-up` catches it at §1.8, but it is a prerequisite rather than something to
discover in the window.

It is the **public** half of the shared `keystore.p12`, exported per
[`MONITORING_ROLLOUT_RUNBOOK.md` §3.6](MONITORING_ROLLOUT_RUNBOOK.md). Two things make it an
operator step rather than an automated one:

- it needs `/var/iri/secrets/keystore.p12` to be on the host already, which is part of the secrets
  that have to be carried across (§1.6 and the copy that precedes it), and
- `openssl pkcs12` prompts for the keystore password interactively, which is deliberate — passing it
  as `-storepass` / `-passin pass:` would put it in shell history.

```bash
openssl pkcs12 -in /var/iri/secrets/keystore.p12 -clcerts -nokeys \
  | openssl x509 -out /var/iri/monitoring/certs/basetool-ca.crt
# add -legacy to the pkcs12 call if OpenSSL 3.x rejects the keytool-made p12
chmod 644 /var/iri/monitoring/certs/basetool-ca.crt
restorecon -F /var/iri/monitoring/certs/basetool-ca.crt
openssl x509 -in /var/iri/monitoring/certs/basetool-ca.crt -noout -subject -ext subjectAltName
```

Once it is there the expiry collector picks it up on its next run with no further step:
`basetool_certificate_files` goes from 1 to 2 and `CertificateExpiringSoon` covers the trust anchor
that nothing serves and nothing probes.

### 0.6b `.env` carries `KC_METRICS_ENABLED=true`

The generated `env.d` template writes `KC_METRICS_ENABLED=${KC_METRICS_ENABLED:-false}` — it
**defaults to off**. Production's `.env` sets it to `true`, so a restore carries it; a host whose
`.env` lacks it silently loses Keycloak's metrics, and the management port answers `404` on
`/metrics` while looking perfectly healthy otherwise.

```bash
grep -c '^KC_METRICS_ENABLED=true' /var/iri/code/.env    # expect 1
```

### 0.6c The certificate files are being watched for expiry

Every certificate the deployment **serves** is probed by blackbox and covered by
`CertificateExpiringSoon`. A probe cannot see a certificate nothing serves, and
`/var/iri/monitoring/certs/basetool-ca.crt` is served by nothing while being the trust anchor for
every verified upstream at the edge *and* for the `https_internal` probe module. Measured on the
testing host 2026-09-20: it was the only certificate in the monitoring plane with no coverage of any
kind. `iri-cert-expiry.timer` closes that, and this step confirms it on the new host rather than
assuming the role ran.

```bash
systemctl is-enabled iri-cert-expiry.timer                       # expect: enabled
systemctl start iri-cert-expiry.service
grep -c '^basetool_certificate_expiry_timestamp_seconds' /var/iri/monitoring/textfile/certificates.prom
```

The count must equal the number of `*.crt` / `*.pem` / `*.cer` files in
`/var/iri/monitoring/certs` — at minimum `basetool-ca.crt` and `grafana.crt`, so **2**. A `0`, or a
missing file, means the alerts have no input and `CertificateMetricsStale` is what will tell you,
36 hours later.

Then read what it actually says, because a metric that exists and a metric that is right are
different claims:

```bash
awk -F'[{}]' '/^basetool_certificate_expiry_timestamp_seconds/ {print $3, $2}' \
  /var/iri/monitoring/textfile/certificates.prom |
  while read -r ts labels; do
    printf '%6d days  %s\n' $(( (ts - $(date +%s)) / 86400 )) "${labels}"
  done
```

Anything under 90 days with `self_signed="true"`, or under 14 with `self_signed="false"`, will page
the moment Prometheus scrapes — rotate it **before** the window rather than during it.

### 0.7 The hand-placed units are gone from the service user's home

> [!warning] The home directory shadows the delivered units — silently and permanently
> `podman-systemd.unit(5)` searches `~/.config/containers/systemd/` **before**
> `/etc/containers/systemd/users/$(UID)`. A unit of the same name left in the home wins, so a host
> brought up by hand keeps running its hand-placed definition and every release is ignored.

```bash
sudo ls -1 ~iri/.config/containers/systemd/ 2>/dev/null | wc -l    # expect 0
```

On a host that has them, move them aside (keep them until the new host has been running long
enough that you would not go back):

```bash
sudo -u iri mkdir -p ~iri/.config/containers/systemd.pre-cutover
sudo -u iri sh -c 'mv ~/.config/containers/systemd/* ~/.config/containers/systemd.pre-cutover/'
sudo -u iri XDG_RUNTIME_DIR=/run/user/$(id -u iri) systemctl --user daemon-reload
```

### 0.8 `restic` and `rclone` are installed, and `/etc/iri/backup.env` exists

Both come from the role (`10-packages.yml`) since 2026-09-20; before that neither was on the host
and `backup.sh` failed its pre-flight with a message naming `apt`, which does not exist on Rocky
either. `backup.env` is operator-provided: the restic repository, its password, and the rclone
remote — see [`backup.md` §4](backup.md).

```bash
rpm -q restic rclone
sudo -u deploy test -r /etc/iri/backup.env && echo readable
```

### 0.9 One backup and one restore drill have run on the Podman host

Not "the scripts exist" — one real run each. The plan is explicit that a week of production without
a working backup outweighs any dump-and-restore cycle, which makes this a cutover gate rather than
follow-up work.

> [!danger] The DRILL belongs on the new host. The BACKUP does not — not before the cutover.
> `restore-drill.sh` restores **`restic restore latest --tag basetool`**, and both hosts write to
> the same repository. So a `backup.sh` run from the new host — which has no database, because its
> data arrives with the restore in §1.4 — would push a snapshot of nothing and make it `latest`.
> The next drill would then certify that empty snapshot, and **§1.4 itself would restore it**: the
> cutover would carry no data and every check before the DNS switch would pass.
>
> What protects against this today is an accident, not a design: `backup.sh` aborts at
> `missing /var/iri/code/.env`, because `.env` also arrives with the restore. Do not rely on it.
>
> - **On the new host, run the drill only.** It needs `/etc/iri/backup.env` (and `rclone.conf`),
>   restic and rclone — not `.env` — so it runs before the cutover and proves exactly what the
>   window depends on: that this host can read that repository and restore production's latest
>   snapshot into a throwaway Postgres under rootless Podman.
> - **Leave the backup to the host that has the data.** Its nightly timer already produces the
>   snapshot §1.2 quiesces and §1.4 restores.
> - **After the cutover** the new host is the host with the data, and its own `iri-backup.timer`
>   takes over — which is when a backup from it becomes the right thing rather than the dangerous
>   one.

Check the log for the four captures that are easy to lose and easy not to notice, because each one
is a best-effort WARN by design and the backup reports success without them:

```
  edge-certs: captured
  edge-acme-webroot: captured
  capturing the redis ACL (/var/iri/redis/users.acl)
  capturing host config (.env, keystore, realm-export, providers)
```

> [!warning] A short image name silently empties the snapshot on Podman
> The helper image that streams those four out was `postgres:18-alpine`. Docker resolves a short
> name against Docker Hub; podman enforces short-name resolution and refuses without a TTY —
> `Error: short-name resolution enforced but cannot prompt without a TTY`. Every helper read failed,
> every failure was a WARN, and the backup went on to report success over a snapshot missing the
> certificates, the ACME state, the redis ACL and the keystore. Fixed by qualifying the image;
> **if you ever override `IRI_BACKUP_HELPER_IMAGE` or `IRI_DRILL_IMAGE`, qualify it fully.**

### 0.10 Rehearsed on testing, against real data

A deploy that moves a digest, showing the **re-pinned service restarted and the databases
untouched**; and a rollback from a release that cannot become healthy.

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

Run the **host-side** half of the conformance suite against the new host. The five external checks
are deliberately left out here: DNS still points at the OLD host, so they would describe that one
and say nothing about this one. They run in §1.10, once the names have moved.

```bash
python scripts/check-conformance.py --ssh root@<new-host-ip> \
  --only certificate-shared --only containers-running \
  --only redis-requires-auth --only scrape-targets-up --only container-metrics \
  --only log-streams --only trace-pipeline --only edge-not-directly-reachable \
  --only containers-unprivileged --only env-reaches-the-units --only containers-read-only
```

> [!important] `log-streams` and `trace-pipeline` are the two that would have caught 2026-09-20
> Alloy runs as a HOST service here and its configuration is written for a container, so every
> path, name and port in it is a claim about an environment it is no longer in (REQ-OBS-019). On
> the testing host that left the service `active`, its scrape target UP and 112 `alloy_*` series
> being collected while it shipped **nothing** and had never carried a single span. `log-streams`
> asserts Loki's ingest RATE rather than its presence and fails on exactly that; `trace-pipeline`
> exists because nothing else in the monitoring plane looks at the trace path at all -- there is
> no alert on either end of it. If `trace-pipeline` SKIPS here, read why:
> `MONITORING_TRACING_ENABLED` must be `true` on production, and a skip means the `.env` restored
> in §1.6 disagrees.

> [!important] `root@`, and not a login account — or eleven checks quietly say nothing
> Every one of these reads something an ordinary account cannot. The rootless containers belong to
> the service user, so `podman ps` as anyone else reports an empty host and the container checks
> report *absent* for services that are running; and `.env` is `0640 deploy:deploy`, so
> `env-reaches-the-units` cannot read it. Measured 2026-09-20 as `sysadm` against the testing host:
> nine checks failed for want of privilege and one skipped, none of it about the host.
>
> **Root was not sufficient either, until 2026-09-21, and it failed the same way.** `ssh root@<host>`
> starts in `/root`, which is `0550 root:root`. `sudo` keeps the *caller's* working directory, so
> the moment a probe reached the rootless containers — `sudo -n -u <service-user> … podman ps` —
> sudo tried to chdir there as that user and died with `cannot chdir to /root: Permission denied`.
> The runtime-detection loop swallows that, falls back to bare `podman`, and root's own podman owns
> no containers: the same nine checks, the same *absent*, a completely different cause. Measured on
> the testing host: from `/root` the probe lists nothing, from `/` it lists every container.
> `HostRunner.run` now roots every command at `/`, and 53 of the suite's own 85 assertions fail if
> that is ever removed. Nothing to do here — it is noted so a future nine-failure run is not
> misdiagnosed as a privilege problem a third time.

> [!note] `client-address-visible` is NOT in this list, and cannot be
> It issues a marked HTTPS request to the **frontend vhost name** and then greps this host's
> edge log for the marker. At this point DNS still answers with the OLD host, so the probe
> reaches that one while the grep reads this one: the marker can never appear, and the check
> fails for a reason that has nothing to do with the new host. It was listed here until
> 2026-09-21, and it would have gone red inside the window — the worst possible moment to teach
> an operator that a red check can be ignored. It runs in §1.10, after the names move, where it
> means what it says.

> [!warning] A unit the bundle has just created is ENABLED and not RUNNING
> `WantedBy=default.target` pulls a unit in when the target is ACTIVATED — at boot, or at the
> service user's first login. A unit that appears afterwards is enabled, wired into
> `default.target.wants`, and simply never started; `deploy.sh` restarts the services it is applying
> rather than starting every generated unit. Measured on the testing host 2026-09-21: `acme` had
> been installed for days, was `enabled`, and had **no journal entries at all** — it had never
> attempted to start. Started by hand it came up in twelve seconds and logged
> `ACME_HOSTS is empty — no certificates are managed on this host`, which is also why nobody
> noticed: on that host it has nothing to do.
>
> **On production it does.** `acme` is what renews the public certificate (ADR-0162), and
> `AcmeRenewalFailing` reads log lines a container that never runs never writes. `containers-running`
> above is what catches it — it did — so read that check's output rather than the container list:
> if it names a service as absent, start it (`systemctl --user start <svc>.service` as the service
> user) and re-run, rather than assuming the deploy did.

> [!note] `env-reaches-the-units` can only run HERE, and it is the reason this step exists
> Seven variables are baked into the units at generation time
> (`check-conformance.py`'s `BAKED_INTO_UNITS`), and setting one of them in the host `.env` does
> nothing unless a drop-in carries it. The `.env` arrives with the restore in §1.6 — so before this
> step there is nothing to compare, and after the DNS switch it is too late to find a disagreement.
> This is the one window in which that check is both possible and useful.

### 1.9 Only then, DNS

### 1.10 Conformance against the public names, then shut the old host down

Now the names resolve to the new host, so the whole suite means what it says — the five external
checks included:

```bash
python scripts/check-conformance.py --ssh root@<new-host-ip>
```

Expect every check to pass or to skip with a stated reason. A `rate-limit-active` skip is normal:
it is opt-in because it puts load on the target, and `--include-load` is a decision to make
deliberately rather than in a cutover window.

```bash
# new host only
systemctl start iri-deploy.timer
```

The old host is then shut down. **Leave its `iri-deploy.timer` stopped** — if it is ever brought
back up, it must come back on the configuration it has on disk and not pull a bundle promoted after
the cutover. That is what makes "bring the old one back" a complete answer rather than a race.

> [!note] What a NEWER bundle would take from the old host, and why the timer stays stopped
> Two files in the bundle are now written for the Podman shape and would be wrong on the Docker
> one. `prometheus.yml` has no `cadvisor` scrape job any more, so a resurrected old host running a
> newer bundle would have no container metrics at all — `basetool:container:*` records
> "cAdvisor-family or cgroup-family" and it would have neither. `config.alloy` reads container
> stdout from the JOURNAL rather than the Docker API (REQ-OBS-019), so that host would lose its
> `<svc>-stdout`, `mon-*`, `postgres-*` and `edge` streams; its FILE streams and both sinks are
> unaffected, because the log paths are bind-mounted on the Podman side and the Loki and Tempo
> endpoints default to the container-network names when the two `IRI_ALLOY_*` variables are unset.
>
> With its deploy timer stopped the old host keeps the bundle it already has and none of this
> reaches it — which is exactly why the timer stays stopped, and why "bring the old one back" is a
> complete answer. Bringing it back on a NEWER bundle is not a rollback path and never was.

---

## 2. The way back

1. **Shut the new host down.**
2. **Bring the old host up**, with `iri-deploy.timer` still stopped. It comes back on the
   configuration and images it had at the cutover — nothing promoted since can reach it.
3. **Revert DNS.**

The two hosts never serve at the same time, in either direction.

**What this does not recover:** anything written on the new host after the cutover. Those writes are
migrated back or accepted as lost, and that is the decision that bounds how long "just bring the old
one back" remains a real option.

---

## 3. What is knowingly left behind

Ruled by @greluc on 2026-09-17: **the 15 GB of monitoring history does not move.** Prometheus, Loki
and Tempo start empty.

The consequence is worth stating rather than discovering: **every alert whose expression looks back
over a window is blind until that window has filled.** A rule reading `[7d]` says nothing useful for
seven days. That is a deliberate, time-boxed gap in coverage, not an outage.
