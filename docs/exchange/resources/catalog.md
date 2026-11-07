# Catalogue

Two routes that turn a client's own names for things into the Basetool's. Either is open to a token
with any exchange scope the registry grants, `exchange.connect` included, and neither is a write.

## Resolve references — `POST /exchange/v1/catalog/resolve`

The body is a [`resolve-request`](../schemas/): one `kind` and 1 to 500
[item references](../formats.md) of that kind.

```json
{
  "kind": "BLUEPRINT",
  "refs": [
    {"bt": "bp-cf-337-panther-repeater", "name": "CF-337 Panther Repeater"},
    {"scRecord": "bp_craft_arclight"},
    {"name": "Unknown Thing"}
  ]
}
```

What each field is matched against, per kind:

| `kind` | `guid` | `bt` | `scRecord` | `scGuid` | `uexId` | `locKey` | `name` |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `BLUEPRINT` | the produced item's GUID, the product's key | the product key, the same value as a blueprint's feed `key`, or a name key stored earlier (deprecated) | the blueprint's Wiki key | the blueprint's Wiki or game-file UUID, or its output item's | the output item's UEX id | the output item's name key | the web import's chain: exact, alias, pack-tag strip, fuzzy |
| `ITEM` | — | the item id | the class name | the Wiki or game-file UUID | the UEX item id | the name key | exact, ignoring case |
| `MATERIAL` | — | the material id | the Wiki key | the Wiki or game-file UUID | the UEX commodity id | the name key | exact, canonical, alias, fuzzy |
| `SHIP_TYPE` | — | the ship type id | the class name, else the base of its variant group | the game-file GUID or the Wiki UUID | the UEX vehicle id, else its curated class-name alias | the name key | the hangar import's ship-type matching; a name several ship types share is `ambiguous` |

A reference takes the first of its fields, in the order `guid`, `bt`, `scRecord`, `scGuid`, `uexId`,
`locKey`, that resolves to exactly one entry. If none does, a `name` that resolves to exactly one
entry wins; otherwise the first field that resolved to several decides, and a reference with
nothing that resolved is `unmatched`. `v1` accepts `nameLocale` but does not use it for matching:
send `locKey`, which is the same in every language, whenever you have it.

The answer is a [`resolve-response`](../schemas/) with one result per reference, in request order:

```json
{
  "results": [
    {"index": 0, "status": "resolved",
     "ref": {"guid": "de20695c-eb2f-4b68-be6f-9806cbd205a7",
             "bt": "de20695c-eb2f-4b68-be6f-9806cbd205a7", "name": "CF-337 Panther Repeater"}},
    {"index": 1, "status": "ambiguous",
     "candidates": [{"bt": "a", "name": "A"}, {"bt": "b", "name": "B"}]},
    {"index": 2, "status": "unmatched"}
  ]
}
```

| `status` | Carries | What a client does |
| --- | --- | --- |
| `resolved` | `ref` with `bt`, the Basetool's `name` and, for a blueprint keyed by its produced item, `guid` | Store `guid` for a blueprint, else `bt`, and send it from then on. |
| `ambiguous` | `candidates`, at most ten `{guid, bt, name}` | Let the member pick. A fuzzy suggestion is always `ambiguous`, even alone. |
| `unmatched` | nothing | Show it to the member; do not send it in a change set. |

`warnings[]` holds at most 50 `{pointer, code}`: `LOC_KEY_UNRESOLVED` at `/refs/<i>/locKey` for a
reference with a `locKey` whose key fields did not resolve, and `UNKNOWN_FIELD` for a field the
schema does not declare.

The change sets resolve their references the same way, so resolving first is optional. It lets a
client settle `ambiguous` and `unmatched` with the member before it writes, instead of reading them
back as per-op results. A stock op resolves its `material` as a `MATERIAL` first and, when no
material matches, as an `ITEM`.

## Warehouse locations — `GET /exchange/v1/catalog/locations`

No parameters. The answer is a [`location-list`](../schemas/): the warehouse's non-hidden locations,
ordered by name, each with its UEX city or space station when it is linked to one (a city link wins).

```json
{
  "items": [
    {"name": "Area18", "uex": {"kind": "CITY", "id": 4}},
    {"name": "Seraphim Station"}
  ]
}
```

These are the only places a stock lot or a ship can be at. A `location` in a change set resolves by
its `uex` link first, then by the exact `name` of a non-hidden location; a place with neither is
refused per op as `LOCATION_UNKNOWN` and is never created. Offer the member a place from this list
instead. The list changes rarely; reading it once per session is enough.

## Errors

| Code | When |
| --- | --- |
| `400 SCHEMA_INVALID` | The request breaks `resolve-request`, for example more than 500 references or a reference without any field. |
| `403 SCOPE_MISSING` | The token carries no exchange scope at all that the registry grants. |

The full list is the [error registry](../errors.md).
