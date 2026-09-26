# ADR-0219 — The exchange contract grows additively under a path major version

- **Status:** Proposed — epic [#2078](https://github.com/krt-profit/basetool/issues/2078); nothing
  built yet.
- **Date:** 2026-09-26
- **Deciders:** @greluc
- **Related:** spec [`external-exchange.md`](../specs/external-exchange.md) ·
  [`api-conventions.md`](../specs/api-conventions.md) ·
  [ADR-0216](0216-the-exchange-api-is-a-separate-contract-on-the-ingest-gateway.md)

## Context

Third-party clients ship on their own schedule; VerseKit released 159 times in four weeks, and some
members will run old releases for months. The backend API may break a DTO in the same PR that
updates the web and the app. The exchange API cannot: every change reaches clients we do not build.

## Decision

We will hold the exchange contract to these rules.

1. **Major version in the path** (`/exchange/v1`). Within a major version only additive changes are
   allowed: new optional fields, new resources, new capabilities, new enum values, new error codes.
   Anything else is a new major version, served in parallel for **at least 12 months** with
   `Deprecation` and `Sunset` headers.
2. **Tolerant reader, both ways.** Unknown fields are ignored; the server reports the ones it
   ignored as `warnings`. Unknown enum values read as `UNKNOWN`. The published schemas stay open
   (`additionalProperties` not false); server-side tests may be strict.
3. **Namespaced extensions.** Client-specific data travels in
   `extensions: {"<reverse-DNS id>": {…}}` and is never interpreted unless registered.
4. **Opaque identifiers and cursors.** Clients never parse them.
5. **Formats are schemas.** JSON Schema 2020-12, one OpenAPI 3.1 file, both committed and served
   by the gateway; each schema's `$id` is its permanent URL,
   `https://ingest.profit-base.online/exchange/v1/schemas/<name>.schema.json`, on our own domain
   rather than a hosting provider's, and never changes once published. The offline file envelope
   carries `format` and `formatVersion` (`major.minor`).
6. **Stable errors.** RFC 9457 problem+json with a `code` from a registry; codes are never reused
   or repurposed.

## Consequences

- A contract test pins every published schema, and CI fails a change that removes or narrows
  anything within `v1`.
- The exchange layer, not the domain DTOs, owns the mapping, so internal refactors stay free.
- A breaking need costs a parallel `v2` for a year; that price is intended.

## Alternatives considered

- **Header or media-type versioning.** Rejected: harder to see in logs, proxies and the docs.
- **Strict schemas that reject unknown fields.** Rejected: every additive server change would break
  older clients.
