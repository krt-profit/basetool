# Org stock

`GET /exchange/v1/org-stock`, scope `exchange.orgstock.read`: the shared warehouse stock of the org
units the member directly belongs to, one pool per unit. It is read-only.

## Which pools

Each unit the member is a **direct member** of is a **pool**: the stock its members booked into the
unit's shared warehouse. All four kinds of unit count, as for [org units](org-units.md). A unit the
member only oversees — the squadrons below an area they lead — is not served, and an administrator's
role adds nothing: the exchange acts with the member's own memberships only. Personal stock of any
member and shared stock booked to no unit never appear.

## A lot

An [`org-stock-lot`](../schemas/) is one pool's stock of one material or item at one warehouse
location, quality and stolen state, **summed over every member** who holds stock there. The same
material at the same place in two of the member's units is two lots. A lot names no holder.

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

As for every resource, the last page carries a feed cursor: store it. **There is no org-stock feed
yet**, so reading with that cursor is answered `410 CURSOR_EXPIRED`; read a new snapshot without
`cursor`, exactly as after any expired cursor. The feed will be announced in the
[changelog](../changelog.md); from then on the same cursor resumes it.

You may keep org stock for up to 7 days. It is other members' stock: show it to the member who
connected your client, and do not pass it on.
