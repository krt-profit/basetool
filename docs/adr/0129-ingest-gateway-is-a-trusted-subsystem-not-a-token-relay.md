# ADR-0129 — The ingest gateway is a trusted subsystem, not a token relay

- **Status:** Accepted
- **Date:** 2026-08-04
- **Related:** spec `REQ-INGEST-001`, `REQ-INGEST-002`, `REQ-INGEST-011`, `REQ-INGEST-012`
  ([`desktop-ingest.md`](../specs/desktop-ingest.md)) · supersedes the relay decision in
  `REQ-INGEST-001` · [ADR-0127](0127-terms-consent-is-versioned-by-content-hash.md)

## Context

The ingest gateway forwards the desktop extractor's uploads to the backend. Until now it did so by
**relaying the caller's own access token**: `BackendImportClient` put the extractor's bearer straight
onto the second hop.

That design makes sender-constrained tokens impossible, and the attempt to have both broke every
send from 2026-08-03 onward.

The mechanism, established by disassembly rather than inference:

- The extractor presents a DPoP proof at Keycloak's token endpoint, so Keycloak binds the **access**
  token: it carries `cnf.jkt`.
- Spring Security 7.1's `BearerTokenAuthenticationFilter` authenticates such a token and *then*
  throws `invalid_token`, because RFC 9449 §7.1 forbids accepting a bound token under the `Bearer`
  scheme. The check lives in the **filter**, not in `DPoPAuthenticationProvider` — so it fires even
  on a resource server with no DPoP support configured at all.
- Production confirmed it: `basetool_ingest_auth_failures_total{reason="invalid_token"}` moved with
  the failing sends. (`reason="other"` is unrelated internet-scanner baseline — a misreading that
  cost several hours of triage.)

So a bound token cannot cross a relay hop, and an unbound one gains nothing from being bound. The
relay and sender-constraining are mutually exclusive.

## Decision

**The gateway stops relaying the caller's token. It validates the caller itself and calls the
backend under its own identity, naming the user it acts for.**

1. **Hop 1 — the extractor to the gateway.** `Authorization: DPoP <bound token>` plus a `DPoP:`
   proof, validated by the gateway via Spring Security's `.dPoP()`. Sender-constraining now pays:
   the party that validates the proof is the party that consumes the token, and this is the only
   internet-facing hop.
2. **Hop 2 — the gateway to the backend.** The gateway authenticates with its **own** service-account
   token (client credentials) and carries the validated caller in a dedicated header. The backend
   accepts that header **only** from the gateway's service-account principal.

### Why not RFC 8693 token exchange

It was the first choice and it does not work. Keycloak 26.7 refuses it, verified in
`keycloak-services-26.7.0.jar`, `StandardTokenExchangeProvider.tokenExchange()`:

```
141: isSenderConstrainedToken(token)   // = getConfirmation() != null — ANY cnf claim
149: token.getIssuedFor()              // azp
156: client.getClientId()              // the requesting client
173: "Sender-constrained token exchange rejected as the token was
      not issued for the requesting client"          → HTTP 400
```

Only the token's own client may exchange its sender-constrained token. The gateway can never be
`basetool-sc-extractor`. The check precedes audience and permission evaluation, so no amount of
realm configuration reaches past it.

The legacy v1 provider has no such check (`V1TokenExchangeProvider.supports()` is
`iconst_1; ireturn`), but it is a **preview** feature Keycloak is retiring. Production
authentication does not get built on a deprecated preview switch.

### What the trust boundary actually costs

**Amended 2026-08-04, with the repository owner's prior approval.** The first cut bounded the header
as "a subject and nothing else, honoured only on the two import endpoints", and substituted that
subject at the two call sites. Production showed why that is not enough: the Spring `SecurityContext`
still held the *gateway*, so `@PreAuthorize`, `@CurrentUserId`, the org-unit scope, the audit trail
and **both person-gates** judged a service account instead of the person sending. Attribution was
right and everything else was wrong — and the gates being wrong is the serious part, because consent
(REQ-SEC-028) and approval (REQ-SEC-017) covered the extractor precisely *because* the gateway used
to relay that person's token.

So the header now **selects the acting member as the security identity** for the request, and
`ActingMemberFilter` replaces the context before either gate runs. It therefore does grant roles and
select a scope — which the earlier wording forbade — but it grants the member's own, assembled from
the database, and never more.

