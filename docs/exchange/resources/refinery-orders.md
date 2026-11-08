# Refinery orders

The member's own refinery work orders: where an order runs, what it refines, when it ends and what
it cost. Reading needs `exchange.refinery.read`. Only orders the member owns are served, whatever
created them: the web, a reviewed [draft](drafts.md) or any client.

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
  cancelled order is not removed; it arrives with the status `CANCELED`.

An order is in the feed again when one of its goods changes, once per page however many goods
changed. It is also in the feed again when the `name`, `uex` or `guid` of its `location` changes,
so the `location` you hold is never stale.
