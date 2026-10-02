# Formats

Every body the Exchange API sends or receives is described by a JSON Schema 2020-12 file. Each
schema is served, unchanged and without sign-in, at its permanent `$id`:
`https://ingest.profit-base.online/exchange/v1/schemas/<name>.schema.json`. A copy is on this site
under [schemas](schemas/), and every schema has valid and invalid
[conformance fixtures](examples/README.md).

Every request body is a JSON document sent with `Content-Type: application/json`; another media
type is `415 UNSUPPORTED_MEDIA_TYPE`, and a body that is not a JSON document is `400 SCHEMA_INVALID`
with one error at the pointer `""`. Neither answer is cached for its `Idempotency-Key`. The gateway
checks each body against its schema before it relays the request. A violation is
`400 SCHEMA_INVALID` with `errors[]`, each a JSON Pointer and the violated keyword, at most 50.

## Item reference — `item-ref`

Names one catalogue entry: a blueprint product, an item, a material or a ship type. Send every
field you know; the server takes the **first** field, in this order, that resolves to exactly one
entry:

| Field | What it is |
| --- | --- |
| `bt` | The Basetool's own key, as the feed and `catalog/resolve` answer it. Always wins. |
| `scRecord` | The DataForge record name (the scmdb `tag`), case-insensitive. Not unique. |
| `scGuid` | The game entity GUID. |
| `uexId` | The UEX id. |
| `locKey` | The `global.ini` name key without its `@`, case-insensitive. |
| `name` + `nameLocale` | The name as the game shows it. `nameLocale` is accepted; names are matched regardless of it, so prefer `locKey` for a non-English name. |

When no field resolves to exactly one entry, the first that resolved to several decides: the answer
is `ambiguous` with at most ten candidates `{bt, name}`. Fuzzy suggestions are always `ambiguous`,
even a single one, because they are never taken without the member. Store the `bt` the server answers
and send it from then on.

`POST /exchange/v1/catalog/resolve` resolves up to 500 references of one `kind` (`BLUEPRINT`,
`ITEM`, `MATERIAL`, `SHIP_TYPE`) at a time. A reference with a `locKey` whose key fields, the
`locKey` included, did not resolve to exactly one entry falls through to the name and adds a
`LOC_KEY_UNRESOLVED` warning at `/refs/<i>/locKey`.

## Quantity — `quantity`

`{amount, unit}`. `unit` is `SCU` or `PIECE` and must match the material's own unit
(`UNIT_MISMATCH` otherwise). A PIECE amount is whole. An SCU amount may carry any number of
decimals: the server rounds it half-up to three before it stores or compares it, so `expectedQuantity`
`12.3456` matches a stored `12.346`. Send three decimals at most to see in the feed what you sent.

## Quality — `quality`

An integer from 0 to 1000. A material is stored at the quality the client sends, whatever its kind;
send 0 when the quality is not known. An item has none and is stored at 0.

## Place — `location-ref`

A warehouse location, by `uex: {kind: CITY | SPACE_STATION, id}`, by exact `name`, or both; the
UEX link is tried first. The **warehouse** is the Basetool's record of the org's stock, the
*Inventory* area of the web app; its locations are the places stock and ships can be recorded at.
`GET /exchange/v1/catalog/locations` lists them. A place without a warehouse location is
`LOCATION_UNKNOWN`; it is never created.

## Provenance — `provenance`

`{source, observedAt}` with `source` one of `log`, `manual`, `import`, `default`, `other`. Send it
with a blueprint you add; the feed answers it for every blueprint whose source the Basetool
recorded. A default grant is the server's alone, so a client's `default` is stored as `other`.

## Material kind — `material-kind`

Read-only: `{type: RAW | REFINED | NO_REFINE, commodity}` and, where UEX knows the material, its
flags `mineral`, `harvestable`, `raw`, `refined`, `buyable` and `sellable`, so a client can route a
material to its own lists. `commodity` is true for a material listed in UEX's commodity catalogue,
ores and refined metals included. None of it decides the quality; see
[stock](resources/stock.md#material-kind).

## Offline file — `envelope`

The wrapper of a file a client writes for the member:

```json
{
  "format": "basetool.blueprints",
  "formatVersion": "1.0",
  "generator": {"name": "Example Client", "version": "1.2.0"},
  "generatedAt": "2026-09-27T12:00:00Z",
  "items": []
}
```

An envelope carries no handle, player name, source folder or file path. `basetool.blueprints` is the
blueprint format: each item is `{ref, acquiredAt?, provenance?}`, at most 2000 per file. The member
can upload such a file in the web's blueprint import, and a client can send the same document to
`POST /exchange/v1/me/drafts/blueprints`; both end in the same review.

- **Size.** The web import refuses a file larger than 8 MiB (8 388 608 bytes) before reading it; the
  draft route takes a body of at most 2 MiB.
- **`formatVersion`.** Write `1.0`. Every `1.x` is read as this page describes; a later minor only
  adds optional fields. A major other than `1` is refused: the draft route answers
  `400 SCHEMA_INVALID` with `errors[{"pointer":"/formatVersion","message":"unsupported major
  version"}]`, and the web import refuses the file. The part before the dot must be exactly `1`, so
  `01.0` is refused too.
- **What is checked.** The draft route checks the whole envelope against its schema and the major
  version. The web import checks `format`, `formatVersion` when it is present (it must be a `1.x`),
  and the items — at most 2000, each with a `ref` — and ignores `generator`, `generatedAt`,
  `provenance` and `extensions`. Write files that pass the schema anyway.
- **Provenance.** A blueprint taken over from the review is recorded with the source `import`,
  whatever the item's `provenance` says.

## Extensions — `extensions`

Where a schema allows `extensions`, it is an object of up to ten reverse-DNS keys
(`io.github.example.client`), each holding an object. The Basetool keeps them apart from its own
fields and never interprets an extension it has not registered.
