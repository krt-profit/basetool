# Job orders

The job orders of all three kinds that the member relates to, under scope
`exchange.joborders.read`: material orders, item orders and aUEC orders, which ask for a provision
of credits. Under scope `exchange.joborders.write` a Logistician creates aUEC orders on behalf of
their own units ([creating an aUEC order](#creating-an-auec-order)). Every route lies below
`/exchange/v1/job-orders`.

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
| `GET /exchange/v1/job-orders/create-options` | job-order data | read it when the create form opens; keep it no longer than the form |
| `POST /exchange/v1/job-orders` | job-order data | keep the answer in memory only, for at most 7 days |

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
is one cursor for all three kinds, and the snapshot's last page hands over the feed cursor. The
change feed that reads it is not published yet: until it is, a feed cursor answers
`410 CURSOR_EXPIRED`, so take a new snapshot at the start of each pass.

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

## The link

`link` is an absolute `https://profit-base.online/…` URL of the order's page, built by the server.
Every action that stays in the Basetool goes through it. Open it only after checking that it is
`https` on the Basetool's host, as for the login page; never open a URL taken from `comment` or
`contact`, which are plain text and render as text.

## Creating an aUEC order

A member who is a **Logistician** of a unit they **directly** belong to — by the Logistician flag, a
leadership rank, or the reach of a Bereich or Organisationsleitung seat over it — creates aUEC
orders on behalf of that unit, under scope `exchange.joborders.write`. The member's unit is always
the **requesting** unit; the responsible unit is one of the Profit units. Creating an order on behalf
of another unit, as a Logistician of the responsible Profit unit, stays in the Basetool. An
administrator creates like every other member. Money moves only in the Basetool, and nothing is
undone through the exchange: cancel a wrong order instead.

Create only on an explicit action of the member, never automatically or by a rule, and let the
member choose both units from the create options (you may preselect).

### Create options

`GET /exchange/v1/job-orders/create-options` — where the member may create an aUEC order, a
[`job-order-create-options`](../schemas/):

| Field | Meaning |
| --- | --- |
| `requestingUnits[]` | the member's units to create on behalf of, as [`org-unit`](../schemas/)s; empty when the member may create no order |
| `responsibleUnits[]` | the Profit units that process aUEC orders, as `org-unit`s; empty as well when the member may create no order |
| `suggestedResponsible` | the id of the Profit area to preselect, or `null` |
| `writesEnabled` | whether job-order writes are switched on now; while `false` a create answers `503 JOB_ORDER_WRITES_DISABLED` |

Empty lists mean: offer no create. The read names no member and is a read, so it answers while
only the writes are off.

### The create

`POST /exchange/v1/job-orders` with an `Idempotency-Key` — the body is
[`job-order-write`](../schemas/) `#/$defs/create`:

| Field | Meaning |
| --- | --- |
| `requestingUnitId` | one of `requestingUnits` |
| `responsibleUnitId` | one of `responsibleUnits` |
| `contact` | whom to contact about the order, plain text, 1 to 200 characters; required |
| `comment` | optional plain text, at most 1000 characters |
| `target` | the requested amount in whole aUEC, 1 to 1 000 000 000 000 |

```json
{
  "requestingUnitId": "2d8b4f30-6e3c-4a0a-9c73-5b2e7f9c1d32",
  "responsibleUnitId": "5a4d0000-0000-4000-8000-000000000102",
  "contact": "Kestrel",
  "comment": "Fuel for the convoy on Saturday",
  "target": 250000
}
```

The answer is `201` with `#/$defs/created` — only the order's `id`, its `status`, its `versions`
and its stored `target`; read the order itself from the snapshot, which shows it a few seconds
later:

```json
{
  "id": "7b1e2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d",
  "status": "OPEN",
  "versions": { "lifecycle": 0, "target": 0, "comment": 0 },
  "target": 250000
}
```

The order is created as in the Basetool: the writers of both units are notified, and open
overviews show it at once. Each create counts one unit of the daily write quota.

**The key is kept with the order.** A create under a key your client already created an order of
this member with answers that order with `201` and its current state — also after the gateway's
one-hour cache and after a `5xx` that came after the order was stored. Changed terms are never
applied: the answer carries the stored `target`, so compare it with what you sent.

## Client guidance

- **An isolated area.** Make job orders their own area, so a job-order refusal — the switches
  included — never stops the other areas of a sync pass.
- **Memory only.** Keep every job-order item in memory, own orders included, with a snapshot at
  each start.
- **The create** follows the bank raise: a fresh `Idempotency-Key` per create, held in memory with
  its body until a clear answer; an unclear outcome (no answer, `5xx`, `IDEMPOTENCY_IN_PROGRESS`,
  `RATE_LIMITED`) goes again under the same key and body. After a restart nothing is resent; the
  snapshot shows whether the order exists.
- **`create-options`** is read when the create form opens, not on every pass. Empty lists hide the
  create; `JOB_ORDER_UNITS_NOT_OFFERED` means the options went stale: read them again.
- **The switch codes.** Treat `JOB_ORDER_EXCHANGE_DISABLED` as "pause the job-order area" and
  `JOB_ORDER_WRITES_DISABLED` as "pause the job-order writes", each after `Retry-After`.

## Refusals

| Status | Code | When | Client action |
| --- | --- | --- | --- |
| 400 | `SCHEMA_INVALID` | `limit` outside 1 to 1000, or a create body outside its schema | fix the request |
| 400 | `IDEMPOTENCY_KEY_MISSING` | a create without a valid `Idempotency-Key` | send one |
| 400 | `JOB_ORDER_UNITS_NOT_OFFERED` | a create names a pair the create options do not offer: an unknown unit, a unit the member does not write for, or a responsible unit outside the Profit units; one code for every side | read the create options again and let the member choose |
| 403 | `SCOPE_MISSING` | the token or the registry lacks `exchange.joborders.read` for the read, or `exchange.joborders.write` for the create options and the create | ask the member to grant it |
| 410 | `CURSOR_EXPIRED` | the cursor is older than the retained changes, not one the server issued, or a feed cursor before the feed is published | take a new snapshot |
| 503 | `JOB_ORDER_EXCHANGE_DISABLED` | the job-order family is switched off; `Retry-After: 300` | pause the job-order area only |
| 503 | `JOB_ORDER_WRITES_DISABLED` | the job-order writes are switched off; `Retry-After: 300` | pause the job-order writes only; retry the create under the same key |
| 503 | `EXCHANGE_DISABLED` | the whole exchange is switched off; `Retry-After: 30` | pause every area |

The gateway's other codes apply as everywhere ([errors](../errors.md)).
