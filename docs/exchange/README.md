# Profit Basetool — Exchange API

The Exchange API lets **approved** third-party applications exchange a member's own data with the
Profit Basetool: their blueprints, their stock, their ships, the anonymous open demand of
their units, and drafts the member reviews in the browser. It is not a general-purpose API. Every
client is approved publicly and case by case, and every member decides which client may do what.

## At a glance

| | |
| --- | --- |
| Base URL | `https://ingest.profit-base.online/exchange/v1` |
| Sign-in | OAuth 2.0 device authorization grant against `https://profit-base.online/auth/realms/iri`, public client, no secret — [authentication](authentication.md) |
| Proof of possession | DPoP (RFC 9449, ES256) on every token request and every call |
| Formats | JSON Schema 2020-12, served at `https://ingest.profit-base.online/exchange/v1/schemas/<name>.schema.json` |
| Reference | [OpenAPI reference](reference/) · [OpenAPI document](reference/exchange-v1.openapi.json) · [schemas](schemas/) |
| Errors | RFC 9457 problem+json with a stable `code` — [error registry](errors.md) |
| Versioning | additive within `v1` — [versioning](versioning.md) |

## Capabilities

A capability is an OAuth scope. A request passes only when the route's scope is in the token **and**
the registry grants it to the client.

| Scope | Allows |
| --- | --- |
| `exchange.connect` | the service document, labelling the installation, the account check |
| `exchange.blueprints.read` / `.write` | reading and changing the member's blueprints |
| `exchange.stock.read` / `.write` | reading and setting the member's stock lots, personal and shared |
| `exchange.hangar.read` / `.write` | reading and changing the member's own ships |
| `exchange.demand.read` | the anonymous open demand of the member's units |
| `exchange.drafts.blueprints` / `.refinery` | staging blueprints or refinery orders for review in the browser |

`catalog/resolve` and `catalog/locations` are open to any exchange scope.

## Pages

- [Quick start](quickstart.md) — from the sandbox to a first synced blueprint in about fifteen
  minutes.
- [Authentication](authentication.md) — the device login, DPoP proofs, the server nonce,
  refreshing, disconnecting, and the [DPoP reference implementation](dpop-reference/README.md).
- [Formats](formats.md) — item references, quantities, qualities, places, provenance, the offline
  file envelope.
- [Errors](errors.md) — every `code`, its status and what a client does about it.
- [Versioning](versioning.md) — the stability promise and how the contract grows.
- [Client security requirements](client-security.md) — sign-in, DPoP, token storage, releases.
- [Becoming an approved client](onboarding.md) — how to apply and what approval means.
- [Local sandbox](sandbox.md) — the Basetool on your own machine, with a test client and synthetic
  members, to build and test a client against: requirements, test scenarios, troubleshooting.
- [Conformance fixtures](examples/README.md) — valid and invalid examples for every schema.
- [Sync guide](sync-guide.md) — pull before push, baselines, tombstones, conflicts, idempotency, the
  mass-change guard, rate limits and back-off. Read it before writing a sync.
- [Changelog](changelog.md) — contract changes, dated.

## Resources

- [Connect](resources/connect.md) — the service document, labelling the installation, the account
  check.
- [Catalogue](resources/catalog.md) — resolving item references, the warehouse locations.
- [Blueprints](resources/blueprints.md) — the blueprint feed and blueprint changes.
- [Stock](resources/stock.md) — stock lots, setting quantities, what a book-out does.
- [Ships](resources/ships.md) — the ship feed, links, upserts and removals.
- [Org demand](resources/org-demand.md) — the anonymous open demand of the member's units.
- [Drafts](resources/drafts.md) — blueprint and refinery drafts reviewed in the browser.

## Support

Questions about an application or an approved client go to the issue tracker of
[krt-profit/basetool](https://github.com/krt-profit/basetool/issues). Report a security problem
privately as its [security policy](https://github.com/krt-profit/basetool/security/policy)
describes, never in a public issue.