That is exact rather than approximate because **every authority this application grants is already a
database read**: approval status, roles, per-role permissions, the org-unit membership flags and the
contextual and cascaded org-unit authorities. The access token contributes none of them directly.
The same assembler serves both paths, so a member acting through the gateway carries precisely the
authority set they would carry logging in.

Four guards bound it, and each closes a way this has gone wrong or could:

- **Only a configured gateway**, keyed on `azp` through one shared predicate, so the "may act for
  another member" and "is a machine, not a member" decisions cannot drift apart. Empty allowlist
  admits nobody.
- **Only the two import endpoints**, matched as parsed `PathPattern`s on the *decoded* path
  (REQ-SEC-029). The bound used to live in this document; now it lives in code.
- **Fail closed on a header with no authenticated caller** — refused, never ignored, so a future
  change to the authentication filters cannot silently reproduce the ordering bug that produced this
  amendment.
- **Liveness.** The database does not mirror whether an account still exists in Keycloak: the roster
  sync fetches `enabled` and never persists it, and the `inKeycloak` flag it does maintain is read by
  no authority code. A member disabled or deleted in the identity provider keeps `ACTIVE` and every
  role here, indefinitely. That is harmless while a token grants access — the account stops being
  issued tokens and the last one expires in minutes — and stops being harmless the moment a caller
  can *name* a subject, because a name never expires. So a subject with no local row is refused
  rather than created, and one the last sync no longer found is refused outright.

**A machine is not a member.** A Keycloak service account is a full user with a UUID `sub`, so the
first call from the gateway ran the entire registration flow on it: an `app_user` row, a PENDING
stamp, the default personal blueprints, an admin notification, and then a 403 on its own account. It
locked itself out on its first authentication. The authority converter now recognises a configured
gateway by `azp` and returns a single `ROLE_INGEST_GATEWAY` without touching the registration path.
The scheduled Keycloak roster sync needs no equivalent carve-out: measured against Keycloak 26.7,
`GET /admin/realms/{realm}/users` omits service-account users entirely even though the user exists.

## Consequences

**A regression this unlocks, which must be fixed in the same change.** `.dPoP()` installs a *separate*
`AuthenticationFilter`, and `FilterOrderRegistration` places it **after** `BearerTokenAuthenticationFilter`.
The gateway's `UserIdMdcFilter` and `ClientIdentityFilter` are anchored relative to
`BearerTokenAuthenticationFilter`, so on a DPoP-scheme request they would run against an empty
`SecurityContext`: `ClientIdentityFilter` finds no JWT, returns early, and the **REQ-INGEST-011
approved-client allowlist is silently skipped** while `.anyRequest().authenticated()` still passes.
Fail-open, no error anywhere. Both filters are therefore re-anchored on the DPoP
`AuthenticationFilter`.

**No flag day.** `.dPoP()`'s filter is gated on `matchesDPoPRequest`, so it coexists with the bearer
filter and both schemes are accepted during the transition. An old extractor sending an *unbound*
bearer keeps working. A v2.7.x client sending a *bound* token under the `Bearer` scheme stays broken
— that is the defect being fixed and those installs must update.

**`htu` is compared byte-exactly.** Spring builds it from `HttpServletRequest#getRequestURL()` and
validates with `String.equals` — no normalisation, no trailing-slash tolerance. Behind
nginx-proxy-manager that value depends on the forwarded headers, so the proxy's
`X-Forwarded-Proto`/`-Host`/`-Port` and the extractor's signed `htu` have to agree exactly. This is
the most likely way the change fails in production while passing every test.

**Operator work.** The realm needs a confidential client with a service account for the gateway, and
a role marking it as the ingest gateway. That is applied by @greluc; production is off-limits here.

**Rejected alternatives.**

- *Keep relaying, drop the binding.* Works immediately and is one line, but gives up
  sender-constraining entirely — the protection this whole line of work exists to obtain.
- *Strip `cnf` in the gateway's decoder so the bearer filter stops objecting.* Defeats RFC 9449 §7.1
  by pretending a bound token is unbound. It would make a stolen token replayable, which is exactly
  what the binding prevents.
- *Trust an on-behalf-of header from anyone on the internal network.* The header is only as good as
  the authentication behind it; without pinning it to the gateway's service account it is an
  impersonation primitive.

