# Job orders

The job orders of all three kinds that the member relates to, under scope
`exchange.joborders.read`: material orders, item orders and aUEC orders, which ask for a provision
of credits. Under scope `exchange.joborders.write` a Logistician creates aUEC orders on behalf of
their own units ([creating an aUEC order](#creating-an-auec-order)), cancels them before any
money moved, and changes their target above what was handed over and their comment
([writes on an existing order](#writes-on-an-existing-order)). Every route lies below
`/exchange/v1/job-orders`.

**The family goes on in two steps.** An administrator turns the job-order family on with the first
grant of `exchange.joborders.read` in the registry, and the writes only after the first grant of
`exchange.joborders.write`. While the family is off, every path at or below
`/exchange/v1/job-orders` answers `503 JOB_ORDER_EXCHANGE_DISABLED` with `Retry-After: 300`. Pause
the job-order area only and keep syncing every other area ([errors](../errors.md)). The writes have
a switch of their own, `503 JOB_ORDER_WRITES_DISABLED`; the reads go on while only the writes are
off.

Processing, every money operation and every Bank decision stay in the Basetool. A client never
accepts, processes, hands over, provides, settles or deletes an order and never changes its
assignees or support; each order carries a [`link`](#the-link) to its page for all of that.

## Retention

| Route | Retention class | What a client may do with it |
| --- | --- | --- |
| `GET /exchange/v1/job-orders` | job-order data | keep it for at most 7 days, outside cloud-synced folders, backups and diagnostics; never forward it |
| `GET /exchange/v1/job-orders/create-options` | job-order data | read it when the create form opens; keep it no longer than the form |
| `POST /exchange/v1/job-orders` | job-order data | keep the answer for at most 7 days, outside cloud-synced folders, backups and diagnostics; never forward it |
| `POST /exchange/v1/job-orders/{id}/cancel` | job-order data | keep the answer for at most 7 days, outside cloud-synced folders, backups and diagnostics; never forward it |
| `POST /exchange/v1/job-orders/{id}/target` | job-order data | keep the answer for at most 7 days, outside cloud-synced folders, backups and diagnostics; never forward it |
| `POST /exchange/v1/job-orders/{id}/comment` | job-order data | keep the answer for at most 7 days, outside cloud-synced folders, backups and diagnostics; never forward it |

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
  leaves the set when its 7 days end: the snapshot leaves it out at once, and the feed answers it
  in `removed` at the latest one day later, after the nightly run. An aUEC order whose settlement
  closes only after its 7 days arrives in `removed` a few seconds after the settlement closed.
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
| `limits` | the two caps on creates through clients and the member's own figures against them ([limits](#limits)) |

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
and its stored `target`; read the order itself from the feed, which brings it a few seconds
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

### Limits

Two caps bound the aUEC orders a member creates through clients — through yours and any other
client together. Orders created in the Basetool count for neither and are never refused.

| Field of `limits` | Meaning |
| --- | --- |
| `openOrderCap` | the most open or in-progress orders the member may hold that were created through clients; 20 by default |
| `openOrders` | the member's open or in-progress orders created through clients |
| `dailyCreateCap` | the most orders the member may create through clients in one UTC day; 20 by default |
| `createdToday` | the orders the member created through clients since the last UTC midnight, cancelled ones included |

```json
{ "openOrderCap": 20, "openOrders": 3, "dailyCreateCap": 20, "createdToday": 1 }
```

- Once `openOrders` reaches `openOrderCap`, a create answers `409 JOB_ORDER_OPEN_ORDER_CAP` until one
  of those orders closes — cancelled, completed or rejected, wherever that happens.
- Once `createdToday` reaches `dailyCreateCap`, a create answers `409 JOB_ORDER_DAILY_CREATE_CAP`
  with `Retry-After`, the seconds until the next UTC midnight. Cancelling an order does not give
  its create back.
- At both caps the open-order cap answers. The figures are the member's own, read when
  `create-options` is read; grey out the create while either cap is reached, and read the options
  again when the form opens next.
- The caps refuse creates only, and a refused create created nothing but still counts its unit of
  the daily write quota. A create under a key that already created an order answers that order
  with `201` whatever the caps say.

## Writes on an existing order

A member who is a Logistician of an order's **requesting** unit, by the rule of the create, changes
an **aUEC** order through a client, under scope `exchange.joborders.write`. Each write is a single
`POST` below the order, with an `Idempotency-Key`, that echoes the version of the one section it
edits under a named field, and answers `200` with `job-order-write` `#/$defs/written` — the
[target change](#target) adds the stored `target`:

```json
{
  "id": "7b1e2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d",
  "status": "CANCELLED",
  "versions": { "lifecycle": 1, "target": 0, "comment": 0 }
}
```

Every answer carries the new `versions`, so you can write on without reading the feed first; the
feed brings the whole order a few seconds later. Write only on an explicit action of the member.

**The order of checks.** The first rule that fails answers, and nothing is written:

1. the order id is a UUID and the body matches its schema, else `400 SCHEMA_INVALID`;
2. the order is in the member's job-order set, else `404 NOT_FOUND` — an unknown, a deleted and a
   hidden order answer alike, so drop it from what you show;
3. the member writes for its requesting unit and it is an aUEC order, else `403 NOT_PERMITTED` —
   a MATERIAL or ITEM order, or one the member only reads;
4. the echoed version is current, else `409 VERSION_CONFLICT`;
5. its responsibility is valid (`responsibilityInvalid` is `false`), else
   `409 JOB_ORDER_RESPONSIBILITY_INVALID`;
6. its `status` admits the write, else `409 JOB_ORDER_CLOSED`;
7. the write's own rule.

The server decides all of them under the order's locks, so a change in the Basetool at the same
moment yields one clear answer. Pre-check what you can from the read: `kind`, `relations`,
`status` and `responsibilityInvalid`.

**Retries.** The writes on an existing order keep no key on the server. A retry under the same key
within the gateway's hour gets the first answer again; after it the moved version answers
`409 VERSION_CONFLICT`, so read the order again before you decide anything.

There is no undo through the exchange: a wrong write is set right in the Basetool, through the
order's `link`.

### Cancel

`POST /exchange/v1/job-orders/{id}/cancel` with an `Idempotency-Key` — the body is
[`job-order-write`](../schemas/) `#/$defs/cancel`:

```json
{ "lifecycleVersion": 0 }
```

It cancels an `OPEN` or `IN_PROGRESS` order (else `409 JOB_ORDER_CLOSED`), only while **no money
operation of the order has been accepted**. Once one has, the cancel answers
`409 JOB_ORDER_MONEY_STARTED`, one code for every money fact, and cancelling happens in the
Basetool. The cancel runs as in the Basetool: the order leaves the queue, the writers of both units
are told that it ended, naming your application, and open pages update at once. There is no reopen
through a client: a mistaken cancel is reopened in the Basetool.

### Target

`POST /exchange/v1/job-orders/{id}/target` with an `Idempotency-Key` — the body is
[`job-order-write`](../schemas/) `#/$defs/targetChange`, the new target in whole aUEC from 1 to
10¹² and `versions.target` as last read:

```json
{ "target": 400000, "targetVersion": 0 }
```

It raises or lowers the target of an `OPEN` or `IN_PROGRESS` order (else `409 JOB_ORDER_CLOSED`),
only to an amount **above the order's `netHandedOver`**. A target at or below it would complete the
order and answers `409 JOB_ORDER_TARGET_TOO_LOW`; completing an order happens in the Basetool. So
the change never completes or reopens an order and moves no money. Pre-check the new target against
the `netHandedOver` you read; the server decides under the order's locks, so a handover at the same
moment counts. The answer is `job-order-write` `#/$defs/targetChanged`:

```json
{
  "id": "7b1e2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d",
  "status": "OPEN",
  "versions": { "lifecycle": 0, "target": 1, "comment": 0 },
  "target": 400000
}
```

The change runs as in the Basetool: the order's target history names the member and your
application, the responsible unit's writers are told of the new need, naming your application, and
open pages update at once. A completed order's target is changed in the Basetool.

### Comment

`POST /exchange/v1/job-orders/{id}/comment` with an `Idempotency-Key` — the body is
[`job-order-write`](../schemas/) `#/$defs/comment`:

```json
{ "comment": "Deliver to Area18, hangar 3", "commentVersion": 0 }
```

It replaces the order's `comment` with plain text of at most 1000 characters; the text is trimmed,
and `null` or a blank text clears it. It echoes `versions.comment` alone: the contact is never
written through a client, so a contact change in the Basetool never conflicts with it. Every
status but `CANCELLED` admits it (else `409 JOB_ORDER_CLOSED`). The comment then shows your
application's name on the order until it is changed in the Basetool, the responsible unit is told
of the change, naming your application, and open pages update at once. Sending the comment the
order already holds changes nothing and answers the current `versions`.

## Client guidance

- **An isolated area.** Make job orders their own area, so a job-order refusal — the switches
  included — never stops the other areas of a sync pass.
- **At most 7 days.** Keep every job-order item, own orders included, for at most 7 days, outside
  cloud-synced folders and out of backups and diagnostics. A client that keeps nothing across
  restarts reads a snapshot at each start and the feed on every pass after it.
- **The create** follows the bank raise: a fresh `Idempotency-Key` per create, held in memory with
  its body until a clear answer; an unclear outcome (no answer, `5xx`, `IDEMPOTENCY_IN_PROGRESS`,
  `RATE_LIMITED`) goes again under the same key and body. After a restart nothing is resent; the
  snapshot shows whether the order exists, and within a run the feed brings it.
- **`create-options`** is read when the create form opens, not on every pass. Empty lists hide the
  create; `JOB_ORDER_UNITS_NOT_OFFERED` means the options went stale: read them again.
- **The caps** are clear answers, not outcomes to retry: drop the key, grey out the create, and
  create again under a new key once an order closes (`JOB_ORDER_OPEN_ORDER_CAP`) or after
  `Retry-After` (`JOB_ORDER_DAILY_CREATE_CAP`). The same key replays the refusal for an hour.
- **Writes on an existing order** take a fresh key per write and echo the version just read. A
  `409 VERSION_CONFLICT` means "read the order again", a `404 NOT_FOUND` "drop it"; the other
  refusals are clear answers that a retry does not change: offer the order's `link`. Since nothing
  is undone through the exchange, confirm a cancel or a target change with the member before
  sending it.
- **The switch codes.** Treat `JOB_ORDER_EXCHANGE_DISABLED` as "pause the job-order area" and
  `JOB_ORDER_WRITES_DISABLED` as "pause the job-order writes", each after `Retry-After`.

## Refusals

| Status | Code | When | Client action |
| --- | --- | --- | --- |
| 400 | `SCHEMA_INVALID` | `limit` outside 1 to 1000, a write body outside its schema, or an order id that is no UUID | fix the request |
| 400 | `IDEMPOTENCY_KEY_MISSING` | a write without a valid `Idempotency-Key` | send one |
| 400 | `JOB_ORDER_UNITS_NOT_OFFERED` | a create names a pair the create options do not offer: an unknown unit, a unit the member does not write for, or a responsible unit outside the Profit units; one code for every side | read the create options again and let the member choose |
| 403 | `SCOPE_MISSING` | the token or the registry lacks `exchange.joborders.read` for the read, or `exchange.joborders.write` for the create options and the writes | ask the member to grant it |
| 403 | `NOT_PERMITTED` | a write on an order the member reads but does not write for, or on a MATERIAL or ITEM order | stop; offer the order's `link` |
| 404 | `NOT_FOUND` | a write on an order that is unknown, deleted or outside the member's set, alike | drop the order locally |
| 409 | `VERSION_CONFLICT` | the echoed section version is stale, also on a retry after the one-hour cache | read again, merge, retry |
| 409 | `JOB_ORDER_RESPONSIBILITY_INVALID` | a write on an order whose responsibility is invalid | do not retry; offer the order's `link` |
| 409 | `JOB_ORDER_CLOSED` | a cancel or a target change of an order that is not `OPEN` or `IN_PROGRESS`, or a comment change on a `CANCELLED` order | read again; offer the order's `link` |
| 409 | `JOB_ORDER_MONEY_STARTED` | a cancel after the first accepted money operation; one code for every money fact | offer the order's `link`; cancelling happens in the Basetool |
| 409 | `JOB_ORDER_TARGET_TOO_LOW` | a target change to an amount at or below the order's `netHandedOver` | read `netHandedOver` again; completing the order happens in the Basetool |
| 409 | `JOB_ORDER_OPEN_ORDER_CAP` | a create while the member holds `openOrderCap` open orders created through clients ([limits](#limits)) | grey out the create until one of them closes; then create under a new key |
| 409 | `JOB_ORDER_DAILY_CREATE_CAP` | a create once the member created `dailyCreateCap` orders through clients this UTC day; `Retry-After` runs to the next UTC midnight | grey out the create until `Retry-After`; then create under a new key |
| 410 | `CURSOR_EXPIRED` | the cursor is older than the retained changes or not one the server issued | take a new snapshot and reconcile it against the last baseline |
| 503 | `JOB_ORDER_EXCHANGE_DISABLED` | the job-order family is switched off; `Retry-After: 300` | pause the job-order area only |
| 503 | `JOB_ORDER_WRITES_DISABLED` | the job-order writes are switched off; `Retry-After: 300` | pause the job-order writes only; retry the create under the same key |
| 503 | `EXCHANGE_DISABLED` | the whole exchange is switched off; `Retry-After: 30` | pause every area |

The gateway's other codes apply as everywhere ([errors](../errors.md)).
