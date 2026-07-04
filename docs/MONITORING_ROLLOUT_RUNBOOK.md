# Monitoring Rollout Runbook

**Doc type:** operator runbook (living). This is the single, execution-ordered document an operator
follows to roll the Prometheus / Grafana / Loki / Tempo / Alloy observability stack onto the
single-host production Docker deployment. Everything you need is here — you should not have to open
another document to execute it.

> README's monitoring section points here: see the `MONITORING_TRACING_ENABLED` /
> `IRI_MONITORING_ENABLED` rows in [`README.md`](../README.md) and follow this runbook end-to-end.

Epic #936, ADR-0072, REQ-OBS-005..011. Compose project: `iri-monitoring`
([`docker-compose.monitoring.yml`](../docker-compose.monitoring.yml) + [`monitoring/`](../monitoring)).

---

## Table of contents

1. [Scope, host, and deployed versions](#1-scope-host-and-deployed-versions)
2. [Phase 0 — Pre-rollout: backup + pause the deploy timer](#phase-0--pre-rollout-backup--pause-the-deploy-timer)
3. [Phase 1 — Rescale to CPX42 (or confirm) + post-rescale checks](#phase-1--rescale-to-cpx42-or-confirm--post-rescale-checks)
4. [Phase 2 — Go / no-go table](#phase-2--go--no-go-table)
5. [Phase 3 — Deposit secrets, certs, data dirs](#phase-3--deposit-secrets-certs-data-dirs)
6. [Phase 4 — Data-plane wiring (Postgres users, Redis ACL, NPM, DNS, Keycloak, healthchecks.io)](#phase-4--data-plane-wiring)
7. [Phase 5 — `.env` additions](#phase-5--env-additions)
8. [Phase 6 — Promote PR 2 + manual `deploy.sh --force`](#phase-6--promote-pr-2--manual-deploysh---force)
9. [Phase 7 — Confirm the monitoring stack came up](#phase-7--confirm-the-monitoring-stack-came-up)
10. [Phase 8 — Canary checklist (as commands)](#phase-8--canary-checklist-as-commands)
11. [Phase 9 — Go-live: resume the deploy timer](#phase-9--go-live-resume-the-deploy-timer)
12. [Rollback](#rollback)
13. [Restore drill (monthly) — extended scope](#restore-drill-monthly--extended-scope)
14. [Keystore-rotation note (MUST update `docs/deployment.md`)](#keystore-rotation-note)
15. [Appendix A — UI click-path verification log](#appendix-a--ui-click-path-verification-log)

---

## 1. Scope, host, and deployed versions

**Host:** Hetzner **CPX42** — 8 vCPU / 16 GB RAM / 320 GB SSD. Deploy user is `deploy`; compose dir is
`/var/iri/code`; host data root is `/var/iri`.

**Deploy automation:** the systemd timer `iri-deploy.timer` runs `/var/iri/code/scripts/deploy.sh`
every 5 minutes. The monitoring apply is **env-gated and non-gating**: once
`IRI_MONITORING_ENABLED=true` is set in `/var/iri/code/.env`, `deploy.sh` runs
`docker compose -p iri-monitoring --project-directory /var/iri/code -f docker-compose.monitoring.yml up -d`
**after** the app stack is healthy. A monitoring failure only logs; it never rolls back the apps.

**Deployed image versions** (pinned in `docker-compose.monitoring.yml`, verified 2026-07):

|      Component      |                      Image                      |  Version  |
|---------------------|-------------------------------------------------|-----------|
| Prometheus          | `prom/prometheus`                               | `v3.13.0` |
| Grafana OSS         | `grafana/grafana-oss`                           | `13.1.0`  |
| Loki                | `grafana/loki`                                  | `3.7.3`   |
| Tempo               | `grafana/tempo`                                 | `2.10.7`  |
| Alloy               | `grafana/alloy`                                 | `v1.17.1` |
| Alertmanager        | `quay.io/prometheus/alertmanager`               | `v0.33.0` |
| node_exporter       | `quay.io/prometheus/node-exporter`              | `v1.11.1` |
| cAdvisor            | `ghcr.io/google/cadvisor`                       | `v0.60.3` |
| postgres_exporter   | `quay.io/prometheuscommunity/postgres-exporter` | `v0.20.0` |
| redis_exporter      | `oliver006/redis_exporter`                      | `v1.86.0` |
| blackbox_exporter   | `prom/blackbox-exporter`                        | `v0.28.0` |
| docker-socket-proxy | `tecnativa/docker-socket-proxy`                 | `0.4.2`   |
| github_exporter     | `ghcr.io/promhippie/github_exporter`            | `v15.0.1` |

**Adjacent products** (UI click-paths verified against these): **Keycloak 26.6**, **NPM 2.15.1**,
Grafana 13.x, Hetzner Cloud Console (current), healthchecks.io (current). Realm: `iri`. Public hosts:
`profit-base.online`, `ingest.profit-base.online`, `keycloak.profit-base.online`, and the new
`grafana.profit-base.online`.

**Run everything below as `root` (via `sudo`) unless a command is explicitly `sudo -u deploy`.**

---

## Phase 0 — Pre-rollout: backup + pause the deploy timer

Take a fresh, verified backup **before** touching anything, then freeze the 5-minute deploy loop so
your manual steps are not interleaved with an automatic tick.

```bash
# 1. Trigger a backup as the deploy user and verify it succeeded (non-zero exit == abort the rollout).
sudo -u deploy /var/iri/code/scripts/backup.sh
echo "backup exit code: $?"     # MUST be 0

# 2. Confirm the newest snapshot is present (restic → off-site). Adjust to your backup listing command.
sudo -u deploy /var/iri/code/scripts/backup.sh --list 2>/dev/null | tail -n 5 || \
  journalctl -u iri-backup.service --since "-15 min" --no-pager | tail -n 20

# 3. Pause the automatic deploy loop for the duration of the rollout.
sudo systemctl stop iri-deploy.timer
systemctl is-active iri-deploy.timer      # expect: inactive
sudo systemctl status iri-deploy.timer --no-pager | head -n 5
```

> The timer stays stopped through Phase 8. It is resumed only in [Phase 9](#phase-9--go-live-resume-the-deploy-timer).

---

## Phase 1 — Rescale to CPX42 (or confirm) + post-rescale checks

The host is **already** on CPX42, but the rescale procedure is documented here so it is repeatable and
so a fresh operator can confirm the current plan. Skip the console steps if `Phase 2`'s `free -m`
already shows ~16 GB; still run the post-rescale confirmation checks.

> **UI click-path (verified against Hetzner Cloud Console, current, 2026-07 — see Appendix A note):**
> 1. Log in to the **Hetzner Cloud Console** (`https://console.hetzner.cloud`) and open the project,
> then the server.
> 2. **Power off the server first** — the rescale action is disabled while the server is running.
> Use the server's power control (top-right power menu) → **Power off**, and wait for the state to
> show off.
> 3. Open the **Rescaling** tab in the left-hand server menu.
> 4. Pick the **CPX42** plan (shared vCPU line). Leave the **"CPU and RAM only"** toggle **OFF** if you
> want the disk to grow with the plan; **turn it ON** only if you might downgrade later.
> 5. **Disk-growing rescales are PERMANENT / not reversible** — Hetzner does not allow rescaling back
> to a smaller disk. Once you rescale with disk growth, the larger disk is one-way. (Confirm the
> warning shown in the dialog before you click **Rescale**.)
> 6. Click **Rescale**; the server restarts automatically when done, then power it back on if it does
> not restart on its own.

**Post-rescale confirmation (run on the host):**

```bash
free -m                     # expect ~16000 MB total (>= 8 GB headroom after the app stack)
df -h /var/iri              # confirm the enlarged disk is mounted where the data lives
cd /var/iri/code && docker compose ps    # every app service Up / healthy after the reboot
```

If any app container is not `Up`/healthy, resolve that before proceeding — do not layer the monitoring
rollout on top of an unhealthy app stack.

---

## Phase 2 — Go / no-go table

Do not proceed unless every row is **GO**. Gather the evidence with the commands, then fill the table.

```bash
# Disk budget vs. planned monitoring footprint.
df -h /var/iri

# Memory headroom.
free -m

# Which host-auth log source exists (drives the Alloy /hostlog scrape target and dashboard 09).
test -f /var/log/auth.log && echo "auth.log present (file-based)" || echo "no auth.log — journald only"

# SSH is key-only (password auth must be disabled on a public host before shipping the SSH dashboard).
sudo sshd -T 2>/dev/null | grep -E '^(passwordauthentication|permitrootlogin|challengeresponseauthentication)'
# expect: passwordauthentication no
```

|            Check             |              Command / evidence               |                                            Budget / expected                                            | GO / NO-GO |
|------------------------------|-----------------------------------------------|---------------------------------------------------------------------------------------------------------|------------|
| Disk free on `/var/iri`      | `df -h /var/iri`                              | **~50–60 GB free** wanted. Prometheus 180d ≈ 18–31 GB (40 GB cap), Loki 31d ≈ 8–12 GB, Tempo 14d ≈ 5 GB | ☐          |
| Memory headroom              | `free -m`                                     | **≥ 8 GB** free after the app stack (monitoring adds ≈ 1.5–2 GB of limits)                              | ☐          |
| Host-auth log source         | `test -f /var/log/auth.log`                   | `auth.log` present → file-based; else **journald** — note which, it drives Alloy                        | ☐          |
| SSH key-only                 | `sshd -T \| grep passwordauthentication`      | `passwordauthentication no`                                                                             | ☐          |
| NPM failed-login line format | verify on the **test stack** (see note below) | You can identify an NPM admin-UI failed-login log line for dashboard 08/09                              | ☐          |

> **NPM failed-login log-line format:** confirm on the isolated **test stack** (never prod) what an
> NPM 2.15.1 admin-UI failed-login line looks like in `/var/iri/npm/data/logs`, so the Loki query on
> dashboard 08 (`08-edge-npm.json`) matches real lines. Spin the test stack per the README's
> *Running the Local Test Stack* section, generate a failed login, and record the exact line shape.

---

## Phase 3 — Deposit secrets, certs, data dirs

All host secret/cert files are mounted **read-only** by `docker-compose.monitoring.yml`. Create the
directory tree first, then each file at its **exact** path. `docker-compose.monitoring.yml` itself
contains **zero secrets** — everything sensitive lives here on the host.

### 3.1 Directory tree + ownership

```bash
# Secret + cert dirs (root-owned, tight perms).
sudo mkdir -p /var/iri/monitoring/secrets /var/iri/monitoring/certs
sudo chmod 700 /var/iri/monitoring/secrets /var/iri/monitoring/certs

# Data dirs, one per stateful service, + the node_exporter textfile dir.
sudo mkdir -p /var/iri/monitoring/data/{prometheus,loki,tempo,grafana,alertmanager,alloy}
sudo mkdir -p /var/iri/monitoring/textfile

# Per-image ownership of the data dirs (the containers run as non-root users):
#   Grafana image runs as uid 472  → grafana data
#   Prometheus image runs as uid 65534 (nobody) → prometheus data
sudo chown -R 472:472     /var/iri/monitoring/data/grafana
sudo chown -R 65534:65534 /var/iri/monitoring/data/prometheus
# Loki, Tempo, Alloy, Alertmanager: leave root-owned; those images either run as root or chown their
# own volume on start. If a container logs a permission error on its data dir, chown that one dir to
# the uid printed in `docker inspect --format '{{.Config.User}}' <image>` and restart it.
sudo chown -R 65534:65534 /var/iri/monitoring/textfile   # node_exporter reads it; deploy/backup write it
```

### 3.2 `scrape_password` — the Spring apps' `/actuator/prometheus` basic-auth password

Same value goes into `.env` as `MONITORING_SCRAPE_PASSWORD` (Phase 5) and into the app secrets so the
apps and Prometheus agree.

```bash
# >= 32 random chars.
openssl rand -base64 36 | tr -d '\n' | sudo tee /var/iri/monitoring/secrets/scrape_password >/dev/null
sudo chmod 600 /var/iri/monitoring/secrets/scrape_password
# Keep it to paste into .env verbatim:
SCRAPE_PW="$(sudo cat /var/iri/monitoring/secrets/scrape_password)"; echo "scrape pw captured (len ${#SCRAPE_PW})"
```

### 3.3 `prometheus_web_password` — plaintext password for the Prometheus web (admin API) basic auth

Used by three consumers: the Grafana Prometheus datasource, Prometheus's own self-scrape
(`job_name: prometheus`), and the weekly TSDB-snapshot backup. Same value goes into `.env` as
`PROMETHEUS_WEB_PASSWORD` (Phase 5).

```bash
openssl rand -base64 36 | tr -d '\n' | sudo tee /var/iri/monitoring/secrets/prometheus_web_password >/dev/null
sudo chmod 600 /var/iri/monitoring/secrets/prometheus_web_password
WEB_PW="$(sudo cat /var/iri/monitoring/secrets/prometheus_web_password)"; echo "web pw captured (len ${#WEB_PW})"
```

### 3.4 `prometheus-web.yml` — Prometheus web config with a bcrypt hash of the above, user `grafana`

Prometheus's `--web.config.file` needs a **bcrypt** hash (not the plaintext). The username **must** be
`grafana` (that is what the datasource and the self-scrape authenticate as).

```bash
# Option A (installation-free, recommended): run htpasswd from a throwaway container — no host
# package needed. -b takes the password as an arg from the ${WEB_PW} VARIABLE (so the plaintext is
# NOT echoed into shell history); -B = bcrypt, -C 10 = cost. Output is ":$2y$10$..."; strip the ':'.
BCRYPT="$(docker run --rm httpd:2.4-alpine htpasswd -nbBC 10 "" "${WEB_PW}" | tr -d ':\n')"

# Option B (if you'd rather install a host tool): apt install -y apache2-utils, then:
#   BCRYPT="$(htpasswd -nBC 10 "" | tr -d ':\n')"   # prompts twice; enter ${WEB_PW} both times

# Write the web config. Note the exact YAML shape Prometheus expects:
sudo tee /var/iri/monitoring/secrets/prometheus-web.yml >/dev/null <<EOF
basic_auth_users:
  grafana: ${BCRYPT}
EOF
sudo chmod 600 /var/iri/monitoring/secrets/prometheus-web.yml
echo "prometheus-web.yml written; hash: ${BCRYPT:0:7}..."
```

> Sanity: the hash must start with `$2y$10$` (htpasswd) or `$2b$10$` (python bcrypt). If your shell
> ate the `$` signs, re-run inside single quotes or paste the hash by hand.

### 3.5 `alertmanager.yml` — rendered from the template with `envsubst`

Alertmanager does **not** expand environment variables, so the committed
[`monitoring/alertmanager/alertmanager.yml.tmpl`](../monitoring/alertmanager/alertmanager.yml.tmpl) is
rendered on the host. Alert e-mails never contain user data. SMTP is 587 STARTTLS with
`smtp_require_tls: true`.

> **Order note:** this render needs two inputs you obtain later — the SMTP credentials and the **healthchecks.io ping URL** created in [Phase 4.7](#47-healthchecksio--watchdog-heartbeat-check). Create that check first, or come back and **re-run this render** once you have the URL. If the monitoring stack is already running, restart Alertmanager afterwards so it reloads the file: `docker compose -p iri-monitoring -f /var/iri/code/docker-compose.monitoring.yml up -d --force-recreate alertmanager`.

```bash
# Fill these from your SMTP provider + the healthchecks.io ping URL (created in Phase 4.7).
export SMTP_SMARTHOST='smtp.example.net:587'
export SMTP_FROM='monitoring@profit-base.online'
export SMTP_AUTH_USERNAME='monitoring@profit-base.online'
export SMTP_AUTH_PASSWORD='<smtp app password>'
export ALERT_EMAIL_TO='ops@profit-base.online'
export HEARTBEAT_URL='https://hc-ping.com/<your-check-uuid>'   # from Phase 4.7

envsubst < /var/iri/code/monitoring/alertmanager/alertmanager.yml.tmpl \
  | sudo tee /var/iri/monitoring/secrets/alertmanager.yml >/dev/null
sudo chmod 600 /var/iri/monitoring/secrets/alertmanager.yml

# Validate the RENDERED file with amtool from the pinned Alertmanager image.
docker run --rm \
  -v /var/iri/monitoring/secrets/alertmanager.yml:/cfg.yml:ro \
  --entrypoint amtool quay.io/prometheus/alertmanager:v0.33.0 check-config /cfg.yml
# expect: "Checking '/cfg.yml'  SUCCESS" and no unresolved ${...} placeholders.

# Immediately unset the SMTP secrets from your shell environment.
unset SMTP_SMARTHOST SMTP_FROM SMTP_AUTH_USERNAME SMTP_AUTH_PASSWORD ALERT_EMAIL_TO HEARTBEAT_URL
```

> If `amtool check-config` fails, do **not** ship — a bad config means alerts silently do not route.
> Grep the rendered file for a stray `${` to catch an env var you forgot to export.

### 3.6 `github_token` — fine-grained read-only GitHub PAT

A **fine-grained** PAT, read-only, scoped to exactly the three tracked repos, no write scopes.

> **UI click-path (verified against GitHub fine-grained PAT UI, current):**
> GitHub → your avatar → **Settings** → **Developer settings** → **Personal access tokens** →
> **Fine-grained tokens** → **Generate new token**. Set **Resource owner** to `krt-profit`,
> **Repository access → Only select repositories** → pick `krt-profit/basetool`,
> `krt-profit/basetool-sc-extractor`, `krt-profit/design-system`. Under **Repository permissions**
> grant **read-only**: *Metadata* (Read), *Issues* (Read), *Pull requests* (Read),
> *Actions* (Read). Grant **no** write permissions. Generate and copy the token once.

```bash
printf '%s' 'github_pat_...' | sudo tee /var/iri/monitoring/secrets/github_token >/dev/null
sudo chmod 600 /var/iri/monitoring/secrets/github_token
```

### 3.7 `certs/basetool-ca.crt` — public cert exported from the shared keystore

Prometheus validates the apps' self-signed HTTPS against this CA (no `insecure_skip_verify`). Export
the **public** cert only from the shared `keystore.p12` at `/var/iri/secrets/keystore.p12`.

`openssl` reads PKCS12 directly, so **no `keytool`/JRE install** is needed. It prompts for the
keystore password — do **not** pass it on the command line (`-storepass` / `-passin pass:` would leak
it into shell history). The `openssl x509` pipe strips the PKCS12 bag attributes to clean PEM.

```bash
openssl pkcs12 -in /var/iri/secrets/keystore.p12 -clcerts -nokeys \
  | openssl x509 -out /var/iri/monitoring/certs/basetool-ca.crt
# Prompt: "Enter Import Password:" -> type the keystore password.
# If OpenSSL 3.x rejects the keytool-made p12 ("error:0308010C ... unsupported"), add -legacy:
#   openssl pkcs12 -legacy -in /var/iri/secrets/keystore.p12 -clcerts -nokeys | openssl x509 -out /var/iri/monitoring/certs/basetool-ca.crt
sudo chmod 644 /var/iri/monitoring/certs/basetool-ca.crt

# Sanity: it is a certificate and carries the app SANs (backend/frontend/ingest).
openssl x509 -in /var/iri/monitoring/certs/basetool-ca.crt -noout -subject -ext subjectAltName
```

### 3.8 `certs/grafana.crt` + `grafana.key` — Grafana's OWN self-signed cert

NPM terminates the public Let's Encrypt cert and re-encrypts upstream to Grafana over HTTPS; NPM does
not verify the upstream cert, so a self-signed one with `SAN dns:grafana` is fine.

```bash
cd /var/iri/monitoring/certs
sudo openssl req -x509 -newkey rsa:2048 -nodes \
  -keyout grafana.key -out grafana.crt \
  -subj "/CN=grafana" \
  -addext "subjectAltName=DNS:grafana,DNS:grafana.profit-base.online" \
  -days 825
# Grafana runs as uid 472 and must read both files.
sudo chown 472:472 grafana.crt grafana.key
sudo chmod 640 grafana.key
sudo chmod 644 grafana.crt
```

### 3.9 Verify the deposit

```bash
sudo ls -la /var/iri/monitoring/secrets /var/iri/monitoring/certs
# Expect exactly:
#   secrets/ : scrape_password  prometheus_web_password  prometheus-web.yml  alertmanager.yml  github_token
#   certs/   : basetool-ca.crt  grafana.crt  grafana.key
```

---

## Phase 4 — Data-plane wiring

Create the accounts, ACLs, edge rules, DNS, IdP client, and heartbeat the monitoring stack depends on.
**Sequence the NPM `/actuator` deny (4.3) BEFORE depositing the `MONITORING_SCRAPE_*` values into the
app `.env`,** so the credentialed metrics endpoint is only ever reachable from the internal scrape net.

### 4.1 PostgreSQL monitoring users (BOTH DBs)

Use psql's `\password` so the password never lands in shell history or the PostgreSQL log. Create a
`monitoring` user with only `pg_monitor` in **each** database. The exporters connect over the isolated
`net-db-*` nets with `sslmode=disable`.

```bash
# --- backend DB ---
# Wrap in `sh -c '...'` with SINGLE quotes so $POSTGRES_USER/$POSTGRES_DB expand INSIDE the container
# (where they are set), not in your host shell (where .env is not loaded — it would fall back to the
# OS user "root" and fail with `role "root" does not exist`).
cd /var/iri/code
docker compose --profile prod exec db-backend \
  sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -p 15432'
```

```sql
-- inside psql (backend):
CREATE USER monitoring;
GRANT pg_monitor TO monitoring;
\password monitoring        -- type the PG_EXPORTER_BACKEND_PASSWORD when prompted (twice)
\q
```

```bash
# --- keycloak DB (different container / db / port) ---
# Inside the db-keycloak container the superuser vars are STILL named POSTGRES_USER/POSTGRES_DB
# (the compose maps KC_POSTGRES_* onto them), so the same single-quoted form works.
docker compose --profile prod exec db-keycloak \
  sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -p 15433'
```

```sql
-- inside psql (keycloak):
CREATE USER monitoring;
GRANT pg_monitor TO monitoring;
\password monitoring        -- type the PG_EXPORTER_KEYCLOAK_PASSWORD when prompted (twice)
\q
```

> The two passwords may differ; each goes into `.env` (`PG_EXPORTER_BACKEND_PASSWORD` /
> `PG_EXPORTER_KEYCLOAK_PASSWORD`). `pg_monitor` grants read of pg_stat_* views only — no table data.

### 4.2 Redis ACL file `/var/iri/redis/users.acl`

A dedicated **read-only monitoring** user. **Do NOT add a `default` user line** — omitting it leaves
the existing `--requirepass` governing the `default` user the apps use. The monitoring user must be
able to run only introspection commands and **must not** be able to read arbitrary keys: the session
store holds OAuth2 **refresh tokens**.

> **This file MUST exist before the deploy that adds `--aclfile`.** If Redis starts with `--aclfile`
> pointing at a missing/invalid file, prod Redis fails to start, the deploy health-gate trips, and the
> app deploy rolls back. Create the file now.

```bash
sudo mkdir -p /var/iri/redis
# Canonical oliver006/redis_exporter monitoring ACL (README). Replace REDIS_EXPORTER_PASSWORD with the
# real password (same value goes into .env as REDIS_EXPORTER_PASSWORD).
sudo tee /var/iri/redis/users.acl >/dev/null <<'EOF'
user monitoring on >REDIS_EXPORTER_PASSWORD -@all +@connection +@read +client +config|get +info +latency +slowlog +memory +cluster|info +cluster|slots +cluster|nodes +xinfo +pfcount -keys sanitize-payload
EOF
sudo chmod 640 /var/iri/redis/users.acl
```

Then replace the placeholder with the actual password (kept out of shell history via a heredoc edit):

```bash
sudo sed -i "s/>REDIS_EXPORTER_PASSWORD/>$(openssl rand -base64 30 | tr -d '/+=\n')/" /var/iri/redis/users.acl
# Record THAT value for .env (REDIS_EXPORTER_PASSWORD) — read it back from the file:
sudo grep -oP '(?<=>)[^ ]+' /var/iri/redis/users.acl   # this is REDIS_EXPORTER_PASSWORD for .env
```

> Note `+@read` combined with `-keys` and `sanitize-payload`: the exporter can run `INFO`/`SLOWLOG`/
> `MEMORY` etc. but **cannot** list keys or dump arbitrary key values. After the stack is up, verify
> the exporter authenticated cleanly: `docker logs iri-monitoring-redis-exporter-1 2>&1 | tail`.
> `IRI_REDIS_ACL_HOST_PATH=/var/iri/redis/users.acl` is set in `.env` (Phase 5) so the app compose
> mounts it and passes `--aclfile`.

### 4.3 NPM — deny `/actuator` on both public app hosts

Block the credentialed Actuator endpoints from the public internet on **both** `profit-base.online`
and `ingest.profit-base.online`. Do this **before** the scrape credentials go live (Phase 5), so the
only path to `/actuator/prometheus` is the internal scrape net.

> **UI click-path (verified against Nginx Proxy Manager 2.15.1 — see Appendix A note):**
> NPM admin UI → **Hosts** → **Proxy Hosts** → click the existing `profit-base.online` host → the
> **Edit** dialog → **Advanced** tab → **Custom Nginx Configuration** text area. Add the location
> block below, **Save**. Repeat for `ingest.profit-base.online`.

```nginx
location /actuator { return 403; }
```

Verify from an external network (not the host) that both return 403:

```bash
curl -s -o /dev/null -w '%{http_code}\n' https://profit-base.online/actuator/health          # expect 403
curl -s -o /dev/null -w '%{http_code}\n' https://ingest.profit-base.online/actuator/health   # expect 403
```

### 4.4 NPM — new proxy host `grafana.profit-base.online`

> **UI click-path (verified against Nginx Proxy Manager 2.15.1 — see Appendix A note):**
> NPM admin UI → **Hosts** → **Proxy Hosts** → **Add Proxy Host**.
> - **Details** tab: **Domain Names** = `grafana.profit-base.online`; **Scheme** = `https`;
> **Forward Hostname / IP** = `grafana`; **Forward Port** = `3000`; toggle **Block Common Exploits**
> **ON**. (Leave **Cache Assets** off; **Websockets Support** optional.)
> - **SSL** tab: **SSL Certificate** = **Request a new SSL certificate** (Let's Encrypt); tick **Force
> SSL** and **HTTP/2 Support**; agree to the Let's Encrypt ToS; **Save**.
>
> NPM does not verify the upstream (self-signed) Grafana cert, so no extra upstream-verify setting is
> needed. The DNS A record (4.5) must resolve **before** the Let's Encrypt HTTP-01 challenge, or the
> cert request fails — create the DNS record first if Let's Encrypt errors.

### 4.5 DNS — A record for Grafana

> **UI click-path (generic — verify against your DNS/registrar's console):**
> Open your DNS provider's zone editor for `profit-base.online` → **Add record** → **Type = A** →
> **Name/Host = `grafana`** (so the FQDN becomes `grafana.profit-base.online`) → **Value/Points to =
> the host's public IPv4** → **TTL** default. Save. (Add a matching **AAAA** record if the host has a
> public IPv6 and your other hosts use one.)

```bash
# Verify resolution before the NPM Let's Encrypt request.
dig +short grafana.profit-base.online A     # must return the host's public IP
```

### 4.6 Keycloak — OIDC client `grafana` + realm-role mapper

> **UI click-path (verified against Keycloak 26.6 admin console):**
> Admin console → select realm **`iri`** (top-left realm switcher) → **Clients** → **Create client**.
> - **General settings**: **Client type** = **OpenID Connect**; **Client ID** = `grafana`. Next.
> - **Capability config**: **Client authentication** = **On** (confidential); enable **Standard flow**. Next.
> - **Login settings**: **Valid redirect URIs** = `https://grafana.profit-base.online/login/generic_oauth`;
> **Web origins** = `+`. Save.
> - **PKCE — nothing to do here; skip it.** The per-client "Proof Key for Code Exchange Code Challenge Method" field is only shown for **public** clients (Client authentication = Off) in current Keycloak 26.x; this `grafana` client is **confidential**, so the field is absent from its Advanced tab — that is expected, not a bug. PKCE still applies anyway: Grafana sends it (`GF_AUTH_GENERIC_OAUTH_USE_PKCE=true`) and Keycloak honours S256 by default. Verified against the Keycloak 26.x admin console.
> - **Credentials** tab → copy the **Client secret** → this is `GRAFANA_OAUTH_CLIENT_SECRET` (Phase 5).

**Role mapper — CRUCIAL** (without it every user is locked out under `role_attribute_strict`):

> **UI click-path (verified against Keycloak 26.6 admin console):**
> Clients → `grafana` → **Client scopes** → the client's **dedicated** scope (`grafana-dedicated`) →
> **Add mapper** → **By configuration** → **User Realm Role**.
> - **Name**: `realm roles`
> - **Multivalued**: **On**
> - **Token Claim Name**: `realm_access.roles`
> - **Add to ID token**: **On** ← the default realm-role mapper only adds roles to the **access**
> token. Grafana evaluates `role_attribute_path` against the **ID token** / userinfo first, so
> without "Add to ID token" ON, `role_attribute_strict: true` denies every login.
> - **Add to access token**: On (leave as-is); **Add to userinfo**: On (belt-and-braces). Save.

Grafana's `GF_AUTH_GENERIC_OAUTH_ROLE_ATTRIBUTE_PATH` is
`contains(realm_access.roles[*], 'Admin') && 'GrafanaAdmin' || ''` with `ROLE_ATTRIBUTE_STRICT=true`
(see `docker-compose.monitoring.yml`), so only realm-role **`Admin`** users get in, as GrafanaAdmin.

> **Record the denial event type on the test stack.** On the isolated test stack, log in to Grafana as
> a **non-`Admin`** user, then read Keycloak's `/metrics` on the management port and note which
> `keycloak_user_events_total{event=...}` value the denied/precheck flow emits (the event-metrics
> counter is tagged `event`, `error`, `realm`, `client_id`, `idp`). Record that `event`/`error` label
> here for the operator, e.g. `keycloak_user_events_total{event="login", error="..."}`, so the
> membership-gate denial is recognisable on dashboard 06 (`06-keycloak.json`).

### 4.7 healthchecks.io — Watchdog heartbeat check

The always-firing Alertmanager **Watchdog** alert is routed to healthchecks.io as a dead-man's switch:
if the stack or host dies, the pings stop and healthchecks.io alerts externally.

> **UI click-path (verified against healthchecks.io, current — see Appendix A note):**
> Create a **free account** at `https://healthchecks.io` (up to 20 checks). On the **Checks** page,
> click **Add Check** → give it a name (e.g. `basetool-alertmanager-watchdog`) → set the **Period** to
> ~1–2 min and a **Grace** of a few minutes (the Watchdog repeats every 1 min per the route config) →
> **Save**. Open the check → copy its **Ping URL** (`https://hc-ping.com/<uuid>`). Paste that into
> `HEARTBEAT_URL` when rendering `alertmanager.yml` (Phase 3.5). Do **not** re-render alertmanager.yml
> now if you already did Phase 3.5 with the real URL; if you used a placeholder, re-run Phase 3.5.

---

## Phase 5 — `.env` additions

Append the following to `/var/iri/code/.env`. Values in `< >` are placeholders — paste the real ones
you generated above. **The `MONITORING_SCRAPE_PASSWORD` must equal the `scrape_password` file
(3.2); `PROMETHEUS_WEB_PASSWORD` must equal the `prometheus_web_password` file (3.3); the Redis and PG
exporter passwords must equal what you set in 4.1/4.2.**

```bash
sudo tee -a /var/iri/code/.env >/dev/null <<'EOF'

# --- Monitoring (epic #936, ADR-0072) ---------------------------------------
# App-side scrape auth for /actuator/prometheus (username is not a secret).
MONITORING_SCRAPE_USER=monitoring
MONITORING_SCRAPE_PASSWORD=<same as /var/iri/monitoring/secrets/scrape_password>
# App tracing → Alloy OTLP.
MONITORING_TRACING_ENABLED=true
MONITORING_OTLP_ENDPOINT=http://alloy:4318/v1/traces
# Keycloak metrics + user-event metrics + tracing → Alloy OTLP (gRPC).
KC_METRICS_ENABLED=true
KC_EVENT_METRICS_USER_ENABLED=true
KC_TRACING_ENABLED=true
KC_TRACING_ENDPOINT=http://alloy:4317
# Grafana break-glass admin (seeds first boot only) + Keycloak OIDC.
GRAFANA_ADMIN_PASSWORD=<strong>
GRAFANA_OAUTH_CLIENT_ID=grafana
GRAFANA_OAUTH_CLIENT_SECRET=<from Keycloak Credentials tab>
GRAFANA_ROOT_URL=https://grafana.profit-base.online/
# Prometheus web (admin API) basic-auth password (datasource + self-scrape + backup snapshot).
PROMETHEUS_WEB_PASSWORD=<same as /var/iri/monitoring/secrets/prometheus_web_password>
# postgres_exporter (per DB) + redis_exporter dedicated read-only users.
PG_EXPORTER_USER=monitoring
PG_EXPORTER_BACKEND_PASSWORD=<from 4.1 backend \password>
PG_EXPORTER_KEYCLOAK_PASSWORD=<from 4.1 keycloak \password>
REDIS_EXPORTER_USER=monitoring
REDIS_EXPORTER_PASSWORD=<from 4.2 users.acl>
IRI_REDIS_ACL_HOST_PATH=/var/iri/redis/users.acl
# Master gate: deploy.sh applies the iri-monitoring project once this is true.
IRI_MONITORING_ENABLED=true
EOF
```

Verify (no unresolved placeholders left):

```bash
sudo grep -nE '<[^>]+>' /var/iri/code/.env && echo "!! placeholders remain — fix before deploy" || echo "no placeholders"
```

> Do **not** set `IRI_MONITORING_ENABLED=true` until you are ready for the deploy in Phase 6 — but
> since the timer is paused (Phase 0), setting it now is safe; nothing applies until you run
> `deploy.sh` manually.

---

## Phase 6 — Promote PR 2 + manual `deploy.sh --force`

Merge the config PR (**PR 2**) so the release bundle that `deploy.sh` pulls carries
`docker-compose.monitoring.yml` and the `monitoring/` config tree. `deploy.sh` snapshots and applies
both from the bundle (`stage_config_snapshot` / `apply_config_tree` copy them onto
`/var/iri/code`).

Then run one manual deploy as the deploy user. `--force` bypasses the postgres/Keycloak carve-out gate
so the app stack is recreated in one pass to pick up the new env vars (`MONITORING_SCRAPE_*`,
`KC_*`, tracing), the new/shared networks (`net-monitoring-scrape`, the external data nets), the Redis
`--aclfile` command change, and the Keycloak metrics/tracing flags.

```bash
# 1. Merge PR 2 on GitHub (config bundle). Confirm the new :stable image / bundle is published.

# 2. Apply manually. This recreates backend/frontend/ingest/keycloak/DBs/redis for the env/network/
#    command changes; the maintenance page shows during the swap. Keycloak 26 persistent sessions
#    survive the restart; in-flight (mid-redirect) logins do not.
sudo -u deploy /var/iri/code/scripts/deploy.sh --force 2>&1 | tee /tmp/iri-deploy-monitoring.log
echo "deploy exit: ${PIPESTATUS[0]}"     # MUST be 0
```

> If the app deploy fails and rolls back, the **most likely** cause is a missing/invalid
> `/var/iri/redis/users.acl` (Phase 4.2) — Redis will not start with a broken `--aclfile`. Fix the
> file and re-run. The monitoring apply step is non-gating and runs only after the app stack is
> healthy, so a monitoring problem will **not** roll back the apps.

---

## Phase 7 — Confirm the monitoring stack came up

```bash
docker compose -p iri-monitoring -f /var/iri/code/docker-compose.monitoring.yml ps
# Expect every service present and Up (Prometheus has NO healthcheck by design — its liveness is the
# self-scrape `up` metric): prometheus grafana loki tempo alloy alertmanager node-exporter cadvisor
# socket-proxy postgres-exporter-backend postgres-exporter-keycloak redis-exporter blackbox-exporter
# github-exporter.

# Tail the deploy log for the non-gating monitoring apply lines:
grep -E 'monitoring:|monitoring stack' /tmp/iri-deploy-monitoring.log
```

If a container is restarting, read its log (`docker logs <name>`); the most common first-boot issues
are a data-dir ownership mismatch (Phase 3.1) or a malformed secret (Phase 3.4/3.5).

---

## Phase 8 — Canary checklist (as commands)

Run every item; each must pass before go-live. Commands assume you run them **on the host**. For the
auth'd Prometheus, use a container **on `net-monitoring-core`** so DNS name `prometheus` resolves and
you can present the web password.

### 8.1 All Prometheus targets UP

```bash
WEB_PW="$(sudo cat /var/iri/monitoring/secrets/prometheus_web_password)"
docker run --rm --network net-monitoring-core curlimages/curl:8.11.1 \
  -su "grafana:${WEB_PW}" 'http://prometheus:9090/api/v1/targets?state=active' \
  | tr ',' '\n' | grep -E '"job"|"health"'
# Expect every target "health":"up". Any "down" → open that target's `lastError`. Alternatively open
# Grafana → dashboard "13 Meta-monitoring" and confirm all targets green.
```

### 8.2 promtool / amtool / alloy config checks (already green in CI, re-confirm rendered)

```bash
# amtool against the RENDERED alertmanager config (Phase 3.5 already did this):
docker run --rm -v /var/iri/monitoring/secrets/alertmanager.yml:/cfg.yml:ro \
  --entrypoint amtool quay.io/prometheus/alertmanager:v0.33.0 check-config /cfg.yml

# promtool against the committed prometheus config + alert rules:
docker run --rm -v /var/iri/code/monitoring/prometheus:/p:ro \
  --entrypoint promtool prom/prometheus:v3.13.0 check config /p/prometheus.yml

# alloy config check:
docker run --rm -v /var/iri/code/monitoring/alloy:/a:ro \
  grafana/alloy:v1.17.1 fmt /a/config.alloy >/dev/null && echo "alloy config parses"
```

### 8.3 Fire a test alert; confirm the mail + healthchecks.io green

```bash
# Add a transient alert straight into Alertmanager from a core-net container.
docker run --rm --network net-monitoring-core --entrypoint amtool \
  quay.io/prometheus/alertmanager:v0.33.0 \
  --alertmanager.url=http://alertmanager:9093 \
  alert add alertname="RolloutCanary" severity="critical" \
  --annotation=summary="monitoring rollout canary — please ignore"
# → within group_wait (0s for critical) a mail should arrive at ALERT_EMAIL_TO.
# (Alternative: temporarily lower a threshold in an alert rule + `curl -X POST .../-/reload`, then revert.)
```

- [ ] **Test alert mail received** at `ALERT_EMAIL_TO`.
- [ ] **healthchecks.io check shows GREEN** (the Watchdog is pinging `HEARTBEAT_URL` every ~1 min).

### 8.4 One prod trace visible in Grafana Explore (Tempo)

> **UI click-path (verified against Grafana 13.x):**
> Grafana → **Explore** (compass icon) → datasource **Tempo** → **Query type** = **Search** → **Run
> query** (or enter a TraceQL like `{}` to list recent traces). Confirm at least one recent trace from
> `basetool-backend` / `basetool-frontend` / `basetool-ingest` (or Keycloak) appears; click it to open
> the trace view.

### 8.5 One NPM access line + one host auth line visible in Loki

> **UI click-path (verified against Grafana 13.x):**
> Grafana → **Explore** → datasource **Loki** → run `{app="npm-access"}` (NPM access log; NPM
> error log is `{app="npm-error"}`, NPM container stdout is `{app="npm"}`) and `{app="host-auth"}`
> (the label `config.alloy` assigns to `/hostlog/auth.log`) → confirm at least one recent line
> each. (If the host uses journald not `auth.log` — see Phase 2 — confirm the label of the
> journald-sourced Alloy variant instead.)

### 8.6 Grafana OIDC login: Admin in, non-Admin denied

> **UI click-path (verified against Grafana 13.x + Keycloak 26.6):**
> Open `https://grafana.profit-base.online/` → click **Sign in with Keycloak** → authenticate.
> - As a realm-**`Admin`** user → login succeeds and you land as **Grafana Admin**.
> - As a **non-`Admin`** user → login is **denied** (`role_attribute_strict` returns no role). This is
> the intended lock-out; confirm the denial rather than treating it as a bug.

- [ ] Admin login works, lands as GrafanaAdmin.
- [ ] Non-Admin login is denied.

---

## Phase 9 — Go-live: resume the deploy timer

Once every Phase 8 box is ticked, re-enable the 5-minute deploy loop. From here, alerts are live and
`deploy.sh` reconciles the `iri-monitoring` project on every tick (non-gating).

```bash
sudo systemctl start iri-deploy.timer
systemctl is-active iri-deploy.timer            # expect: active
sudo systemctl list-timers iri-deploy.timer --no-pager
```

**Rollout complete.** Update the CHANGELOG and, per the project's docs-move-with-the-change rule,
confirm README's monitoring section and the wiki are current.

---

## Rollback

The monitoring project is fully decoupled from the app stack.

**Stop only the monitoring stack (apps untouched):**

```bash
docker compose -p iri-monitoring -f /var/iri/code/docker-compose.monitoring.yml down
# The app stack (backend/frontend/ingest/keycloak/DBs/redis) keeps running. Nothing here rolls the
# apps back — the app stack rolls back only via deploy.sh's own health-gate on a failed APP deploy.
```

You can also just set `IRI_MONITORING_ENABLED=false` in `/var/iri/code/.env`; the next `deploy.sh`
tick then stops applying the monitoring project (existing containers keep running until you `down`
them).

**Fully revert the app-side env/network/command changes** (the `MONITORING_SCRAPE_*`, `KC_*` tracing,
Redis `--aclfile`, shared networks):

```bash
# 1. Revert PR 2 on GitHub (removes docker-compose.monitoring.yml + monitoring/ from the bundle and
#    the app-side wiring that referenced them).
# 2. Apply, recreating the app stack without the monitoring wiring:
sudo -u deploy /var/iri/code/scripts/deploy.sh --force
# 3. Optionally remove IRI_MONITORING_ENABLED / MONITORING_* / KC_* lines from .env.
```

> Leaving the host secret/cert files and data dirs in place is harmless — nothing reads them once the
> `iri-monitoring` project is down.

---

## Restore drill (monthly) — extended scope

The monthly `iri-restore-drill` (`scripts/restore-drill.sh`, REQ-OPS-011) now also verifies the
monitoring artifacts **once a backup has captured them**: it restores `grafana.db` (must be a valid
SQLite file) and the monitoring **secrets archive** (`secrets.tar.gz`), and writes
`basetool_restore_drill_artifact_ok{artifact="grafana_sqlite"}` and
`{artifact="monitoring_secrets"}` (1 = restorable, 0 = not) alongside the DB pair. These are reported
**independently** and do not gate the DB-recoverability proof.

> **Run a manual backup BEFORE the first post-rollout drill**, so the monitoring artifacts are present
> in the snapshot the drill restores. Otherwise `artifact_ok=0` fires the "artifact not restorable"
> alert on a false negative.

```bash
# 1. Capture the monitoring artifacts into a fresh snapshot.
sudo -u deploy /var/iri/code/scripts/backup.sh

# 2. Then run the drill (or wait for iri-restore-drill.timer). It exits non-zero on any DB failure and
#    reports the monitoring artifacts independently.
sudo -u deploy /var/iri/code/scripts/restore-drill.sh
# Inspect the textfile metric it wrote:
sudo cat /var/iri/monitoring/textfile/restore_drill.prom
# Expect: basetool_restore_drill_artifact_ok{artifact="grafana_sqlite"} 1
#         basetool_restore_drill_artifact_ok{artifact="monitoring_secrets"} 1
```

---

## Keystore-rotation note

**MUST extend `docs/deployment.md`'s keystore-rotation procedure.** When the shared
`/var/iri/secrets/keystore.p12` is rotated, the exported public CA and Grafana's own cert do **not**
auto-update, and a rotation will then **silently break every app scrape (Prometheus TLS handshake) and
Grafana's TLS**. Add these two steps to the keystore-rotation runbook in `docs/deployment.md`:

1. **Re-export the public CA** consumed by Prometheus (openssl prompts for the password; do not put
   it on the command line):

   ```bash
   openssl pkcs12 -in /var/iri/secrets/keystore.p12 -clcerts -nokeys \
     | openssl x509 -out /var/iri/monitoring/certs/basetool-ca.crt
   # add -legacy after `pkcs12` if OpenSSL 3.x rejects the keytool-made p12
   ```
2. **Re-issue the Grafana self-signed cert** if its SANs/validity changed (Phase 3.8), keeping
   `chown 472:472`.
3. Restart the affected monitoring services so they reload the new files:

   ```bash
   docker compose -p iri-monitoring -f /var/iri/code/docker-compose.monitoring.yml \
     up -d --force-recreate prometheus grafana
   ```

   Then re-run Phase 8.1 (all targets UP) to confirm the app scrapes handshake against the new CA.

---

## Appendix A — UI click-path verification log

Each UI click-path in this runbook was checked against current official (or, where noted, the most
authoritative available) documentation at authoring time (**2026-07-04**). Product versions are those
deployed.

|                                                                               UI step (section)                                                                                |        Product / version        |                                                                                                                                                    Verified against                                                                                                                                                     |                          Status                          |
|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------|
| Grafana OAuth role_attribute_path / _strict, `/login/generic_oauth`, env-var names (4.6, dc.monitoring.yml)                                                                    | Grafana 13.x                    | grafana.com generic-oauth configure-authentication docs                                                                                                                                                                                                                                                                 | Verified (live)                                          |
| Keycloak OIDC client create, Client authentication On, Standard flow, Credentials tab, User Realm Role mapper + "Add to ID token" (4.6)                                        | Keycloak 26.6                   | keycloak.org Server Administration Guide                                                                                                                                                                                                                                                                                | Verified (live)                                          |
| Keycloak user-event metrics (`keycloak_user_events_total`, tags event/error/realm; `--metrics-enabled`/`--event-metrics-user-enabled`; management port 9000) (4.6)             | Keycloak 26.x                   | keycloak.org "Monitoring user activities with event metrics"                                                                                                                                                                                                                                                            | Verified (live)                                          |
| Grafana Explore → Tempo Search / TraceQL; Loki queries (8.4/8.5)                                                                                                               | Grafana 13.x / Tempo            | grafana.com Tempo "visualize/query traces in Grafana" docs                                                                                                                                                                                                                                                              | Verified (live)                                          |
| Hetzner rescale: power off first, Rescaling tab, pick plan, "CPU and RAM only" toggle, disk-growth permanent (Phase 1)                                                         | Hetzner Cloud Console (current) | Hetzner official rescaling doc URL returned **HTTP 404**; verified against Hetzner community/how-to sources (bizanosa, cloudtally) — **confirm against the live console before executing**                                                                                                                              | Partial — official page unreachable                      |
| NPM proxy-host Advanced tab → Custom Nginx Configuration `location /actuator { return 403; }`; Add Proxy Host Details/SSL tabs, Block Common Exploits, Let's Encrypt (4.3/4.4) | NPM 2.15.1                      | nginxproxymanager.com advanced-config + guide pages (file-based config confirmed; the exact **Details/SSL/Advanced** modal tab labels + "Custom Nginx Configuration" text-area name are **not spelled out on the official page** — corroborated by NPM GitHub issues/discussions) — **confirm against the live NPM UI** | Partial — official page does not name the modal tabs     |
| healthchecks.io: Add Check on the Checks page, copy Ping URL, free tier 20 checks (4.7)                                                                                        | healthchecks.io (current)       | healthchecks.io/docs (concepts + free-tier limit confirmed; the exact **"Add Check"** button label / copy affordance are **not described** in the docs) — **confirm against the live UI**                                                                                                                               | Partial — official docs don't describe the UI affordance |
| GitHub fine-grained PAT: Settings → Developer settings → Fine-grained tokens → Generate, resource owner, per-repo read-only perms (3.6)                                        | GitHub (current)                | GitHub fine-grained PAT UI (well-established; not re-fetched live this session)                                                                                                                                                                                                                                         | Not re-verified live                                     |
| DNS A record for `grafana` (4.5)                                                                                                                                               | Registrar-specific              | Generic; verify against your provider's console                                                                                                                                                                                                                                                                         | N/A (generic)                                            |

**Could NOT verify against a live official source (documented inline above, confirm against the live
UI/console before executing):**
- **Hetzner** rescale click-path — the official rescaling documentation page returned HTTP 404;
corroborated only via community how-to sources.
- **NPM 2.15.1** — the official pages confirm the *feature* (Advanced custom Nginx config, Block Common
Exploits, Let's Encrypt) but do **not** spell out the exact **Details / SSL / Advanced** modal tab
labels or the **"Custom Nginx Configuration"** text-area name; these are corroborated by community
and GitHub issue sources.
- **healthchecks.io** — official docs confirm the concepts and the 20-check free tier but do **not**
describe the **"Add Check"** button / Ping-URL copy affordance.
- **GitHub fine-grained PAT** click-path — well-established and stable, but not re-fetched from a live
source in this authoring session.
