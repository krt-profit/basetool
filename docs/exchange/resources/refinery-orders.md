# Refinery orders

The member's own refinery work orders: where an order runs, what it refines, when it ends and what
it cost. Reading needs `exchange.refinery.read`, writing `exchange.refinery.write`. Only orders the
member owns are served and written, whatever created them: the web, a reviewed [draft](drafts.md)
or any client.

## An order

| Field | Meaning |
| --- | --- |
| `id` | Opaque identifier of the order, the feed key. |
| `clientKey` | Your key for the order, only when **your client** created it; other clients never see it. |
| `createdBy` | `{clientId, name}` of the client that created the order; absent for an order created in the web. |
| `versions` | `{gameData, finances}`, the counters of the order's two sections (see below). |
| `status` | `OPEN`, `IN_PROGRESS`, `COMPLETED` or `CANCELED`. |
| `location` | `{name, uex?, guid?}` of the refinery the order runs at. |
| `method` | `{code?, name}` of the refining method; absent when none is set. |
| `startedAt` | When the run started; absent when not set. |
| `durationMinutes` | The run's length in minutes; absent when not set. |
| `endsAt` | `startedAt` plus `durationMinutes`; absent while either is unset. |
| `pickedUpAt` | When a client reported the yield collected in the game; absent otherwise. |
| `storedAt` | When the yield was booked into stock; absent while it has not been. |
| `expenses`, `otherExpenses`, `oreSales` | Money in aUEC, each absent when not set. |
| `missionId` | The linked mission, by id only; absent when there is none. |
| `orgUnitId` | The owning [org unit](org-units.md), by id; absent for a personal order without one. |
| `goods` | The goods, each `{id, input, inputQuantity, output, outputQuantity, quality}`. |

The owner is never carried: it is always the member.

A good refines a raw `input` material into a refined `output` material, both `{bt, name}` as in the
[catalogue](catalog.md). `inputQuantity` and `outputQuantity` are whole units, 100 to the SCU, as
the refinery terminal shows them; `quality` runs from 0 to 1000. A good's `id` stays the same while
the good exists.

### Section counters

An order is locked in two independent sections. `gameData` covers the location, the method, the
timer, the status, the goods, the pickup and the store; `finances` covers `expenses`,
`otherExpenses` and `oreSales`. A change in one section moves only that section's counter, so a
timer update from the game never collides with a finance edit in the web. The mission link and the
owning unit belong to neither section.

## Reading the orders — `GET /exchange/v1/me/refinery-orders`

