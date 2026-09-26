# ADR-0216 — The exchange API is a separate, capability-scoped contract on the ingest gateway

- **Status:** Accepted — owner gate G0 of epic [#2078](https://github.com/krt-profit/basetool/issues/2078),
  taken with the merge of #2111 and #2112 (2026-09-26); implementation pending. Amends [ADR-0018](0018-desktop-ingest-gateway-device-grant.md) and
  [ADR-0129](0129-ingest-gateway-is-a-trusted-subsystem-not-a-token-relay.md) (the relay route
  list and two new trusted headers) and narrows
  [ADR-0135](0135-public-api-vhost-not-a-gateway.md) (exchange paths never join its allowlist).
- **Date:** 2026-09-26
- **Deciders:** @greluc
- **Related:** spec [`external-exchange.md`](../specs/external-exchange.md) (`REQ-XCH-001`…) ·
  [`desktop-ingest.md`](../specs/desktop-ingest.md) (`REQ-INGEST-001`, `-010`, `-011`, `-012`) ·
  [ADR-0217](0217-third-party-clients-are-public-device-grant-clients-in-a-db-registry.md) ·
  [ADR-0218](0218-exchange-sync-semantics.md) ·
  [ADR-0219](0219-the-exchange-contract-grows-additively-under-a-path-major-version.md)

## Context

The developer of VerseKit, a desktop toolkit for Star Citizen, asked to be authorised as a client
so members can keep blueprints, their personal Lager and their ships in step between the two
tools. More such requests are expected, and our own SC Extractor already talks to the ingest
gateway through two bespoke endpoints (`/v1/refinery-extract`, `/v1/blueprint-preview`).

The backend API is the web's and the app's internal contract. It is role-driven, grants what the
member's roles allow (an admin's token can do anything an admin can), and changes with every
feature. The app reaches a default-deny allowlist of it through the `api.*` vhost (ADR-0135), which
admits `/api/v1/me/` as a prefix. Handing foreign software that surface would freeze an internal
contract and give it far more than it needs.

The ingest gateway already sits on its own internet-facing host, validates the caller's token and
its DPoP proof at the edge, polices which software calls through the `azp` allowlist
(REQ-INGEST-011), and calls the backend under its own service account naming the member in
`X-Ingest-On-Behalf-Of` (ADR-0129).

## Decision

We will open the Basetool to approved external client software only through a separate
**exchange API**, `/exchange/v1/**`, served by the ingest gateway.

1. **Own contract.** The exchange API is designed for third parties: own resources, own JSON
   Schemas and OpenAPI file, own error-code registry, own stability rules (ADR-0219). It exposes
   the member's own blueprints, own personal Lager lots, own ships, an anonymised demand aggregate
   (ADR-0220) and review-in-browser drafts — nothing else.
2. **Capabilities are the unit of approval.** A client is approved for capabilities (OAuth scopes
   such as `exchange.blueprints.write`), never for endpoints. A later client's need is met by a new
   capability on the same surface, not by a new endpoint pair.
3. **The gateway enforces, the backend re-checks.** The gateway checks registry, revocation,
   capability, DPoP, version, schema, quota and idempotency, then relays under ADR-0129 to a
   separate backend **exchange layer** at `/api/v1/exchange/**`. That layer is the one place that
   enforces own-data-only, journals every write and publishes live-sync frames; it calls the
   existing domain services, so the web's validation and audit apply unchanged.
4. **The relay route list grows explicitly.** `ActingMemberFilter.ACTING_PATHS` stays an exhaustive
   list and gains the exchange routes one by one — never a `/api/v1/exchange/**` prefix. The gateway
   additionally asserts `X-Exchange-Client` and `X-Exchange-Capabilities`; the backend trusts both
   only from the gateway's service identity and refuses them from any other caller (amends ADR-0129
   and ADR-0018).
5. **Member controls live outside the relay.** Connected apps, revoke, activity, undo and the
   confirmation of a staged mass change are served at `/api/v1/connected-apps/**` to the member's
   own browser session only, so a client can never undo, confirm or revoke by itself.
6. **Never on the `api.*` vhost.** Neither `/api/v1/exchange/**` nor `/api/v1/connected-apps/**`
   is ever added to the ADR-0135 allowlist, and nothing of the exchange lives under `/api/v1/me/`
   (narrows ADR-0135).
7. **The legacy extractor endpoints end at the go-live.** The SC Extractor migrates to the exchange
   API in a release shipped in parallel; from the go-live the legacy `/v1/*` routes answer `410`
   with a German update hint, behind a flag.

## Consequences

- One surface serves every external client, including our own extractor, which dogfoods it.
- The gateway stops being stateless: it keeps idempotency results and daily quotas in Redis
  (ADR-0221) and a little policy logic. REQ-INGEST-001 and REQ-INGEST-011 are amended accordingly.
- The backend grows an exchange layer that duplicates no domain logic but maps an external,
  frozen contract onto internal services that may change; a change there must keep the exchange
  contract tests green.
- `IngestPathScope` has to split into a protective scope (both path families) and the legacy client
  gate (`/v1/**` only), or third-party clients would hit the extractor allowlist.

## Alternatives considered

- **Expose the backend API to approved clients.** Rejected by the owner: too powerful for foreign
  software, role-driven rather than capability-driven, and it would freeze the internal contract.
- **A bespoke endpoint pair per client.** Rejected: the next client would need another pair, and
  each would carry its own security review.
- **Per-member API keys.** Rejected: no consent, no expiry, no sender binding; a key in a JSON file
  under `Documents` is exactly the failure this design guards against.
- **A second gateway just for the exchange.** Rejected: the ingest gateway already has the edge
  host, the token validation, the DPoP code and the relay; a second one would duplicate all of it.
