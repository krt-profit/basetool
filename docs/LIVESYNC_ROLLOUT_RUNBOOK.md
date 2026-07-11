# Live-Sync Rollout Runbook

**Doc type:** operator runbook (living). This is the single, execution-ordered document an operator
follows to roll the tool-wide live multi-user sync (epic #1102) onto the single-host production Docker
deployment. Everything you need is here — you should not have to open another document to execute it.

> README's live-sync line points here (see [`README.md`](../README.md)). Requirements: REQ-FE-015 and [ADR-0094](adr/0094-tool-wide-topic-room-live-sync-relay.md). The Redis fan-out infra lands in the self-contained commit `5e648cf4` ("fan the SSE push across replicas via Redis pub/sub").

Epic #1102 (closes #1115, #1120). Compose project: the app stack (`--profile prod`,
[`docker-compose.yml`](../docker-compose.yml)).

---

## Why this needs a manual operator step

Almost all of #1102 is application code that ships through the ordinary `deploy.sh` tick with **no**
operator action. Two things do **not**:

1. **A compose network-topology change.** The backend now joins a new dedicated network
   **`net-redis-backend`** (subnet `172.28.11.0/24`) so it can reach the `redis` service for the
   notification SSE cross-replica fan-out (the frontend live-sync fan-out reuses the existing
   `net-redis-frontend`, so it needs no topology change). `deploy.sh` reconciles the stack with an
   **in-place `up`**, which — on a network add against the pinned `172.28.0.0/16` block — strands
   containers on the old network wiring instead of re-attaching them — the subnet-pinning lesson of
   issue #974, where an in-place `up` after a network change leaves services unable to resolve each
   other (`backend` cannot reach `redis`). The fix is a clean **`down` + `up`** from a quiesced stack,
   which only a human should trigger inside a maintenance window.
2. **A Redis ACL pre-check.** The fan-outs `PUBLISH`/`SUBSCRIBE` on two pub/sub channels; the Redis
   `default` user must carry the `&*` channel grant (it already does for the current setup) and the
   restricted `monitoring` user must **not**. This is a read-only verification, not a change, but it
   must pass before the deploy.

A Redis outage (or a mis-provisioned ACL) does **not** break the app: each fan-out publishes only
**after** its local same-replica relay, so cross-replica delivery degrades silently to single-instance
and the notification polling fallback (REQ-NOTIF-006) still delivers. Redis is deliberately **not** in
the backend readiness group and `management.health.redis.enabled` is `false` in the base config
(the ADR-0084 lesson: an optional feature must never trip a deploy rollback), so `/actuator/health`
stays green without Redis.

**Run everything below as `root` (via `sudo`) unless a command is explicitly `sudo -u deploy`.**

---

## Table of contents

