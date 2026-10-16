# Org stock

`GET /exchange/v1/org-stock`, scope `exchange.orgstock.read`: the shared warehouse stock of the org
units the member directly belongs to, one pool per unit. With `exchange.orgstock.write`, a
Logistician of a unit also books, sells, moves and edits the stock of that unit's pool, see
[Writing](#writing).

## Which pools

Each unit the member is a **direct member** of is a **pool**: the stock its members booked into the
unit's shared warehouse. All four kinds of unit count, as for [org units](org-units.md). A unit the
member only oversees — the squadrons below an area they lead — is not served, and an administrator's
role adds nothing: the exchange acts with the member's own memberships only. Personal stock of any
member and shared stock booked to no unit never appear.

## A lot

An [`org-stock-lot`](../schemas/) is one pool's stock of one material or item at one warehouse
location, quality and stolen state, **summed over every member** who holds stock there. The same
material at the same place in two of the member's units is two lots. A lot names no holder; the
single entries are an extra, see [Entries](#entries).

| Field | Meaning |
| --- | --- |
| `key` | Opaque identifier of the lot. |
| `unit` | `{id, name}` of the unit whose pool holds the lot. |
| `material` | `{bt, name}`: a material, or a game item, as in a [stock lot](stock.md#a-lot). |
| `materialKind` | Materials only, as in a [stock lot](stock.md#material-kind). |
| `location` | `{name, uex?}` of the warehouse location. |
| `quality` | 0 to 1000; an item lot has 0. |
| `stolen` | Whether the stock is marked stolen. |
| `quantity` | `{amount, unit}`, the summed amount in the material's own unit. |
| `earmarked` | `{jobOrders, missions}`: how much of `quantity` is reserved for job orders and for missions, each a `{amount, unit}` in the lot's unit; `0` when nothing is. |
| `entries` | Only with `exchange.orgstock.details`, in a pool where the member is a Logistician: the single entries, see [Entries](#entries). Absent otherwise. |

```json
{
  "key": "u:7d1c2f4e-3b8a-4c55-9e21-6a0f3b2d9c10|m:c3b1e0c2-8a4e-4f64-9a51-2b9f5c7e0a11|l:5e2a9d71-0c4b-4f8e-b3a6-1d7c8e9f0a22|q:500|s:0",
  "unit": {"id": "7d1c2f4e-3b8a-4c55-9e21-6a0f3b2d9c10", "name": "Iridium"},
  "material": {"bt": "c3b1e0c2-8a4e-4f64-9a51-2b9f5c7e0a11", "name": "Laranite"},
  "materialKind": {"type": "RAW", "commodity": true},
  "location": {"name": "Area18", "uex": {"kind": "CITY", "id": 4}},
  "quality": 500,
  "stolen": false,
  "quantity": {"amount": 48.25, "unit": "SCU"},
  "earmarked": {
    "jobOrders": {"amount": 12, "unit": "SCU"},
    "missions": {"amount": 0, "unit": "SCU"}
  }
}
```

## Entries

With the additional scope `exchange.orgstock.details`, every lot of a pool in which the member is a
**Logistician of that unit** carries `entries`: one per holder's stock, as the Basetool shows a
Logistician in its warehouse. The member is a Logistician of a unit when their membership in exactly
that unit carries the Logistician flag or a leadership rank; leading an area does not make them one
of the squadrons below it. In every other pool, and without the scope, lots carry no `entries`. The
scope asks nothing of its own: a request without it is answered without entries, not refused.
When the member becomes or stops being a Logistician of a unit, the feed delivers that unit's lots
again, with or without `entries`. When your token gains the scope, read a new snapshot; when it
loses it, drop the entries you kept.

| Field | Meaning |
| --- | --- |
| `id`, `version` | The entry and its version. |
| `holder` | `{withheld: false, name, ref}`: the name the Basetool shows and `ref`, a pseudonym of the member that stays the same at your client and differs at every other client. Use `ref`, not the name, to recognise a holder. `{withheld: true}` alone when the member keeps their name away from connected applications. |
| `quantity` | The entry's amount, in the lot's unit. |
| `note` | The entry's note, when it has one and the holder is not withheld. |
| `jobOrders` | `[{id, number?, quantity, delivered}]`: the job orders it is earmarked to, `number` shown as `#number`. |
| `missions` | `[{id, name?, quantity}]`: the missions it is earmarked to. |
| `free` | `{jobOrders, missions}`: the part not earmarked, per dimension. |

```json
"entries": [
  {
    "id": "9a7c2e10-4f3b-4d21-8c6e-2b1a0f9d8e31",
    "version": 3,
    "holder": {"withheld": false, "name": "Hauler One", "ref": "q0Fh3kB9xV7m2LwP5nR8tY1cZ4dE6gJ0sU3aH7bK9fM"},
    "quantity": {"amount": 30, "unit": "SCU"},
    "note": "Hangar 3",
    "jobOrders": [{"id": "4b2d6f80-1a3c-4e57-9b0d-7c5e3a1f2d42", "number": 412, "quantity": {"amount": 12, "unit": "SCU"}, "delivered": false}],
    "missions": [],
    "free": {"jobOrders": {"amount": 18, "unit": "SCU"}, "missions": {"amount": 30, "unit": "SCU"}}
  },
  {
    "id": "1f3e5a7c-9b2d-4c64-8e0a-3d5f7b9c1e75",
    "version": 0,
    "holder": {"withheld": true},
    "quantity": {"amount": 18.25, "unit": "SCU"},
    "jobOrders": [],
    "missions": [],
    "free": {"jobOrders": {"amount": 18.25, "unit": "SCU"}, "missions": {"amount": 18.25, "unit": "SCU"}}
  }
]
```

## The answer

A [`page`](../schemas/) of org-stock lots with one more member, `units`: the
[`org-unit`](../schemas/) entries of every pool the member reads now. Every page lists them. A unit
that is no longer listed — the member left it — is gone for your client together with all its lots;
a departure takes effect on the next request.

```json
{
  "items": [ ],
  "removed": [],
  "units": [
    {"id": "7d1c2f4e-3b8a-4c55-9e21-6a0f3b2d9c10", "name": "Iridium", "shorthand": "IRI", "kind": "SQUADRON"}
  ],
  "nextCursor": "f1.4711.0",
  "hasMore": false
}
```

Page the snapshot with `limit` (1 to 1000, default 500) and the `nextCursor` of the previous page
while `hasMore` is `true`. A lot whose stock changes while you page may move to a later page.

## Following changes

As for every resource, the last snapshot page carries a feed cursor: store it, and read with it
later to get only what changed since ([sync guide](../sync-guide.md)). A feed page holds each
changed lot once, in `items` with its current state or in `removed` as a tombstone keyed like the
lot when the pool no longer holds any of it. Store the new `nextCursor` after every page.

A lot changes when someone books stock into or out of the pool, moves it to another place, holder or
unit, changes its quality, its stolen marker or its note, rebooks it between personal and shared,
or earmarks it for a job order or a mission. For the entries it also changes when a holder's name
changes or they start or stop withholding it, and when an earmarked mission is renamed. A
tombstone names the channel that removed the lot and, for a client, its `clientId`;
`installationId` is set only when one of the member's own installations removed it.

Every feed page lists `units` too. Drop the lots of a unit that is no longer listed. When the
member joins a unit, the feed delivers every lot of its pool, so you never need a new snapshot for
it. A cursor older than the 90-day tombstones is answered `410 CURSOR_EXPIRED`: read a new snapshot
without `cursor`.

```json
{
  "items": [ ],
  "removed": [
    {
      "key": "u:7d1c2f4e-3b8a-4c55-9e21-6a0f3b2d9c10|m:c3b1e0c2-8a4e-4f64-9a51-2b9f5c7e0a11|l:5e2a9d71-0c4b-4f8e-b3a6-1d7c8e9f0a22|q:500|s:0",
      "removedAt": "2026-10-16T09:12:44Z",
      "removedBy": {"channel": "web"}
    }
  ],
  "units": [ ],
  "nextCursor": "f1.4802.0",
  "hasMore": false
}
```

You may keep org stock for up to 7 days. It is other members' stock, and the entries name them:
show it to the member who connected your client, and do not pass it on.

## Writing

With `exchange.orgstock.write`, a member who is a **Logistician of a unit** (see [Entries](#entries))
books stock into that unit's pool for any member of the unit, and books out, sells, moves and edits
any entry of the pool, as the Basetool's warehouse lets them. Send a change set to
`POST /exchange/v1/org-stock/changes`
with an `Idempotency-Key`, as for every write ([sync guide](../sync-guide.md)). The ops run one after
the other in one request; **each op counts against the daily write quota** (a `dryRun` counts
once), and a change set that would exceed it is refused `429 QUOTA_EXCEEDED` as a whole. Every
write is checked and booked by the Basetool's warehouse with its own rules: reservations stay
protected, linked offers of the Basetool's material exchange are lowered, the holder is notified
of a book-out, a sale or a move by someone else, the Basetool's warehouse audit names the member and
your client, and every change is journaled under the member.

Every op names its pool in `unit`. An op on a pool whose unit the member is not a Logistician and
direct member of, on a personal entry, or on an entry of another pool is rejected `NOT_PERMITTED`
and changes nothing. Use the entries' `id` and `version` from the [entries](#entries).

### The roster

`GET /exchange/v1/org-stock/members?unit=<unit id>`, scope `exchange.orgstock.write`: the members a
book-in into that unit's pool may name as holder — the unit's direct members with their `ref` (the
same pseudonym the entries carry) and name, ordered by name. Members who keep their name away from
connected applications are left out and cannot be booked in for; their entries stay writable by id.
A unit the member is not a Logistician of is answered `403 NOT_PERMITTED`.

```json
{
  "unit": {"id": "7d1c2f4e-3b8a-4c55-9e21-6a0f3b2d9c10", "name": "Iridium"},
  "items": [
    {"ref": "q0Fh3kB9xV7m2LwP5nR8tY1cZ4dE6gJ0sU3aH7bK9fM", "name": "Hauler One"}
  ]
}
```

### `book-in`

A new shared entry of the pool, held by the member whose `ref` you name in `holder`, with
`material`, `location`, `quality`, `stolen` and `quantity` as in a [stock write](stock.md), an
optional `note` and optional earmarks: `jobOrders` (`[{jobOrder, quantity}]`, job orders that need
the material or item) and `missions` (`[{mission, quantity}]`, not for an item), each adding up to at
most the amount. Piece-counted stock joins the holder's entry of the same lot in the pool; for an SCU
material set `mergeScu: true` to do the same, as the warehouse's merge option does.

### `book-out`

Takes `quantity` out of the entry `entry` at the `version` you last read. By default only the part of
the entry that is free of earmarks can be booked out; to take earmarked stock, name how much comes out
of each earmark in `fromJobOrders` and `fromMissions` (`[{jobOrder, quantity}]`,
`[{mission, quantity}]`). An entry booked out completely is removed.

### `sell`

A `book-out` that records a sale: `quantity`, `fromJobOrders` and `fromMissions` as for a
`book-out`, plus the `terminal` the stock was sold at and the `proceeds` in aUEC. Each mission in
`fromMissions` receives its share of the proceeds, `proceeds × mission's amount ÷ quantity`, as
mission income — only when the member takes part in that mission, as in the Basetool's warehouse.

### `move-location` and `move-holder`

Moves `quantity` of the entry `entry` to a new entry. `move-location` keeps holder and pool and names
the new `location`. `move-holder` keeps the location and names the new `holder` by its `ref` from
the roster of `toUnit`, the unit whose pool receives the stock: `unit` itself or another unit the
member is a Logistician of. The earmarks named in `fromJobOrders` and `fromMissions` move along;
everything else must come from the free part, as for a `book-out`. Piece-counted stock joins the
receiving holder's entry of the same lot; for an SCU material set `mergeScu: true` to do the same.
The previous and the new holder are notified. A move to the entry's own holder and place is
`unchanged`; a `move-holder` to the entry's own holder into another pool is `NOT_PERMITTED`, use
`move-pool` for that.

### `move-pool`

Moves the entry `entry` into the pool of `toUnit`, another unit the member is a Logistician of and
the entry's holder directly belongs to; holder and location stay. Without `quantity` the whole entry
moves with its earmarks; an entry pledged as a donation cannot move whole. With `quantity` that part
is split off the free stock as a new entry, and the earmarks stay on the rest. `mergeScu` works as
for the other moves. The holder is notified. Naming the entry's own pool is `unchanged`.

### `note` and `stolen`

`note` sets the entry's `note`; without `note`, or with a blank one, it clears it. `stolen` sets
the marker in `stolen` on the whole entry or, with a `quantity`, on that part of it, which becomes
an entry of its own. Either op is `unchanged` when the entry already is as asked.

### A change set

The ops of one change set run in their order, each against the entry as the ops before it left it,
so a later op on the same entry carries the version the earlier one produced:

```json
{
  "ops": [
    {
      "op": "book-in",
      "unit": "7d1c2f4e-3b8a-4c55-9e21-6a0f3b2d9c10",
      "holder": "q0Fh3kB9xV7m2LwP5nR8tY1cZ4dE6gJ0sU3aH7bK9fM",
      "material": {"bt": "c3b1e0c2-8a4e-4f64-9a51-2b9f5c7e0a11", "name": "Laranite"},
      "location": {"name": "Area18", "uex": {"kind": "CITY", "id": 4}},
      "quality": 500,
      "stolen": false,
      "quantity": {"amount": 12.5, "unit": "SCU"},
      "note": "Hangar 3"
    },
    {
      "op": "book-out",
      "unit": "7d1c2f4e-3b8a-4c55-9e21-6a0f3b2d9c10",
      "entry": "9a7c2e10-4f3b-4d21-8c6e-2b1a0f9d8e31",
      "version": 3,
      "quantity": {"amount": 14, "unit": "SCU"},
      "fromJobOrders": [{"jobOrder": "4b2d6f80-1a3c-4e57-9b0d-7c5e3a1f2d42", "quantity": {"amount": 2, "unit": "SCU"}}]
    },
    {
      "op": "sell",
      "unit": "7d1c2f4e-3b8a-4c55-9e21-6a0f3b2d9c10",
      "entry": "1f3e5a7c-9b2d-4c64-8e0a-3d5f7b9c1e75",
      "version": 0,
      "quantity": {"amount": 6, "unit": "SCU"},
      "terminal": "TDD Area18",
      "proceeds": 48250
    },
    {
      "op": "move-holder",
      "unit": "7d1c2f4e-3b8a-4c55-9e21-6a0f3b2d9c10",
      "entry": "1f3e5a7c-9b2d-4c64-8e0a-3d5f7b9c1e75",
      "version": 1,
      "quantity": {"amount": 4, "unit": "SCU"},
      "toUnit": "7d1c2f4e-3b8a-4c55-9e21-6a0f3b2d9c10",
      "holder": "q0Fh3kB9xV7m2LwP5nR8tY1cZ4dE6gJ0sU3aH7bK9fM"
    },
    {
      "op": "stolen",
      "unit": "7d1c2f4e-3b8a-4c55-9e21-6a0f3b2d9c10",
      "entry": "9a7c2e10-4f3b-4d21-8c6e-2b1a0f9d8e31",
      "version": 4,
      "stolen": true,
      "quantity": {"amount": 2, "unit": "SCU"}
    }
  ]
}
```

### The result

A [change result](../schemas/) as for every write: an applied op is counted, an op that was not is
listed with its reason. The entries an op created or changed reach you through the feed.
`dryRun: true` checks every op without writing.

| Reason | Op | Meaning |
| --- | --- | --- |
| `NOT_PERMITTED` | all | The pool, or the `toUnit` of a `move-holder` or `move-pool`, is not one of the member's Logistician units; the entry is unknown, personal or in another pool; or a `move-holder` names the entry's own holder for another pool. |
| `HOLDER_UNKNOWN` | `book-in`, `move-holder`, `move-pool` | `holder` is not on the roster of the receiving unit: not a direct member, withholding their name, or not a ref of your client; for `move-pool`, the entry's holder is not a direct member of `toUnit`. Read the roster again. |
| `VERSION_CONFLICT` | all but `book-in` | The entry changed since you read it; an earlier op of the same change set counts. Pull the feed and retry. |
| `INSUFFICIENT_STOCK` | all but `book-in`, `note` | The entry holds less than `quantity`. |
| `STOCK_EARMARKED` | `book-out`, `sell`, moves, `stolen` | The part of `quantity` that `fromJobOrders` and `fromMissions` do not cover is earmarked, or pledged as a donation; for a partial `stolen` or `move-pool`, the rest of the entry would hold less than its earmarks; a whole `move-pool` of an entry pledged as a donation. |
| `STOCK_OFFERED` | `stolen`, `move-pool` | The rest of the entry after a partial `stolen` or `move-pool` would hold less than it offers on the Basetool's material exchange. |
| `OVER_ALLOCATION` | `book-in` | The earmarks of one kind add up to more than `quantity`. |
| `EARMARK_TARGET_INVALID` | `book-in`, `book-out`, `sell`, moves | An earmark names a job order that does not need the material or item, an unknown mission, a mission for an item, a target twice, or — when taking from an entry — a target the entry is not earmarked to or more than it holds there. |
| `UNIT_MISMATCH`, `LOCATION_UNKNOWN`, `STOLEN_MARKING_DISABLED`, `UNMATCHED`, `AMBIGUOUS` | as for [stock](stock.md) | |

An undo of org-stock writes under *Connected applications* is not offered yet; the journal keeps
every change for it.
