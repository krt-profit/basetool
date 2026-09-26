# ADR-0217 — Third-party clients are public device-grant clients, bound by DPoP and consent, approved in a database registry

- **Status:** Accepted — owner gate G0 of epic [#2078](https://github.com/krt-profit/basetool/issues/2078),
  taken with the merge of #2111 and #2112 (2026-09-26); implementation pending. Amends [ADR-0152](0152-the-audit-row-records-which-client-a-mutation-came-through.md)
  (client attribution on the relay hop).
- **Date:** 2026-09-26
- **Deciders:** @greluc
- **Related:** spec [`external-exchange.md`](../specs/external-exchange.md) ·
  [ADR-0216](0216-the-exchange-api-is-a-separate-contract-on-the-ingest-gateway.md) ·
  [ADR-0129](0129-ingest-gateway-is-a-trusted-subsystem-not-a-token-relay.md) ·
  [ADR-0131](0131-mobile-auth-refresh-only-dpop-binding.md) · `REQ-INGEST-011`, `REQ-INGEST-012`,
  `REQ-SEC-036`, `REQ-AUDIT-005`

## Context

An external client is a program on a member's PC. It cannot keep a secret: its client id is
reproducible from the binary, and its release process is outside our control (VerseKit's releases
are unsigned today). What it can do is hold a key pair, show a device code and let the member
approve it in the browser.

Today the gateway accepts DPoP but does not require it, and the backend, on the relay hop, builds
the acting member's authentication from **every** stored role (`DatabaseActingMemberAuthorities`
calls `assembleFor(user)`), so an admin's extractor upload runs with admin authority. Audit rows of
relayed writes record `client_id=none`, because the acting authentication carries no token.

## Decision

We will model every external client as follows.

1. **One public Keycloak client per product**, device grant only (RFC 8628), `consentRequired`,
   `fullScopeAllowed: false`, no PII mappers and none of the realm's default `profile`, `email` or
   `roles` scopes, the base scope `exchange.connect`, `offline_access` and every capability scope
   offered as optional, and access **and** refresh
   tokens DPoP-bound through the per-client attribute `dpop.bound.access.tokens` (RFC 9449). The
   Android client's refresh-only binding (ADR-0131) is deliberately not used: it would leave the
   access token unbound.
   Clients request `offline_access`: a device login joins the member's browser SSO session, so
   without an offline session every web logout would disconnect every client (observed on
   Keycloak 26.7.4 on 2026-09-26, WP 0.4; owner decision the same day). The offline session
   survives a web logout; removing the consent deletes it, and an admin logout of the member
   makes its tokens stale — both at once, also observed.
2. **DPoP is required** on every exchange route (new gateway code; `REQ-INGEST-012` amended).
3. **Installations.** A member may connect the same product from several PCs. An installation is
   identified by the thumbprint of its DPoP key (`cnf.jkt`) and labelled by the client (at most 40
   characters of letters, digits, space, `-`, `_`, `.`; never logged or audited; always shown after
   the registered client name).
4. **Revocation.** Disconnecting **one installation** puts its `jkt` on a **persistent deny list**
   (database, mirrored to Redis, kept at least as long as a client session can live), because an
   `iat` comparison alone would expire with the access token while the installation's refresh token
   mints fresh ones. Disconnecting **a whole client** removes its Keycloak consent (for a
   first-party client without consent: ends its client sessions) and stores a revocation
   timestamp; tokens issued before it are refused. The gateway reads both uncached. A departing
   member is logged out by the admin API, which also makes offline tokens stale.
5. **Reduced authentication.** On exchange paths the acting member holds an exchange role, the
   capability authorities of the token and the memberships the demand feed needs — never the
   member's full stored roles. An ArchUnit rule forbids exchange services from calling
   admin-gated methods (`REQ-SEC-036` extended).
6. **The registry lives in the backend database.** Clients, their granted capabilities, minimum
   version, state (active / suspended) and contact are managed by `ADMIN` on an admin page and
   mirrored into Redis for the gateway, which reads the mirror fail-closed. **The database alone
   decides capabilities**; there is no repo-reviewed ceiling and no Keycloak ceiling.
7. **Attribution.** The gateway asserts the external client in `X-Exchange-Client`; the acting
   authentication carries it, so audit rows and metrics name the client instead of `none`, with the
   vocabulary taken from the registry rather than `ApiClientMetricsProperties` (amends ADR-0152).
8. **Approval.** Case by case, closed source possible, recorded as a public issue plus a PR to
   `docs/legal/approved-clients.md` — the merge is the approval. Code signing of client releases is
   recommended, not required. A token-handling flaw must be fixed within 7 days, or the client is
   suspended.

## Consequences

- A stolen refresh token is useless without the installation's private key; a revoked installation
  cannot refresh its way back.
- An admin's client no longer runs with admin authority behind the relay.
- **Accepted risk.** With no repo-reviewed capability ceiling, unsigned client releases and a
  runtime admin switch, one admin click — or a taken-over admin or client maintainer account —
  grants or abuses write access at once. The controls are detection and reversal: the audit area
  „Verbundene Anwendungen", an alert on every registry change, journal and undo (ADR-0218), and
  suspension.
- **Device-code phishing** (RFC 8628 §5.4) remains possible with any public client id. It is
  countered, not prevented: a themed device page warns to enter only codes created on one's own PC,
  every new connection raises a notification and is highlighted in „Verbundene Anwendungen", and a
  device code lives 600 s.
- The minimum-version gate reads the `User-Agent` and is cooperative: it stops honest old releases,
  not a manipulated client.

## Alternatives considered

- **DPoP optional for third parties.** Rejected by the owner: a bearer token copied out of a
  backup or a diagnostics bundle would be fully usable.
- **Registry in the gateway's configuration, reviewed in the repo.** Rejected by the owner in favour
  of an admin page with a suspend switch; the accepted risk above is the price.
- **A Keycloak scope ceiling per client as a third check.** Rejected by the owner for the same
  reason: one place decides.
- **Open source only.** Rejected: approval is case by case.

## Amendment — 2026-09-26: how long a connection lives

Owner decision while building the Keycloak template (WP 2.2, #2081): a third-party client's
**offline session lives at most 30 days idle and 90 days in total** — the realm's own offline
bounds, pinned per client (`client.offline.session.idle.timeout`,
`client.offline.session.max.lifespan`) so a later realm change cannot lengthen them. A member
therefore re-connects a client after 30 days without use, and at the latest every 90 days. The
installation deny list of decision 4 keeps an entry **at least 90 days**. Shorter windows (14/30,
7/30 days) were offered and not chosen.