1. [Phase 0 — Pre-checks](#phase-0--pre-checks)
2. [Phase 1 — Deploy with the network change (clean down + up)](#phase-1--deploy-with-the-network-change-clean-down--up)
3. [Phase 2 — Post-deploy verification](#phase-2--post-deploy-verification)
4. [Rollback](#rollback)

---

## Phase 0 — Pre-checks

Do not proceed unless every check passes.

```bash
cd /var/iri/code

# 1. REDIS_PASSWORD is set in .env (the frontend + ingest already use it; the backend now reuses it
#    for the notification fan-out — no NEW variable is required). Confirm it is present and non-empty.
sudo grep -q '^REDIS_PASSWORD=..*' .env && echo "REDIS_PASSWORD present" || echo "!! REDIS_PASSWORD missing — set it before deploy"

# 2. The Redis default user carries the &* channel grant (covers PUBLISH/SUBSCRIBE of the two
#    fan-out channels). It is granted by --requirepass on the default user; the aclfile must NOT
#    override the default user to a narrower grant. Inspect the running ACL:
REDIS_PW="$(sudo grep -oP '(?<=^REDIS_PASSWORD=).*' /var/iri/code/.env)"
docker exec redis redis-cli -a "$REDIS_PW" --no-auth-warning ACL LIST
# Expect a `user default on ... ~* &* +@all` line (the &* is the channel grant), and the restricted
# `user monitoring` line WITHOUT `&*`. If `default` shows `resetchannels` / no `&*`, add `&*` to the
# default user in /var/iri/redis/users.acl and reload (ACL LOAD) BEFORE deploying — otherwise both
# fan-outs will fail every PUBLISH.

# 3. Free disk / images headroom for a stack recreate (pub/sub adds no keys, so the 384mb Redis
#    noeviction budget is unaffected — this is only for the image pull + container recreate).
df -h /var/iri
free -m
```

|                  Check                  |            Evidence            |              Expected              |
|-----------------------------------------|--------------------------------|------------------------------------|
| `REDIS_PASSWORD` set                    | `grep '^REDIS_PASSWORD=' .env` | present, non-empty                 |
| Redis `default` user has `&*`           | `ACL LIST`                     | `user default ... &* +@all`        |
| Redis `monitoring` user has **no** `&*` | `ACL LIST`                     | `user monitoring ...` without `&*` |
| Disk / memory headroom                  | `df -h` / `free -m`            | room for an image pull + recreate  |

---

## Phase 1 — Deploy with the network change (clean down + up)

The new `net-redis-backend` network means a clean `down` + `up` is required — an in-place `up` would
strand `backend` off `redis` (see [Why this needs a manual operator step](#why-this-needs-a-manual-operator-step)).
Do this in a maintenance window; the maintenance page shows during the swap.

```bash
cd /var/iri/code

# 1. Freeze the 5-minute deploy loop so no automatic tick interleaves with the manual steps.
sudo systemctl stop iri-deploy.timer
systemctl is-active iri-deploy.timer      # expect: inactive

# 2. Make sure the release bundle that carries the #1102 code + the compose change is promoted
#    (merge the PR / promote :stable) so deploy.sh pulls it. Confirm the compose on the host already
#    has the net-redis-backend network + the backend joining it:
grep -n 'net-redis-backend' docker-compose.yml    # expect the network definition + backend/redis joins

# 3. Bring the app stack DOWN cleanly. Volumes are NOT touched (no --volumes) — Postgres, Keycloak
#    and the Redis data/AOF all persist.
docker compose --profile prod down

# 4. (Optional, if a stale project-owned network lingers) prune ONLY dangling project networks. This
#    never touches the monitoring project's networks or any in-use network.
docker network prune -f

# 5. Bring it back up from the clean state via deploy.sh --force (recreates every service, picks up
#    the new network + the backend REDIS_* env). --force bypasses the stateful-infra carve-out gate.
sudo -u deploy /var/iri/code/scripts/deploy.sh --force 2>&1 | tee /tmp/iri-deploy-livesync.log
echo "deploy exit: ${PIPESTATUS[0]}"      # MUST be 0

# 6. Re-enable the deploy loop.
sudo systemctl start iri-deploy.timer
systemctl is-active iri-deploy.timer      # expect: active
```

> If the deploy fails and rolls back, the **most likely** cause is unrelated to live-sync (Redis is non-gating). Check `/tmp/iri-deploy-livesync.log`; a Redis fan-out problem will **not** roll the apps back.

---

## Phase 2 — Post-deploy verification

```bash
cd /var/iri/code
REDIS_PW="$(sudo grep -oP '(?<=^REDIS_PASSWORD=).*' /var/iri/code/.env)"

# 1. Both fan-out subscribers are live on their channels (the frontend on basetool:livesync:changed,
#    the backend on basetool:notify:published). PUBSUB CHANNELS lists channels with >=1 subscriber.
docker exec redis redis-cli -a "$REDIS_PW" --no-auth-warning PUBSUB CHANNELS 'basetool:*'
# Expect BOTH: basetool:livesync:changed  AND  basetool:notify:published
# (A channel appears only once a subscriber is connected — the frontend subscribes at startup, the
#  backend at startup when the fan-out is enabled. If one is missing, read that app's startup log.)

# 2. Backend fan-out start line (enabled path):
docker compose --profile prod logs backend | grep -iE 'redis.*fan|notification.*fan-out|RedisNotificationFanout' | tail
# Frontend fan-out start line:
docker compose --profile prod logs frontend | grep -iE 'redis.*fan|livesync.*fan|RedisLiveSyncFanout' | tail

# 3. Health: root health green on both apps; readiness UNAFFECTED by Redis (Redis is not in the
#    readiness group and its health indicator is disabled — a Redis blip must not flip readiness).
docker compose --profile prod exec backend  sh -c 'wget -qO- http://localhost:11261/actuator/health || true' ; echo
docker compose --profile prod exec frontend sh -c 'wget -qO- http://localhost:18081/actuator/health || true' ; echo
# Expect {"status":"UP"} on both.

# 4. File-descriptor ceiling: the epic pins nofile=65536 on both apps (they now hold one /ws/sync
#    socket per tab + a notification-SSE relay per viewer). Confirm the pin took effect — a value far
#    below 65536 would mean the dockerd hard limit clamped it, in which case raise LimitNOFILE in the
#    host's docker.service before relying on live-sync at scale.
docker compose --profile prod exec backend  sh -c 'ulimit -n' ; echo
docker compose --profile prod exec frontend sh -c 'ulimit -n' ; echo
# Expect 65536 on both.
```

**Grafana (dashboard `07 Basetool operations`):**

- [ ] The `Live-sync subscribe outcomes/hour by topic class`, `Live-sync subscriptions (live) by topic class`
  and `Redis fan-out pub/sub & errors/hour` panels return data.
- [ ] `basetool_livesync_redis_published_total` / `basetool_sse_redis_published_total` climb once
  users are active on more than one replica (single-replica prod shows publishes with zero
  cross-replica consumes — that is normal).
- [ ] The `LiveSyncRedisFanoutBroken` alert is **inactive** (no sustained publish errors).

**Two-browser smoke (do NOT use prod credentials for a scripted test — a manual human login is fine):**

- [ ] Open the **same mission** in browser A and an incognito B (same or two users). A change in A
  (join / crew move / party-lead / frequency) appears in B **in place, without a reload** — watch
  the `/ws/sync` frame in DevTools → Network → WS.
- [ ] Open the **Aufträge queue** in A + B; create/reorder in A → B's queue updates in place.
- [ ] With a **dialog open** in B, a peer change shows the **„Aktualisierungen verfügbar"** pill
  rather than yanking B's DOM.

---

## Rollback

The live-sync fan-out is decoupled from correctness: a rollback returns the tool to single-instance
sync (same-replica peers still sync) + local-only SSE + the notification polling fallback — **no data
loss**.

**Fastest (leave the code, disable the fan-outs):** set `APP_NOTIFICATIONS_REDIS_FANOUT_ENABLED=false`
in `/var/iri/code/.env` and, for the frontend, `APP_LIVESYNC_REDIS_ENABLED=false`, then re-deploy
(`sudo -u deploy /var/iri/code/scripts/deploy.sh --force`). No network change is needed to disable —
the backend simply stops opening the Redis connection.

**Full revert (removes the network change too):** revert the self-contained infra commit on GitHub
(`git revert 5e648cf4`, plus the rest of the branch as needed). Because that removes the
`net-redis-backend` network, the revert ALSO needs a clean `down` + `up`:

```bash
cd /var/iri/code
sudo systemctl stop iri-deploy.timer
docker compose --profile prod down        # volumes untouched
docker network prune -f                    # drops the now-unreferenced net-redis-backend
sudo -u deploy /var/iri/code/scripts/deploy.sh --force
sudo systemctl start iri-deploy.timer
```

> Leaving `net-redis-backend` in place after a code-only rollback is harmless — nothing joins it once the backend no longer references it.

