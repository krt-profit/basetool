# ADR-0397 — The edge restarts outside the applications' restart window

- **Status:** Accepted
- **Date:** 2026-11-19
- **Deciders:** @greluc
- **Related:** spec [`deployment-delivery.md`](../specs/deployment-delivery.md) (REQ-OPS-061,
  REQ-OPS-003, REQ-OPS-007) · [ADR-0213](0213-a-release-that-moves-the-provider-jar-costs-one-outage.md) ·
  [ADR-0083](0083-deploy-bot-health-drift-targeted-restart.md) ·
  [ADR-0162](0162-edge-is-native-nginx-with-a-separate-acme-client.md) ·
  [ADR-0187](0187-the-edge-learns-the-client-address-from-a-proxy-protocol-front-end.md) · runbook
  `docs/deployment.md` → *What happens on the host*

## Context

The edge maps an unreachable upstream to the maintenance page, so while the applications are down
for a release every request should still get an answer. Users reported a stretch at the start of a
deploy in which they got no answer at all, not even the maintenance page.

Production's haproxy log (TCP mode, one line per connection, read 2026-11-19) shows where it comes
from:

- **An application release does not cause it.** The releases of 2026-11-15 10:50, 2026-11-18 11:23
  and 14:05 and 2026-11-19 06:35 show no refused connection: the maintenance page answered from the
  first second of the window.
- **A release that re-defines the edge does.** v1.26.3 (2026-11-19 09:55) moved the nginx image, so
  `edge` was in the release's re-defined units. The apply stopped it in the window's one stop
  together with the databases, keycloak and the applications, and started the stack in
  `RT_STACK_ORDER`, where the edge came after `db-keycloak` and `keycloak`. haproxy refused 268
  connections from 09:56:08 to 09:57:20 — the edge was down until keycloak was healthy.
- **Every edge restart costs about ten seconds.** The config-drift recreate of 2026-11-18 11:26
  left 14 connections without a byte from 11:26:16 to 11:26:25. nginx's image stops it with
  `SIGQUIT`, a graceful stop that closes the listeners at once and then waits on open connections;
  the live-update streams keep it waiting until Podman's ten-second stop timeout kills it.

haproxy cannot cover the gap itself. It forwards TCP and the TLS ends at the edge, so it has no page
to send, and the rootless port forwarder accepts a connection while nginx is not listening, so
haproxy sees a closed connection rather than a failed connect and its retries never start.

## Decision

The edge is never stopped inside the applications' restart window, and its own restart is short.

- **First in the stack order.** `RT_STACK_ORDER` starts with `edge`. It depends on no stack unit —
  its upstreams resolve per request — so every start in order brings the maintenance page up before
  the databases, keycloak and the applications. The applications still find it up when they fetch
  the issuer's discovery document through the public name.
- **Restarted alone, before the window.** `rt_apply_stack` restarts a re-defined edge on its own
  after the unit reload and before the window's one stop, and the stop never names it. The new edge
  then serves the maintenance page for the whole window. A sick edge is healed the same way: one
  restart, nothing else stopped.
- **A failed edge fails early.** An edge restart that does not reach healthy fails the apply at once,
  with `[edge]` as the unit that did not come up and nothing else stopped; the rollback applies the
  previous release as for any failed gate.
- **A bounded stop.** `worker_shutdown_timeout 2s` in `docker/edge/nginx.conf` caps the graceful
  stop's wait on open connections at two seconds.
- **A reload, not a restart, for configuration and certificates.** When `reconcile_edge` finds the
  on-disk edge configuration or a certificate changed, it runs `docker/edge/reload.sh` in the
  running edge: render the vhosts again, `nginx -t`, `nginx -s reload`. New workers take over
  without a gap. A configuration nginx refuses is left unloaded and the edge is **not** recreated,
  because a new edge would not start on it while the running one still serves the configuration it
  has; `basetool_edge_config_stale` reports it and `EdgeConfigNotLoaded` alerts. Any other reload
  failure, and an edge that is not running, falls back to the recreate. The deploy host has rsync,
  so the mirror rewrites the files inside the bind-mounted directories and the edge sees them
  without being recreated.

## Consequences

