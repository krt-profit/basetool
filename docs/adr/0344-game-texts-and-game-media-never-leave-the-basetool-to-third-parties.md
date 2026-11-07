# ADR-0344 — Game texts and game media never leave the Basetool to third parties

- **Status:** Accepted
- **Date:** 2026-11-05
- **Deciders:** @greluc
- **Related:** the P4K catalogue spec (REQ-P4K-033, REQ-XCH-050) · the exchange spec (REQ-XCH-036) · the observability spec ·
  #2894 · #2821 · map #2884 ·
  [p4kx catalogue contract](https://github.com/p4kx-org/p4kx/blob/main/docs/basetool-catalog.md)

## Context

Identifiers, short names and fact values (green) may reach third parties; game texts (yellow) and
game media (red) may not. The P4K catalogue marks every game text with `"x-gameText": true` —
every `description`, every `displayType`, a starmap object's `name` and the contract texts. The
Basetool's description columns are filled by several writers (SC Wiki, UEX, P4K, admin), and some
columns mix them (`Manufacturer.description`, `Material.description`).

Nothing yellow or red leaves today: no exchange DTO or schema carries a description,
`displayType`, lore, image or URL field, and the exchange queries (`EXCHANGE_LOTS`,
`ExchangeShipRow`) never select the description columns. The gap is structural: no schema sets
`additionalProperties: false`, and the exchange gateway passes undeclared answer fields through
unchanged.

## Decision

1. **Columns are classified by content, one class per column.** `@GameText` (yellow) marks every
   description column, whichever writer fills it: `GameItem.description_en` / `_de`,
   `ShipType.description_en` / `_de`, `Manufacturer.description` (and the coming
   `description_de`), `Material.description`, and `displayType` should it ever be persisted.
   `@GameMedia` (red) is defined and sits on no column: no archive media is in scope, and the UEX
   link and image columns (`uex_screenshot`, `url_photo`, `url_video`, `url_store`, …) stay
   unmarked UEX data.
2. **An ArchUnit rule** forbids code of an outbound integration (the `exchange` domain) to call the
   getter of a `@GameText` or `@GameMedia` field.
3. **A field ratchet** covers the backend exchange DTOs (recursively) and every property of the
   published v1 schemas / OpenAPI: each field is listed with a green class (identifier, short name,
   fact value, member free text, client data). An unlisted field fails the build, yellow or red is
   forbidden, and DTO and schema must agree. The model is `CredentialFieldRatchetTest`.
4. **The gateway fails closed.** An undeclared property in a backend answer yields `502`, as a
   schema violation does. The content of a declared free-form map (`extensions`,
   `additionalProperties` with a schema) passes verbatim.
5. **A broken answer contract is measured.** `basetool_ingest_exchange_relay_total` gains the
   outcome `answer_contract_broken`, covering the existing answer schema violations too, with an
   alert on > 0. The WARN log names route and field path, never the value.
6. **The rule covers every outflow to third parties.** The guards cover the exchange; any new
   outbound integration (the VerseKit sync, #2821) joins the ArchUnit rule and the ratchet.

## Consequences

- A game text on its way to a third party is stopped by a build gate or by the gateway, not only
  by review.
- The fail-closed gateway changes the frozen exchange path (REQ-XCH-036): new goldens and the
  `e2e` label. A new exchange DTO field is declared in the v1 schema in the same change, which the
  ratchet enforces.
- The exchange spec gains a requirement and a threat-model row; the P4K catalogue spec gains the
  classification requirement; the observability spec and the monitoring rules gain the outcome and
  its alert.

## Alternatives considered

- **Classification by source** (a column is yellow because a given writer fills it): mixed columns
  such as `Manufacturer.description` and `Material.description` cannot be classified per column
  that way. Rejected.
