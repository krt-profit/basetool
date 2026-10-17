# Org demand

`GET /exchange/v1/me/org-demand`, scope `exchange.demand.read`: what the member's units still need,
so a client can show the member where their stock or their crafting helps. It is anonymous and
read-only; there is no paging and no cursor. With the additional scope `exchange.demand.details`,
a Logistician of a unit also gets that unit's orders one by one; see [order details](#order-details).

## Whose demand

The open and in-progress job orders that a unit **the member is a member of** is responsible for.
Units the member only oversees or administers do not count.

Only a member who may see their organisation's job orders in the Basetool gets the demand: a
member of at least one unit that takes part in the profit sharing, or of a leadership seat above
one. Everyone else, a member of no unit included, gets `200` with two empty lists and `"reason": "NOT_PERMITTED"`:

```json
{
  "updatedAt": "2026-09-28T12:00:00Z",
  "materials": [],
  "items": [],
  "reason": "NOT_PERMITTED"
}
```

The check runs on every request, so a member whose unit leaves the profit sharing stops seeing its
demand at once, even while its orders stay open. An administrator's role does not open it: the
exchange acts with the member's own reach only. A permitted member whose units have nothing open
gets two empty lists **without** a `reason`. Show the member why the view is empty when `reason` is
present, drop any cached demand, and treat a `reason` value you do not know as withheld.

## The answer

An [`org-demand`](../schemas/) document:

```json
{
  "updatedAt": "2026-09-27T12:00:00Z",
  "materials": [
    {
      "material": {"bt": "m-agr", "name": "Agricium"},
      "rawRefs": [{"bt": "m-agr-ore", "name": "Agricium (Ore)"}],
      "minQuality": 650,
      "openQuantity": {"amount": 40, "unit": "SCU"},
      "source": "material-order"
    }
  ],
  "items": [
    {
      "item": {"bt": "i-panther", "name": "CF-337 Panther Repeater"},
      "openQuantity": {"amount": 2, "unit": "PIECE"},
      "craftableByMe": true
    }
  ]
}
```

**`materials[]`** — one line per material, quality floor and source:

| Field | Meaning |
| --- | --- |
| `material` | The material; `bt` is its Basetool id, as in a stock lot. |
| `rawRefs` | Raw ores that refine into this material, at most 20, by name; possibly empty. |
| `minQuality` | The quality floor the orders ask for: 650 for a good-quality requirement, otherwise 0. |
| `openQuantity` | What is still missing: the orders' requirement minus the stock already booked to them at or above that floor, summed over the orders. |
| `source` | `material-order` for a material order, `item-order` for the materials an item order resolves to. |

**`items[]`** — one line per game item that item orders still need:

| Field | Meaning |
| --- | --- |
| `item` | The game item; `bt` is its Basetool id. |
| `openQuantity` | Pieces ordered, minus delivered, minus already reserved for the order. |
| `craftableByMe` | The member holds a blueprint that produces it; variants count when the order counts them. |

`reason` is absent whenever the demand is shown; see [whose demand](#whose-demand).

An item order appears twice: as its item line and as the `item-order` material lines of what it is
made of. Count one or the other, never both. Lines with nothing open are left out. `updatedAt` is
when the server computed the answer.

## Order details

With `exchange.demand.details` in addition to `exchange.demand.read`, the answer carries
`orders[]`: the open and in-progress orders whose responsible unit is one the member is a
**Logistician of** — the member's own Logistician flag or a leadership seat in exactly that unit; a
seat above it does not count. `orders` is present only when the member is a Logistician of at least
one unit they belong to, and then lists that unit's orders by number, possibly none. It is absent
without the scope, for every other member and when the demand is withheld. `materials` and `items`
stay exactly as above, and the orders of the member's other units stay summed there only.

```json
"orders": [
  {
    "id": "0b9a6f0e-4c1d-4a8e-9a3f-5c2d7e8f9a10",
    "number": 412,
    "type": "MATERIAL",
    "status": "OPEN",
    "priority": 3,
    "unit": {"id": "7d1c2b3a-0000-4000-8000-000000000001", "name": "Alpha Squadron"},
    "requestingUnit": {"id": "7d1c2b3a-0000-4000-8000-000000000002", "name": "Bravo Squadron"},
    "handle": "Quartermaster",
    "comment": "Deliver to the hangar at Lorville.",
    "materials": [
      {
        "material": {"bt": "m-agr", "name": "Agricium"},
        "minQuality": 650,
        "openQuantity": {"amount": 40, "unit": "SCU"}
      }
    ],
    "items": []
  }
]
```

| Field | Meaning |
| --- | --- |
| `id` | The order's id, the same an org-stock earmark names. Opaque. |
| `number` | The running number the Basetool shows as `#412`. |
| `type` | `MATERIAL` or `ITEM`. |
| `status` | `OPEN` or `IN_PROGRESS`. |
| `priority` | The order's place in the queue, 1 first; absent when it has none. |
| `unit` | The unit responsible for the order, one the member is a Logistician of. |
| `requestingUnit` | The unit that placed the order and receives the goods. |
| `handle` | The requester as the order's author typed it — free text, not a member account. Absent when empty. |
| `comment` | The order's comment, free text. Absent when empty. |
| `materials` | Per material and quality floor, what this order still needs: its requirement minus the stock booked to it, never below zero. For an item order, what its items are made of. |
| `items` | For an item order, per game item: ordered minus delivered minus reserved. Empty for a material order. |

Lines with nothing open are left out. An order never names who works on it.

## What it does not reveal

Without the order details: no requester, assignee, order number or title, unit name, free text or
per-order figure — only catalogue names and summed amounts. With them, the orders of the member's
Logistician units carry their number, units, requester and comment, but never an assignee. Sums are
not suppressed for small counts, so a line may stand for a single order. Treat the document as the
member's organisation's internal data.

## Caching

The server computes the demand for each request. A client may keep a copy for **at most 7 days**,
outside cloud-synced folders, never in a backup or a diagnostics bundle, and never forwards it
([client security requirements](../client-security.md)). The demand changes slowly: refresh it
once when the member opens the view, and after that at most every 5 minutes, like any timed sync
([sync cadence](../sync-guide.md#back-off-and-sync-cadence)).
