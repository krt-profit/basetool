# ADR-0217 — Third-party clients are public device-grant clients, bound by DPoP and consent, approved in a database registry

- **Status:** Accepted — owner gate G0 of epic [#2078](https://github.com/krt-profit/basetool/issues/2078),
  taken with the merge of #2111 and #2112 (2026-09-26); implemented on main by 2026-09-28 (epic #2078), production rollout with the go-live
  ([#2092](https://github.com/krt-profit/basetool/issues/2092)). Amends [ADR-0152](0152-the-audit-row-records-which-client-a-mutation-came-through.md)
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

*Note 2026-09-28: the paragraph above is the state when the decision was taken (2026-09-26). On
main, the exchange routes require a DPoP-bound token and a proof (`ExchangeTokenGateFilter`), an
exchange call's acting member holds only the reduced exchange authentication
(`DatabaseActingMemberAuthorities.exchangeAuthoritiesFor`, REQ-XCH-009), and audit rows and API
metrics of relayed exchange calls name the registered client (`ClientAttribution`, REQ-XCH-010,
REQ-AUDIT-005). The legacy `/v1` extractor relay still acts with every stored role until it is
switched off (REQ-XCH-033).* *Amended 2026-09-28: it was switched off at the go-live and removed
with #2092 step 9; no relay acts with a member's stored roles any more.*

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
  suspension. *Amended 2026-09-27: the undo is no longer per member only — an admin undoes one
  client for every member at once, suspending it first
  ([ADR-0227](0227-an-admin-undoes-one-client-for-every-member-in-the-background.md)).*
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

## Amendment — 2026-09-27: what a client revocation compares

The go-live security review (#2092, findings M1 and L1; the owner approved the fix) found decision 4
incomplete for a client without consent. Keycloak 26.7's consent removal revokes a client's offline
sessions whether or not a consent exists, but ends its **online** client sessions only when one did;
the Admin API can end an online session only whole, including every other client in it. An online
refresh token of such a client therefore survived the disconnect, and each refresh minted a token
whose `iat` lay after the revocation, which the `iat` comparison admitted. The timestamp was also
written before the consent removal, so a refresh in that gap passed for up to the access-token
lifetime.

1. **The disconnect ends what Keycloak lets it end, then stamps.** The consent removal, then every
   online session of the member whose only client is this one, and only then the revocation time,
   read after Keycloak answered. A shared session — the usual case, since a device login joins the
   member's browser session — is left: ending it would sign the member out of the web.
2. **The gateway compares by the kind of token.** An offline token (scope `offline_access`) by its
   `iat`: Keycloak revoked every offline session of the client, so a later `iat` is a later
   connection. Any other token by its `auth_time`, which a refresh keeps; a token without
   `auth_time` is refused. Every exchange client carries `auth_time` through its default `basic`
   scope.
3. **`auth_time` alone was not taken for every token.** Keycloak keeps a user session's `AUTH_TIME`
   when a login is completed from the SSO cookie (`AuthenticationManager.redirectAfterSuccessfulFlow`,
   26.7.4), and the device flow honours neither `prompt` nor `max_age`. A reconnect that joins an
   older browser session would therefore stay refused, breaking „a new connection works at once"
   for the in-contract clients, which always request `offline_access`. The cost falls on clients
   that do not request it: after a disconnect they need a sign-in newer than it.
4. **Not chosen:** ending every session that holds the client (signs the member out of the web on
   each disconnect); a Keycloak SPI admin endpoint that ends only the client's sessions (precise,
   but a new privileged surface in Keycloak, left for the owner to decide); refusing tokens without
   `offline_access` outright (a larger contract change than the finding needs).

The owner chose the SPI endpoint the same day: [ADR-0226](0226-a-keycloak-admin-extension-ends-one-client-inside-a-shared-session.md)
ends the client inside shared sessions too, and the gateway's `auth_time` check of point 2 stays as
the backstop for a Keycloak without it.

## Amendment — 2026-09-28: the disconnect answers in the next second

E2E run 36388920243 (chromium 1280x800) found the first call of a device login started right after a
whole-client disconnect refused `401 CLIENT_REVOKED`. The revocation is stored as the epoch second of
the backend's clock, and a token counts as connected at or before it when its `iat` or `auth_time` —
whole seconds as well — is `<=` that second. A new connection issued within the revocation's own
second was therefore refused, although „a new connection works at once" is decision 4's promise.
The `<=` is what keeps a refresh of the old session in that same second refused (point 1 above), so
the comparison stays.

1. **The member's whole-client disconnect answers only once the backend clock has passed the stored
   second** (`ExchangeRevocationSecond`, called by `ConnectedAppsController` after the service's
   transaction committed): at most one second, holding no transaction and no lock. A device login the
   member starts after the page confirms the disconnect is issued in a later second and passes; the
   gateway and the backend share the host clock with Keycloak, which the comparison already assumes.
2. **Nothing else needs it.** A departure revokes a member who has lost access; they cannot connect
   again within the second. The reconcile rewrites stored times and stamps none. An installation
   revocation denies a key, not a time, and a reconnect needs a new key anyway.
3. **Not chosen** (owner decision 2026-09-28): telling clients to retry once after a second (moves the
   cost to third parties and breaks the promise); a strict `<` (reopens the same-second refresh of the
   old session for a whole access-token lifetime); a millisecond issue-time claim through a Keycloak
   mapper (precise, but an SPI, a realm, a mirror-format and two-gate change for a one-second window);
   denying the old session ids (a device login joins the same browser session and would be refused).

## Amendment — 2026-09-28: the online session is capped at 90 days too

The first amendment pinned only the **offline** session (30 days idle, 90 days total) and reasoned
that the installation deny list, kept 90 days, therefore outlives every token bound to a denied key.
That holds only for a client that requests `offline_access`. `offline_access` is optional on the
template, the gateway admits a token without it (judged by `auth_time`, amendment of 2026-09-27),
and an installation disconnect ends no Keycloak session. Such a client's online session had no
per-client bound, so it inherited the realm's SSO session maximum of **180 days**; Keycloak 26.7.4
caps every token at its client session's maximum lifespan
(`TokenManager.getTokenExpiration`, `SessionExpirationUtils.calculateClientSessionMaxLifespanTimestamp`),
which for that session was 180 days. A denied key could so be refreshed for up to about 180 days
after the disconnect while the Redis deny entry expired after 90, leaving only the backend's
unbounded database check behind it. The same held for a whole-client revocation whose online session
Keycloak did not end (a Keycloak without the session extension, a departure whose Keycloak step
failed): from day 90 both gates admitted it.

Owner decision (2026-09-28, security review of the retention sweep for #2092):

1. **Every exchange client's online session is capped as its offline session is**:
   `client.session.idle.timeout` 30 days and `client.session.max.lifespan` 90 days, pinned by
   `scripts/provision-keycloak-realm.py` on the third-party template and on `basetool-sc-extractor`,
   and carried into the sandbox and E2E realms. No session of an exchange client, online or offline,
   and no token issued in one, lives longer than 90 days after it began, so the 90-day deny list and
   revocation mirror outlive every session that existed at the revocation.
2. **A client without `offline_access` signs in again after at most 90 days** (30 days idle), as a
   client with it always had to. Nothing else changes for it.
3. **Production** receives the pins with the go-live's provisioner run; no separate host step.
4. **Not chosen:** keeping the deny list and its mirror 180 days (the privacy notice states 90);
   refusing tokens without `offline_access` at the gates (reverses the decision of 2026-09-27 that
   such a client works); accepting a denied key's return between day 90 and day 180; deleting only
   the label at 90 days and keeping the key until day 180.

## Amendment — 2026-09-28: disconnected connections are deleted after 90 days

Owner decision (2026-09-28, storage limitation, Art. 5(1)(e) GDPR): the installation deny list of
decision 4 and the per-member client revocations were kept for the life of the member's account.
They are now deleted **90 days** after the disconnect by a nightly sweep (REQ-XCH-035). 90 days
is the lifespan cap of every exchange session, online and offline, and the lifetime of the Redis
entries, so no token issued before a disconnect outlives the entry that refuses it.

1. **One transaction, in an order that shows nothing as connected again**: installations revoked on
   their own; then installations a whole-client disconnect ended (last seen at or before it), which
   carry no revocation of their own and are hidden only through the revocation row; then the
   revocations.
2. **The retention is configuration with a 90-day floor**, and never shorter than the change feed's
   and journal's retention, so the entries that name an installation never outlive it.
3. **Audited once per run that deleted anything**, with counts only: a system deletion in an audited
   area is still a mutation (REQ-AUDIT-001), and a run that deletes nothing leaves no row, so the
   trail does not grow by one row a night.
4. **Not chosen:** keeping the entries for the account's life (no purpose after the sessions have
   ended); marking installations revoked at a whole-client disconnect so one query suffices (a
   write to rows of every installation of the client at each disconnect, and a migration of the
   existing ones, for what one extra delete does).

## Amendment — 2026-09-28: the offline session needs the member's `offline_access` role

The amendments above assumed that a client requesting `offline_access` gets an offline session. It
does so only when the member holds the `offline_access` realm role within the client's scope, and
every exchange client has `fullScopeAllowed` off. Production's default role lacked the role
(hardening step 10), so SC Extractor 2.10.0's first device login on production was refused `400
not_allowed`. Owner decision (2026-09-28): `default-roles-basetool` carries `offline_access` and the
`offline_access` client scope maps it, as in the sandbox and E2E realms; the provisioner converges
both and never removes them ([ADR-0202 amendment 5](0202-a-realm-is-brought-to-the-production-shape-by-a-provisioner-that-never-deletes.md#amendment-5--2026-09-28-every-member-may-hold-an-offline-session),
which also lists which other clients could now issue an offline token). Nothing about the exchange
clients' own shape changes.
