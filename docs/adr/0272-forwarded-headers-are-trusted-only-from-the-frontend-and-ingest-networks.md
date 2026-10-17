# ADR-0272 — Forwarded headers are trusted only from the frontend and ingest networks

- **Status:** Accepted — decided ahead of the change; implemented in #2673.
- **Date:** 2026-10-17
- **Deciders:** @greluc (issue #2638)
- **Related:** [ADR-0268](0268-the-backend-is-internal-only-and-ingest-is-the-single-public-api-entry-point.md)
  (which makes the peer set two networks) · specs `REQ-SEC-011`, `REQ-SEC-032`, `REQ-SEC-033` ·
  `ForwardedHeaderConfig`, `ClientIpContextFilter`, `RateLimitingFilter`,
  `ForwardedHeaderRewriteTest` · frontend `ClientIpRelayFilter`

## Context

REQ-SEC-011 established how the backend attributes a client IP, and the reason is pinned by a test
rather than by a comment: downstream of Spring's `ForwardedHeaderFilter`, `getRemoteAddr()` returns
the **leftmost** entry of the forwarded chain, which any client can set, and the original chain is
gone. `ForwardedHeaderRewriteTest` demonstrates exactly that with `9.9.9.9, 203.0.113.7`. So
`server.forward-headers-strategy` is `none`, `ForwardedHeaderConfig` registers
`ClientIpContextFilter` at `HIGHEST_PRECEDENCE` and re-registers Spring's filter one slot behind it,
and attribution happens on the raw headers, honouring a forwarded entry only when the TCP peer is in
`app.rate-limit.trusted-proxies`.

That allow-list is `172.28.0.0/16` in production — the whole container address space, every bridge
network at once. It was written when the peers that could legitimately forward included the edge in
front of the API vhost, and a /16 covers whatever the compose file happens to lay out.

The resolved address is not cosmetic: `RateLimitingFilter` uses it as the per-IP bucket key
(REQ-SEC-032), and the frontend's `ClientIpRelayFilter` relays the member's browser address on every
outbound `WebClient` call precisely so that those limits key on the member rather than on one
container. The address itself is never logged or exported — only the `KeySource` that produced it.

Two facts make this the moment to narrow it. After ADR-0268 the backend's possible HTTP peers are the
frontend (`net-backend-frontend`, `172.28.2.0/24`) and the ingest gateway (`net-backend-ingest`,
`172.28.8.0/24`); the edge is not one. And the rewrite half of the arrangement has no consumer: the
backend builds **no absolute URL from a request**. There is no `ServletUriComponentsBuilder`, no
`fromCurrentRequest*`, no `ResponseEntity.created(...)`, no `Location` header, and no read of
`getRequestURL()`, `getScheme()` or `getServerName()` anywhere in `backend/src/main`. The connector
itself is HTTPS, so `isSecure()` is true without any rewrite and the HSTS header keeps being written.

## Decision

**Trust forwarded headers only from the frontend and ingest container networks, and remove the
`ForwardedHeaderFilter` registration.**

1. `app.rate-limit.trusted-proxies` names the frontend and ingest networks instead of
   `172.28.0.0/16`. A forwarded header from any other peer is ignored and the request is attributed
   to its actual peer address.
2. **Spring's `ForwardedHeaderFilter` is removed** from `ForwardedHeaderConfig`. Its only effect here
   is the host/scheme/port rewrite nothing reads, and its documented side effect — a client-chosen
   `getRemoteAddr()` for everything downstream — is a liability held for no benefit.
3. **`ClientIpContextFilter` stays, at the front of the chain, with the narrowed list.** Removing the
   rewrite does not remove the need for attribution: the per-IP budget still has to key on the
   member's address, which still arrives as a relayed header from a trusted peer.
4. **`ForwardedHeaderRewriteTest` is rewritten to pin what now holds.** It exists to keep a
   non-obvious framework behaviour from being re-discovered; with the filter gone, what needs pinning
   is that the peer address reaches the application unrewritten and that a forwarded header from an
   untrusted peer does not move attribution.

## Consequences

**The trust boundary becomes two networks a reviewer can read against arc42 §7**, instead of an
address space that happens to contain every container including ones that must never be believed. A
third internal caller becomes a deliberate addition in the same change that introduces it.

**One filter leaves the front of every request**, and with it the window in which a downstream
component could read a client-chosen `getRemoteAddr()` and be right by accident.

**The constraint this creates is written down, because the next person will not know it.** If the
backend ever has to produce an absolute URL from a request — a redirect, a `Location` on a 201, a
link in a payload — it must build it from configuration, or re-register the filter and then re-open
the attribution ordering this decision simplified. The spec says so (#2673), because a build failure
will not.

**A relay that stops sending the header degrades attribution, not authorisation.** The per-`sub`
budget of REQ-SEC-033 keys on the token subject and is unaffected; the per-IP budget would key on the
calling container, which makes it coarse rather than wrong. The edge keeps the budgets that protect
the public surfaces.

**Nothing changes for the ingest gateway's own posture.** It sets
`server.forward-headers-strategy: native` because it really is behind the edge and is the
internet-facing hop; this decision is about the backend's peers, not about the gateway's.

## Alternatives considered

*Keep `172.28.0.0/16`.* It is one line and it trusts the monitoring network, the database networks
and every network a future service joins to speak for any client. The cost of narrowing it is a
two-entry list that has to be edited when a caller is added — which is the event that should require
an edit.

*Keep the `ForwardedHeaderFilter` "in case something needs it".* Framework behaviour kept on
speculation is how the attribution problem arose in the first place: the filter was registered
because Boot registers it, and REQ-SEC-011 then had to work around it. Nothing reads the rewrite
today, and the test above records what it costs to have it.

*Trust the headers unconditionally and let the network be the boundary.* Nearly true after ADR-0268,
and it gives up the one thing the allow-list buys: a peer that *is* on an internal network but is not
a relay — a monitoring scrape, a future sidecar — cannot claim to speak for an arbitrary member.

*Resolve the client IP from a dedicated header the relays set, instead of `X-Forwarded-For`.* A
cleaner contract between our own modules and a second mechanism to keep in step with the standard one
the gateway already relies on. Not worth a bespoke header for the same guarantee.
