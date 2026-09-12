# Edge cutover runbook — Nginx Proxy Manager → native nginx

Doc type: **operational runbook**. Companion to
[ADR-0162](adr/0162-edge-is-native-nginx-with-a-separate-acme-client.md), which carries the decision
and the reasoning. This file carries only the steps.

**Merging and promoting is not enough**, and this runbook exists because of the two reasons why:

1. The five `net-proxy-*` networks gain `internal: true` and a sixth network appears. Docker cannot
   change those in place, so the stack is recreated — a **brief full outage**, planned rather than
   discovered.
2. **nginx refuses to start without a certificate**, and the `acme` container cannot obtain one
   before something serves the HTTP-01 challenge on port 80. That circle is broken by seeding the
   certificates Let's Encrypt already issued for NPM, which are valid and need no network at all.

Budget 15 minutes. Everything is reversible at every step; the way back is at the bottom.

---

## 0. Before you start

`ACME_EMAIL` must be in the host `.env`. Without it the `acme` container exits immediately with
`ACME_EMAIL must be set in .env for the acme container` — by design, and it is the only new required
variable.

```bash
grep -c '^ACME_EMAIL=' /var/iri/code/.env      # expect: 1
```

The edge verifies its upstreams against the certificate exported from the shared keystore. It is
already on the host for Prometheus; confirm rather than assume:

```bash
ls -l /var/iri/monitoring/certs/basetool-ca.crt
```

If it is missing, export it first — the recipe is in
[`MONITORING_ROLLOUT_RUNBOOK.md`](MONITORING_ROLLOUT_RUNBOOK.md) §3.6.

## 1. Merge and promote

Nothing special. The promotion carries `docker/edge/` to the host inside the `basetool-config`
bundle; the deploy writes it under `/var/iri/code/docker/edge/`.

**Verify it arrived before touching anything**, because an empty mount is exactly how this change
would "ship and do nothing":

```bash
ls /var/iri/code/docker/edge/conf.d/ /var/iri/code/docker/edge/include/
```

Expect **seven** files under `conf.d/` (the maps plus six server blocks) and **seven** under
`include/`. If the directory is absent, the bundle predates the change — promote again and do not
continue.

## 2. Stop NPM

It holds `:80` and `:443`; the edge cannot bind them while it runs. NPM is no longer in the `prod`
profile, so `up -d` neither starts nor stops it — this is deliberate and manual.

```bash
cd /var/iri/code && docker compose --profile rollback stop npm
```

**The site is down from here until step 4.**

## 3. Seed the certificates

`lego` will issue its own on first run, but nginx has to start before it can serve the challenge.
The certificates NPM already holds are valid, so copy them in.

The volume does not exist yet. Compose names it `<project>_edge-certs`, and the project name comes
from the compose directory — `/var/iri/code`, so `code_edge-certs`. Create it and confirm the name
rather than trusting this paragraph:

```bash
docker volume create code_edge-certs && docker volume ls --format '{{.Name}}' | grep edge-certs
```

Copy each host's material into the per-host layout the edge mounts. The mapping was read from the
live NPM configuration on 2026-09-12:

|             Host              | NPM certificate |
|-------------------------------|-----------------|
| `profit-base.online`          | `npm-3`         |
| `keycloak.profit-base.online` | `npm-4`         |
| `ingest.profit-base.online`   | `npm-5`         |
| `grafana.profit-base.online`  | `npm-7`         |
| `api.profit-base.online`      | `npm-8`         |

```bash
docker run --rm \
  -v /var/iri/npm/letsencrypt/live:/seed:ro \
  -v code_edge-certs:/certs \
  alpine:3 sh -c '
    set -eu
    seed() { mkdir -p "/certs/$2"; cp -L "/seed/$1/fullchain.pem" "/certs/$2/fullchain.pem"; cp -L "/seed/$1/privkey.pem" "/certs/$2/privkey.pem"; }
    seed npm-3 profit-base.online
    seed npm-4 keycloak.profit-base.online
    seed npm-5 ingest.profit-base.online
    seed npm-7 grafana.profit-base.online
    seed npm-8 api.profit-base.online
    ls -R /certs
  '
```

`cp -L` matters: Let's Encrypt's `live/` entries are symlinks into `archive/`, and a copied symlink
would dangle inside the volume.

## 4. Bring the edge up

The network changes force a recreate of the five proxy bridges, so this is the step that briefly
takes the whole stack with it.

```bash
cd /var/iri/code && docker compose --profile prod up -d
```

If Docker refuses to recreate a network because containers are still attached, stop the stack first
(`docker compose --profile prod down`) and repeat. That is the full-outage path and it is expected
here.

## 5. Verify, from outside

```bash
curl -sSI https://profit-base.online/ | head -3
curl -sS -o /dev/null -w '%{http_code}\n' https://api.profit-base.online/actuator/health   # expect 404
curl -sS -o /dev/null -w '%{http_code}\n' https://profit-base.online/.git/config           # expect 404
curl -sSI http://profit-base.online/ | head -2                                             # expect 308
```

Then the things only the host can show:

```bash
docker inspect edge --format '{{.State.Health.Status}}'        # expect: healthy
docker compose --profile prod logs --tail=20 acme
docker exec edge sh -c 'wget -q -O - http://127.0.0.1:8080/healthz'   # expect: ok
```

**The two probes that already existed are the real gate.** `blackbox-edge-deny` and the daily
`edge-deny-probe.yml` GitHub Action assert the `/actuator` and Keycloak-admin denials from outside
the host — they run unchanged and they are the check that the translation did not widen anything.

## 6. Let ACME prove itself

`acme` issues its own certificates on first start, through the running edge. That is deliberate: it
exercises renewal **now**, while the seeded certificates are still valid for weeks, instead of
finding out in sixty days.

```bash
docker compose --profile prod logs acme | tail -30
```

When it succeeds, `deploy.sh`'s reconcile notices the changed fingerprint on its next tick and
recreates the edge to load them. Nothing to do by hand.

---

## Rollback

At any point, and it is fast:

```bash
cd /var/iri/code
docker compose --profile prod stop edge acme
docker compose --profile rollback up -d npm
```

NPM's data, its database and its Let's Encrypt material were never touched, and it still has the
`net-proxy-*` membership it needs to reach the upstreams.

> [!warning] A rolled-back NPM cannot RENEW certificates
> The `net-proxy-*` networks are `internal: true` now, and NPM is on those five and nothing else —
> so certbot can no longer reach Let's Encrypt. Serving is unaffected: the existing certificates stay
> valid for weeks, which is ample for deciding what to do next. But a rollback that is meant to last
> needs one of two things: drop `internal: true` from the five networks again (a recreate, another
> outage window), or attach NPM to `net-acme-egress`, which exists and has egress:
>
> ```bash
> docker network connect code_net-acme-egress npm
> ```
>
> The second is a one-liner and survives until the container is recreated. Check the certificate
> expiry before deciding it does not matter: `docker exec npm certbot certificates | grep -A1 npm-3`.

Leave NPM in place afterwards, longer than feels necessary. Removing it is a separate change.