- **Easier:** an application release, a re-apply and a heal of an application keep the maintenance
  page up from start to end. A release that re-defines the edge costs one edge restart of about two
  to three seconds instead of the whole window, and any edge restart drops from about ten seconds to
  about two to three. An edge configuration change and a renewed certificate cost no gap at all, and
  a configuration nginx refuses no longer takes the edge down: the recreate it used to trigger would
  have left no edge running.
- **Harder / costs accepted:** a request or stream still open when the edge stops is closed after two
  seconds — an upload in progress included — where it was closed after ten. The edge's own restart,
  a host restart and a haproxy restart still leave the public ports without an answer; the owner
  accepted those as the exceptions (2026-11-19). A unit the release retires is still stopped before
  the edge restarts, because the edge can only pick up its new definition after the unit reload
  that has to follow the retirement. A reload renders into the live directory before `nginx -t`
  checks it, so a refused configuration stays on the edge's tmpfs until the next successful reload
  or restart; nginx keeps the loaded one in memory, and a restart renders from scratch. A reload also
  ends the old workers' live-update streams after two seconds; the clients reconnect.

## Alternatives considered

- **Keep the edge in the window and only move it first in the order.** That would end the wait for
  keycloak, but the edge would still go down with the window and come back as the first start —
  a gap at the busiest moment of the release for no gain over restarting it alone beforehand.
- **Restart the edge after the gate.** The old edge would serve the window, but the new edge's
  configuration (a changed maintenance mapping included) would not apply during it, and a failing
  new edge would roll back a release whose applications already restarted.
- **A haproxy fallback page or longer retries.** In TCP mode haproxy has no HTTP response to send,
  and terminating TLS there would move the certificates, the PROXY-protocol trust and the client
  address handling of ADR-0187 out of the edge; the retries do not fire behind the rootless port
  forwarder.
- **Two edges behind haproxy, switched blue/green.** It would remove the edge's own gap, at the cost
  of a second edge unit, a second set of ports and a switch-over step in the apply — out of
  proportion to two or three seconds the owner accepts.
- **`SIGTERM` as the edge's stop signal.** nginx's fast stop would end every request at once; the
  bounded graceful stop lets short requests finish.
- **Recreate on a refused configuration too.** That is what `reconcile_edge` did. It turns a bad
  configuration into an edge that does not start, the one failure the maintenance page cannot
  cover.
- **Render into a staging directory and test that.** It would keep a refused configuration off the
  live directory, but `nginx.conf` includes the rendered vhosts from one fixed path, so testing a
  staged copy needs a second `nginx.conf`; the live render is only read on a reload or a restart.

## Amendment 1 (2026-11-25) — the frontend starts before ingest, and the window is measured

**Context.** The window of v1.26.7 on production (2026-11-22, read from the service user's journal)
took 170 s: the stop 54 s, then keycloak 22 s, backend 31 s, ingest 32 s and frontend 31 s, each
`systemctl start` waited for in `RT_STACK_ORDER`. Members see the maintenance page until the
frontend answers. `frontend` requires `backend`, `keycloak` and `redis-session`, not `ingest`, yet it
started only after ingest's 32 s.

**Decision.**

- `RT_STACK_ORDER` is `edge db-backend db-keycloak redis redis-session redis-shared keycloak backend
  frontend ingest acme`. Ingest is down exactly as long as before, only later; the member-facing
  window loses ingest's start.
- `rt_apply_stack` times the window from its one stop until every stack unit started
  (`RT_WINDOW_SECONDS`). A release, re-apply or heal whose window closed healthy logs it and writes
  `basetool_deploy_last_restart_window_seconds` and `…_timestamp`, shown on the *Ops automation*
  dashboard, so the effect of this and later changes is read rather than guessed.
- The maintenance page reloads every 10 s instead of 30 s (REQ-OPS-061).

**Consequences.** The member-facing window of a release shrinks by ingest's start, about 30 s of the
170 s measured. The desktop extractor's gateway comes back last, after the same downtime as before.
The stop's 54 s are left as they are here: the frontend and
the backend each wait out the 20 s graceful-shutdown phase for requests still open, and keycloak
restarts in every release whose provider-JAR digest moved; both are separate decisions.

**Alternatives considered.** Starting frontend and ingest in one `systemctl start` would let systemd
run them in parallel, but the gate names what did not come up per unit in start order; one call
would need a second pass to attribute a failure, for no member-facing gain over the order.