A snapshot without `cursor`, the changes since it with one: the same `cursor`, `limit`, paging and
tombstones as [blueprints](blueprints.md#reading-the-set--get-exchangev1meblueprints).

- The **snapshot** holds only the orders in `OPEN` or `IN_PROGRESS`, the ones the game still shows
  as running or ready.
- The **feed** carries every change of every own order, whatever its status. It can therefore bring
  a `COMPLETED` or `CANCELED` order your client never saw; adopt it or ignore it. Completing,
  cancelling and reopening an order in the web arrive as ordinary changes.
- A **tombstone** means the order is gone for the member: deleted, or moved to another member. A
  cancelled order is not removed; it arrives with the status `CANCELED`. The tombstone of an order
  your client created carries its `clientKey` too, so you can drop it by your own key.

An order is in the feed again when one of its goods changes, once per page however many goods
changed. It is also in the feed again when the `name`, `uex` or `guid` of its `location` changes,
so the `location` you hold is never stale.

## Changing orders — `POST /exchange/v1/me/refinery-orders/changes`

Send an `Idempotency-Key` and a change set of 1 to 500 ops, `dryRun` optional. The server decides
the ops in order, as if the earlier ones had already run, with one result per op and no shared
rollback: a refused op leaves the others as they are. Every write goes through the Basetool's own
refinery rules, so the web shows your client as the order's creator, the member's open refinery
pages update at once, and a changed timer re-arms the ready notice as a web edit does.

```json
{
  "ops": [
    {
      "opId": "c1",
      "op": "create",
      "clientKey": "wo-ARC-L1-0043",
      "orgUnit": {"id": "0d6f3c1a-5b2e-4f7a-9c8d-1e2f3a4b5c6d"},
      "location": {"name": "ARC-L1 Wide Forest Station"},
      "method": {"code": "DINYX"},
      "status": "IN_PROGRESS",
      "endsAt": "2026-11-08T14:30:00Z",
      "durationMinutes": 90,
      "goods": [
        {
          "input": {"name": "Quantainium (Raw)"},
          "inputQuantity": 1200,
          "outputQuantity": 900,
          "quality": 650
        }
      ]
    },
    {
      "opId": "u1",
      "op": "update",
      "clientKey": "wo-ARC-L1-0043",
      "versions": {"gameData": 0},
      "endsAt": "2026-11-08T15:00:00Z"
    }
  ]
}
```

| Op | Fields | Does |
| --- | --- | --- |
| `create` | `clientKey`, `orgUnit`, `location`, optional `method`, `status`, `endsAt`, `durationMinutes`, `expenses`, `otherExpenses`, `oreSales`, `goods` | Creates an order owned by the member, with your client as its creator. |
| `update` | `id` or `clientKey`, `versions`, and any of `location`, `method`, `status`, `endsAt`, `durationMinutes`, `expenses`, `otherExpenses`, `oreSales` | Changes the fields it carries and leaves every absent field as it is. |
| `good-add` | `id` or `clientKey`, `versions`, `good` | Adds one good; the result names the new row's id. |
| `good-update` | `id` or `clientKey`, `versions`, `goodId`, and any of `input`, `inputQuantity`, `outputQuantity`, `quality` | Changes the fields of one good it carries. |
| `good-remove` | `id` or `clientKey`, `versions`, `goodId` | Removes one good. |
| `cancel` | `id` or `clientKey`, `versions` with `gameData` | Cancels the order as the web does, stored or not; a cancelled order is `unchanged`. |
| `delete` | `id` or `clientKey`, `versions` with `gameData` and `finances` | Removes an order that is not stored, with its goods; a stored one is `ORDER_STORED`. |
| `pickup` | `id` or `clientKey`, `versions` | Reports the yield collected in the game: the order becomes `COMPLETED` with `pickedUpAt`. Nothing is booked into stock. |

Every op may carry an `opId` of your own, echoed in its result.

### Fields

- **Addressing.** Every op but `create` names its order by its `id` from the feed or a result, or
  by your own `clientKey`, so an order a `create` made earlier in the same set can be changed at
  once. An order of another owner, and a key another client set, are `ORDER_UNKNOWN`.
- **Client key.** A `create` carries a `clientKey` of 1 to 128 characters, unique per member and
  client. A `create` whose key already names an order of the member is `rejected` as
  `ALREADY_EXISTS`, and its result carries that order's `id`: adopt it, nothing is overwritten.
- **Org unit.** `orgUnit` is `{id}` of one of the units
  [`GET /exchange/v1/me/org-units`](org-units.md) returns; another unit is `NOT_A_MEMBER`. The
  server never picks one. After the create, the owning unit and the mission link change only in
  the web.
- **Location.** A [place](../formats.md#place--location-ref), resolved exactly. It must be a
  refinery the web offers for refinery orders, else `NOT_A_REFINERY`; a place without a location
  is `LOCATION_UNKNOWN`.
- **Method.** `{code}` or `{name}`, resolved by its code first, then by its exact name.
- **Status.** `OPEN`, the default of a create, or `IN_PROGRESS`. `COMPLETED` comes only from a
  `pickup`, and `CANCELED` is not written here. An order whose yield was booked into stock keeps
  its status: changing it is `ORDER_STORED`.
- **Pickup.** A `pickup` closes an `OPEN` or `IN_PROGRESS` order, also before its `endsAt`,
  because the game is the truth. The order becomes `COMPLETED`, the feed shows `pickedUpAt`, and
  the web shows it as picked up with your client's name. Nothing is booked into stock: send it
  when your own stock sync books the goods. An order already `COMPLETED` is `unchanged`, so a
  repeated pickup is harmless; a cancelled one is `ORDER_CANCELED`. The member may still change
  the status in the web afterwards. A pickup carries no further field; a pickup that books the
  yield into stock is not available yet.
- **Timer.** `endsAt` is RFC 3339 in UTC; `durationMinutes` needs `endsAt`. With both, the server
  stores `startedAt = endsAt - durationMinutes`. Without a duration the run starts at the time of
  the write, and `durationMinutes` is the rest until `endsAt`, rounded up to whole minutes, so
  `endsAt` stays as sent.
- **Money.** `expenses`, `otherExpenses` and `oreSales` in aUEC, from 0 to 1000000000; 0 clears
  the value.
- **Goods.** A good is `{input, inputQuantity, outputQuantity, quality}`. `input` is an
  [item reference](../formats.md#item-reference--item-ref) to a raw material, resolved exactly:
  one that is not raw is `MATERIAL_NOT_RAW`. The output material follows from the input as in the
  web. Quantities are whole units, 100 to the SCU. `good-update` and `good-remove` name a row by
  its `goodId` from the feed or a result; a row of another order is `GOOD_UNKNOWN`.

Nothing is matched fuzzily, because no member reviews a direct write: a reference that matches
nothing is `unmatched`, one that matches several `ambiguous`. Resolve doubtful names with
[`catalog/resolve`](catalog.md) first.

### Counters in a change set

Every op but `create` sends `versions`, the counter of **each section it touches** as you last saw
it: `gameData` for the location, the method, the timer, the status, the goods ops and `pickup`,
`finances`
for `expenses`, `otherExpenses` and `oreSales`. A stale counter, or a missing one for a section the
op touches, is `VERSION_CONFLICT` for that op only. Within one set the expected counter is what the
earlier ops left: every applied op raises the counter of each section it touches by one. A finance
update from your client therefore never collides with a timer edit in the web, and the reverse.

A `cancel` sends `gameData`, a `delete` both counters.

Once an op on an order is refused, every later op on the same order in the set is `rejected` as
`PRECEDING_OP_REJECTED`, so a set never applies the second half of a change whose first half
failed.

### The result

The change result lists every op that was not applied in `results[]`, with its
[reason](../errors.md#per-op-reasons-in-a-change-result). For an applied op, outside a dry run,
`orders[]` carries one entry: its `index` and `opId`, the order's `id`, the ids of the goods rows
it created in `goods`, absent when it created none, and the order's `versions` after the op. Keep
them, and write on without reading the feed first.

```json
{
  "dryRun": false,
  "applied": 1,
  "unchanged": 0,
  "notApplied": 1,
  "results": [
    {"index": 0, "opId": "c1", "result": "rejected", "reason": "ALREADY_EXISTS",
     "id": "3f1c2b4a-6d5e-4a7b-8c9d-0e1f2a3b4c5d"}
  ],
  "orders": [
    {"index": 1, "opId": "g1", "id": "5b3e4d6c-8f7a-4c9d-8e1f-2a3b4c5d6e7f",
     "goods": ["7d8e9f0a-1b2c-4d3e-8f4a-5b6c7d8e9f0a"],
     "versions": {"gameData": 10, "finances": 4}}
  ]
}
```

| Case | `result` | `reason` |
| --- | --- | --- |
| `update` that changes nothing, `cancel` of a cancelled order | `unchanged` | — |
| `pickup` of an order already `COMPLETED` | `unchanged` | — |
| `pickup` of a cancelled order | `rejected` | `ORDER_CANCELED` |
| A location, method or material that matches nothing | `unmatched` | `UNMATCHED` |
| A location, method or material that matches several | `ambiguous` | `AMBIGUOUS` |
| A place without a location | `rejected` | `LOCATION_UNKNOWN` |
| A location that is not a refinery the web offers | `rejected` | `NOT_A_REFINERY` |
| A good's input that is not a raw material | `rejected` | `MATERIAL_NOT_RAW` |
| `create` naming a unit the member is not a direct member of | `rejected` | `NOT_A_MEMBER` |
| An `id` that names no order of the member, or a `clientKey` your client never set | `rejected` | `ORDER_UNKNOWN` |
| A `goodId` that names no good of the order | `rejected` | `GOOD_UNKNOWN` |
| `create` with a `clientKey` the member's orders already carry; `id` names the order | `rejected` | `ALREADY_EXISTS` |
| A stale or missing counter of a section the op touches | `rejected` | `VERSION_CONFLICT` |
| An op on an order an earlier op of the set was refused for | `rejected` | `PRECEDING_OP_REJECTED` |
| A status change or a `delete` of an order whose yield was booked into stock | `rejected` | `ORDER_STORED` |
| Any op after a `delete` of the same order in the set | `rejected` | `ORDER_UNKNOWN` |

On `VERSION_CONFLICT`, pull the feed, merge, and send the op again under a new `Idempotency-Key`.

**Dry run.** `dryRun: true` decides every op and writes nothing; its result has no `orders`.

**Quota.** A change set counts **once** against the daily write quota, whatever its op count
([limits](../sync-guide.md#rate-limits-quota-and-back-off)). Only a `delete` counts as a removal
for the [mass-change guard](../sync-guide.md#the-mass-change-guard); a set that deletes too many
orders is held for the member's confirmation. A `cancel`, a `good-remove` and a `pickup` never count.

**Delete.** A `delete` removes the order and its goods; stock already booked from it stays. Your
feed carries its tombstone, with the `clientKey` when your client created the order.

**Undo.** The member can undo your client's writes on *Connected applications*. Undoing a `create`
removes the order while neither of its counters moved since; undoing an `update`, a goods op or a
`cancel` restores the sections it touched while their counters are unchanged; undoing a `pickup`
sets the status back and clears `pickedUpAt` under the same check. Undoing a `delete` brings the
order back under its `id`, its goods under their ids, with its `clientKey`; the feed shows it again
under the same key. A write changed afterwards is skipped as *changed afterwards*. An undo reaches
your feed like a web edit ([sync guide](../sync-guide.md#undo)).

## Errors

| Code | When |
| --- | --- |
| `400 SCHEMA_INVALID` | The change set, `limit` or a parameter breaks the contract, for example a `create` without `clientKey`, an op without `id` and `clientKey`, or `durationMinutes` without `endsAt`. |
| `400 IDEMPOTENCY_KEY_MISSING` | A change set without a well-formed `Idempotency-Key`. |
| `403 SCOPE_MISSING` | Reading without `exchange.refinery.read`, or writing without `exchange.refinery.write`. |
| `410 CURSOR_EXPIRED` | The cursor is older than the tombstones, or not one the server issued. |
| `413 BATCH_TOO_LARGE` | More than 500 ops. |

The full list is the [error registry](../errors.md).
