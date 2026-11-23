# Job orders

The job orders of all three kinds that the member relates to, under scope
`exchange.joborders.read`: material orders, item orders and aUEC orders, which ask for a provision
of credits. Every route lies below `/exchange/v1/job-orders`.

**The job-order family is not switched on yet.** Until an administrator turns it on, every path at
or below `/exchange/v1/job-orders` answers `503 JOB_ORDER_EXCHANGE_DISABLED` with
`Retry-After: 300`. Pause the job-order area only and keep syncing every other area
([errors](../errors.md)). The writes have a switch of their own, `503 JOB_ORDER_WRITES_DISABLED`;
the reads go on while only the writes are off.

Processing, every money operation and every Bank decision stay in the Basetool. A client never
accepts, processes, hands over, provides, settles or deletes an order and never changes its
assignees or support; each order carries a [`link`](#the-link) to its page for all of that.

## Retention

| Route | Retention class | What a client may do with it |
| --- | --- | --- |
| `GET /exchange/v1/job-orders` | job-order data | keep it in memory only, for at most 7 days |

Job-order data of every kind, the member's own orders included, is kept for at most 7 days,
outside cloud-synced folders, never in a backup or diagnostics, and never forwarded. Keep it out of
any state file that goes into a backup. The retention class is a condition of the approval
([becoming a client](../onboarding.md)).

## Which orders

The read covers the orders of all three kinds (`MATERIAL`, `ITEM`, `AUEC`) to which the member has
a current own relation:

- a unit the member **directly** belongs to requests the order, is responsible for it, or actively
  supports it;
- or the member works on it as an assignee.

Leadership reach over other units, the Kartell-wide read of the Basetool, aUEC holders and
recipients, former units and ended support do not count. An administrator reads like every other
member. There is no filter parameter: each order carries the member's [`relations`](#every-order)
to it, so build "mine", "I work on" and "to process" from them.

Of these orders the read holds:

- the active ones (`OPEN`, `IN_PROGRESS`);
- closed ones (`COMPLETED`, `CANCELLED`, `REJECTED`) for up to 7 days after closing;
- aUEC orders for as long as `settlementOpen` is `true`.

## The snapshot

`GET /exchange/v1/job-orders` — a snapshot of the member's job-order set, ascending by `id`. Page
with `cursor` and `limit` (1 to 1000, default 500) as in the [sync guide](../sync-guide.md). There
is one cursor for all three kinds, and the snapshot's last page hands over the cursor of the
[feed](#the-feed).

A new order joins the snapshot a few seconds after it was created.

The page is a [`page`](../schemas/) with `#/$defs/jobOrderPage`: `items`, `removed` (always empty
on a snapshot page), `nextCursor` and `hasMore`. Each item is a
[`job-order-material`](../schemas/), [`job-order-item`](../schemas/) or
[`job-order-auec`](../schemas/), told apart by `kind`.

### Every order

| Field | Meaning |
| --- | --- |
| `id` | the order's UUID and the feed key; store and echo it, never parse it |
| `number` | the running number, shown as `#number`; a display field only, no route accepts it |
| `kind`, `status` | the kind and the lifecycle status |
| `priority` | the place in the queue; `null` once closed |
| `requestingUnit`, `responsibleUnit`, `supportingUnits[]` | [`org-unit`](../schemas/)s with name, shorthand and kind; ended support is not listed |
| `createdAt`, `updatedAt` | creation, and the last change of a field this read carries |
| `relations` | at least one of `requesting`, `responsible`, `supporting`, `assignee`, in that order |
| `comment` | the author's plain text, or `null` |
| `responsibilityInvalid` | the responsible unit lost its valid Profit responsibility; nothing can be written until it is fixed in the Basetool |
| `link` | the order's page in the Basetool (see [the link](#the-link)) |
| `assignees[]` | `name`, a per-client `ref`, `own` and `note`; see below |
| `versions` | `{ lifecycle, target, comment }`, which the writes echo; `target` is `null` on material and item orders |

`assignees[]` lists the members working on the order by the name the Basetool shows and a `ref`
that is the same in every answer to your client and different for every other client. A member who
withholds their name from connected applications is left out, in their own client too. `own` marks
the acting member's own entry, and only that entry carries `note`, their own note (or `null`).
Another member's note is never carried.

### Per kind

| Kind | Fields |
| --- | --- |
| `MATERIAL` | `contact`, the author's handle as plain text (left out when the order names none); `materials[]` per material and quality floor: `material` (`bt` and `name`), `minQuality`, `unit` (`SCU` or `PIECE`), `requested`, `handedOver`, `remaining`, `open` (the remaining amount less linked stock and claims, never below 0) |
| `ITEM` | `contact` as for material orders; `items[]` per item line: `id`, `item` (`bt` and `name`), `ordered`, `manufactured`, `delivered`, `open` (`max(0, ordered − delivered)`) |
| `AUEC` | in exact whole aUEC: `target`, `netHandedOver`, `remaining`, `overpaid`; and `settlementOpen`, `true` while a closed order still holds purpose-bound funds or an overpayment, with no amount: "settle in the Basetool". An aUEC order carries **no contact**. |

Never carried: holders and amounts per person (not even a sum), provision, fees, movements,
recipients, Bank state, unit commitments, claims, the money and target-change history and other
members' notes.

```json
{
  "id": "3d4e5f60-7182-4c93-8db4-c5d6e7f8091a",
  "number": 414,
  "kind": "AUEC",
  "status": "COMPLETED",
  "priority": null,
  "requestingUnit": { "id": "4c5d6e7f-8091-4a2b-8c3d-4e5f60718293", "name": "Iridium",
                      "shorthand": "IRI", "kind": "SQUADRON" },
  "responsibleUnit": { "id": "5d6e7f80-91a2-4b3c-9d4e-5f6071829304", "name": "Profit",
                       "shorthand": "PRO", "kind": "BEREICH" },
  "supportingUnits": [],
  "createdAt": "2026-11-10T12:00:00Z",
  "updatedAt": "2026-11-21T17:45:00Z",
  "relations": ["requesting"],
  "comment": "Fuel for the next op",
  "responsibilityInvalid": false,
  "link": "https://profit-base.online/orders/3d4e5f60-7182-4c93-8db4-c5d6e7f8091a",
  "assignees": [],
  "versions": { "lifecycle": 3, "target": 1, "comment": 0 },
  "target": 250000,
  "netHandedOver": 260000,
  "remaining": 0,
  "overpaid": 10000,
  "settlementOpen": true
}
```

## The feed

`GET /exchange/v1/job-orders?cursor=<cursor>` with the cursor of the snapshot's last page, or of
the last feed page, answers the changes of the member's job-order set since then. Read it to its
end, until `hasMore` is `false`, on every pass ([sync guide](../sync-guide.md)). The page is the
same `jobOrderPage`, and each order appears at most once per page.

- An order that is in the member's set arrives in `items` with its whole current state, as in the
  snapshot. Your own writes, a reopened order and an order the member starts to relate to arrive
  this way.
- Any other order arrives in `removed` as `{ key, removedAt }`: drop it. The entry names no writer,
  and a lost relation (a unit stopped relating, the member left the unit, the assignee entry ended),
  the end of the order's 7 days and a deletion look alike. Ignore a key you never held.
- A cancelled order stays an item with its new `status` until its 7 days end. A closed order
  leaves the set at the latest one day after its 7 days.
- An order changes in the feed only when a field the read carries changes, never on a provision, a
  holder movement, a fee, a commitment or a contact change of an aUEC order in the Basetool. A
  change arrives a few seconds after it was made.
- `removedAt` is when the order's latest change was recorded; `updatedAt` of an item is the last
  change of a field the read carries.

```json
{
  "items": [],
  "removed": [
    { "key": "2c3d4e5f-6071-4b82-9ca3-b4c5d6e7f809", "removedAt": "2026-11-22T19:41:03Z" }
  ],
  "nextCursor": "f1.1031777.0",
  "hasMore": false
}
```

The feed keeps 90 days of changes. An older cursor answers `410 CURSOR_EXPIRED`: take a new
snapshot and reconcile it against your last baseline
([full resync](../sync-guide.md#full-resync-after-a-cursor-expired)).

## The link

`link` is an absolute `https://profit-base.online/…` URL of the order's page, built by the server.
Every action that stays in the Basetool goes through it. Open it only after checking that it is
`https` on the Basetool's host, as for the login page; never open a URL taken from `comment` or
`contact`, which are plain text and render as text.

## Client guidance

- **An isolated area.** Make job orders their own area, so a job-order refusal — the switches
  included — never stops the other areas of a sync pass.
- **Memory only.** Keep every job-order item in memory, own orders included, with a snapshot at
  each start and the feed on every pass after it.
- **The create**, once published, follows the bank raise: a fresh `Idempotency-Key` per create, held
  in memory with its body until a clear answer; an unclear outcome (no answer, `5xx`,
  `IDEMPOTENCY_IN_PROGRESS`, `RATE_LIMITED`) goes again under the same key and body. After a restart
  nothing is resent; the snapshot shows whether the order exists.
- **`create-options`**, once published, is read when the create form opens, not on every pass.
- **The switch codes.** Treat `JOB_ORDER_EXCHANGE_DISABLED` as "pause the job-order area" and
  `JOB_ORDER_WRITES_DISABLED` as "pause the job-order writes", each after `Retry-After`.

## Refusals

| Status | Code | When | Client action |
| --- | --- | --- | --- |
| 400 | `SCHEMA_INVALID` | `limit` outside 1 to 1000 | fix the request |
| 403 | `SCOPE_MISSING` | the token or the registry lacks `exchange.joborders.read` | ask the member to grant it |
| 410 | `CURSOR_EXPIRED` | the cursor is older than the retained changes or not one the server issued | take a new snapshot and reconcile it against the last baseline |
| 503 | `JOB_ORDER_EXCHANGE_DISABLED` | the job-order family is switched off; `Retry-After: 300` | pause the job-order area only |
| 503 | `EXCHANGE_DISABLED` | the whole exchange is switched off; `Retry-After: 30` | pause every area |

The gateway's other codes apply as everywhere ([errors](../errors.md)).
