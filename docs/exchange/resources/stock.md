# Stock

The member's stock in the warehouse, as lots. Reading needs `exchange.stock.read`, changing needs
`exchange.stock.write`.

## A lot

A [`stock-lot`](../schemas/) is the member's stock of one material or item at one warehouse
location, quality and stolen state: everything the member holds there, **personal and shared**,
**summed across the org-unit pools** it is booked in — what the member's own warehouse page in the web
shows. It has no org unit and no row id. Stock another member holds is never in a lot.

| Field | Meaning |
| --- | --- |
| `key` | Opaque identifier of the lot. |
| `material` | `{bt, name}`: a material, or a game item. `bt` is its Basetool id. |
| `materialKind` | Materials only, see [below](#material-kind). |
| `location` | `{name, uex?}` of the warehouse location. |
| `quality` | 0 to 1000. Every material lot has the quality it is booked at; an item lot has 0. |
| `stolen` | Whether the stock is marked stolen. |
| `quantity` | `{amount, unit}` in the material's own unit; SCU to three decimals, items in whole `PIECE`s. |

```json
{
  "key": "lot-1",
  "material": {"bt": "c3b1e0c2-8a4e-4f64-9a51-2b9f5c7e0a11", "name": "Laranite"},
  "location": {"name": "Area18", "uex": {"kind": "CITY", "id": 4}},
  "quality": 712,
  "stolen": false,
  "quantity": {"amount": 12.5, "unit": "SCU"}
}
```

### Material kind

`materialKind` classifies a material so a client can sort it into its own lists. It never decides
a lot's quality.

| Field | Meaning |
| --- | --- |
| `type` | `RAW` (goes into a refinery), `REFINED` or `NO_REFINE`. Always present. |
| `commodity` | Listed in UEX's commodity catalogue. Always present. That catalogue holds ores, refined metals and gems as well as trade goods, so `true` does **not** mean "a trade good without quality". |
| `mineral` | UEX: a mined mineral. |
| `harvestable` | UEX: harvested rather than mined. |
| `raw` | UEX: an unrefined raw material. |
| `refined` | UEX: the product of a refinery. |
| `buyable` | UEX: a terminal sells it. |
| `sellable` | UEX: a terminal buys it. |

The six UEX flags are left out for a material UEX does not know.

## Reading the lots — `GET /exchange/v1/me/stock`

A snapshot without `cursor`, the changes since it with one: the same `cursor`, `limit`, paging and
tombstones as [blueprints](blueprints.md#reading-the-set--get-exchangev1meblueprints). A lot whose
stock is all booked out, or moved to another member, arrives as a tombstone; rebooking stock between
personal and shared does not change its lot. A lot whose stock
changes while a snapshot is read may move to a later page; its change follows in the feed either way.

## Setting quantities — `POST /exchange/v1/me/stock/changes`

Send an `Idempotency-Key` and a change set of 1 to 500 `set-quantity` ops, `dryRun` optional. An op
names the lot and says what it should hold, and what the client last saw it hold:

```json
{
  "ops": [
    {
      "op": "set-quantity",
      "opId": "o1",
      "material": {"bt": "c3b1e0c2-8a4e-4f64-9a51-2b9f5c7e0a11", "name": "Laranite"},
      "location": {"name": "Area18", "uex": {"kind": "CITY", "id": 4}},
      "quality": 712,
      "stolen": false,
      "quantity": {"amount": 20, "unit": "SCU"},
      "expectedQuantity": {"amount": 12.5, "unit": "SCU"}
    }
  ]
}
```

`expectedQuantity` is the lot's quantity in the client's last synced state, and 0 for a lot the
client has not seen on the server. Only `opId` and `override` are optional. The server compares and
books SCU amounts rounded to three decimals.

The server decides the ops in order, as if the earlier ones had already run, and each op in these
steps; the first that fails ends it:

| Step | Refused as |
| --- | --- |
| `material` resolves as a material, else as an item | `unmatched` `UNMATCHED`, `ambiguous` `AMBIGUOUS` |
| `location` resolves to a warehouse location, never created | `rejected` `LOCATION_UNKNOWN` |
| `quantity` and `expectedQuantity` are in the material's unit — `PIECE` for an item | `rejected` `UNIT_MISMATCH` |
| `stolen: true` needs the Basetool's stolen marking, which may be switched off | `rejected` `STOLEN_MARKING_DISABLED` |
| The lot's stock is locked; its quantity must equal `expectedQuantity` | `rejected` `VERSION_CONFLICT` |
| A lot already at `quantity` | `unchanged` |
| An empty lot whose last change came from the web, the app, the system, another client or another installation | `rejected` `REMOVED_ELSEWHERE` |
| A fall larger than the lot's stock not reserved for a job order or mission | `rejected` `STOCK_EARMARKED` |

A material op addresses the lot at the `quality` it sends, whatever the material's kind; send 0 for
stock whose quality you do not know. An item op addresses the lot at quality 0, whatever `quality`
it sends. `REMOVED_ELSEWHERE` guards against refilling what the member emptied
elsewhere; ask the member, then resend with `override: true`. On `STOCK_EARMARKED`, ask the member
to release the reservation in the web. On `VERSION_CONFLICT`, pull the feed, merge, and send the op
again under a new `Idempotency-Key`.

## What a change books

A change is booked like the warehouse books it, audited in the warehouse under your client's name:

- **A rise** books the difference in as new personal stock without an org unit; counted pieces join
  the existing stock of that item as a book-in in the web does.
- **A fall** books the difference out, taking personal stock first, then stock without an org unit,
  then the oldest. It never takes stock reserved for a job order or mission.

**Material Exchange offers.** The Material Exchange is the Basetool's board of stock offers and
requests between members, unrelated to this API. A book-out that leaves less stock than an offer
of the member promises lowers that offer, or removes it when nothing is left — as in the web, and
audited. The result counts them in `offersReduced` and `offersRemoved`. An undo does not bring them
back, so tell the member when either is above 0.

**Stolen and not stolen.** A fall of a lot and a rise of its twin — same material, location and
quality, the other `stolen` value — in the same batch move the stock by re-marking it, not by
booking it out and in. As much as both ops allow is marked, part of a row split off where needed;
stock that backs a Material Exchange offer or is reserved stays as it is, and the rest is booked
out and in.

**Moves.** To move stock between locations or qualities, send the fall and the rise in one batch. A
fall counts as a move, not as a removal for the mass-change guard, when the batch's rises of the same
material or item cover all of it, falls taken in the batch's order. Sent in two batches, the fall
counts as a removal.

## The stock result

The [`change-result`](../schemas/) has the counts and `results[]` described for
[blueprints](blueprints.md#changing-the-set--post-exchangev1meblueprintschanges), plus the offers the
book-outs touched:

```json
{
  "dryRun": false,
  "applied": 3,
  "unchanged": 1,
  "notApplied": 1,
  "results": [
    {"index": 2, "result": "unchanged"},
    {"index": 4, "opId": "x", "result": "rejected", "reason": "VERSION_CONFLICT"}
  ],
  "offersReduced": 1,
  "offersRemoved": 0
}
```

`dryRun: true` decides every op and writes nothing; it neither marks stock nor asks the mass-change
guard. For the guard, a lot counts as removed when it is set to 0, or cut to at most a tenth of what
it held before your client's first change to it within the guard's 24 hours — see the
[sync guide](../sync-guide.md#the-mass-change-guard).

## Errors

| Code | When |
| --- | --- |
| `400 SCHEMA_INVALID` | The change set, `limit` or a parameter breaks the contract, for example a `PIECE` amount that is not whole. |
| `400 IDEMPOTENCY_KEY_MISSING` | A change set without a well-formed `Idempotency-Key`. |
| `409 MASS_CHANGE_CONFIRMATION_REQUIRED` | The batch removes too much; the member confirms it. |
| `410 CURSOR_EXPIRED` | The cursor is older than the tombstones, or not one the server issued. |
| `413 BATCH_TOO_LARGE` | More than 500 ops, or too large to hold for the member's confirmation. |

The full list is the [error registry](../errors.md).
