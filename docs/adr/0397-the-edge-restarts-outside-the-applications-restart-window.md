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

## Consequences

- **Easier:** an application release, a re-apply and a heal of an application keep the maintenance
  page up from start to end. A release that re-defines the edge costs one edge restart of about two
  to three seconds instead of the whole window, and any edge restart drops from about ten seconds to
  about two to three.
- **Harder / costs accepted:** a request or stream still open when the edge stops is closed after two
  seconds — an upload in progress included — where it was closed after ten. The edge's own restart,
  a host restart and a haproxy restart still leave the public ports without an answer; the owner
  accepted those as the exceptions (2026-11-19). A unit the release retires is still stopped before
  the edge restarts, because the edge can only pick up its new definition after the unit reload
  that has to follow the retirement.
- **Follow-up:** the config- and certificate-drift path of `reconcile_edge` still recreates the edge
  instead of reloading nginx; a reload would make that change gapless.

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
