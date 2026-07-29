# ADR-0121 — Item-order edits reconcile lines in place; blueprint drift is detected, not auto-healed

- **Status:** Accepted
- **Date:** 2026-07-29
- **Deciders:** @greluc
- **Related:** REQ-ORDERS-032 · REQ-ORDERS-033 (`docs/specs/orders-item-production.md`) ·
  REQ-ORDERS-025 (production booking) · REQ-ORDERS-023 (requester edit) · REQ-OBS-011 (business
  metrics) · ADR-0099 (job-order item production booking)

## Context

Two defects surfaced together on 2026-07-29 while investigating a production order that displayed
the wrong materials for a `Cryo-Star SL` line.

**(1) Blueprint drift.** An ordered-item line stores the `blueprint` chosen to produce its
`gameItem`, and `JobOrderItemService` validates that the blueprint actually produces the item — but
only *when the line is written*. `ScWikiBlueprintSyncService` re-resolves every blueprint's
`outputItem` from the Wiki feed on **every** run (`resolveGameItem(dto.outputItemUuid())`). When
upstream re-points a blueprint at a different item, the existing order line keeps its now-foreign
`blueprint_id`, and its snapshotted materials silently start describing another recipe. In prod this
had happened to 2 of 60 item lines: order #75's `Cryo-Star SL` line was showing the `HeatSink`
recipe (Agricium/Borase/Pressurized Ice instead of Torite/Pressurized Ice/Iron), and a `Karna Rifle`
line pointed at the `Karna "Valor" Rifle` blueprint. Nothing detected it: no error, no audit event,
no metric — the wrong numbers simply rendered as fact, and people collect and book against them.

**(2) Production loss on edit.** Both item-order edit paths (`updateItemJobOrder` and the requester
`updateItemJobOrderAsRequester`) did `jobOrder.getItems().clear()` followed by a full rebuild through
`buildItemLine`. `orphanRemoval` deleted every `JobOrderItem`, and the rebuilt rows started at
`manufacturedAmount = 0`. So **every** save of an item order silently discarded all recorded
production. The existing freeze did not catch this: it blocks editing once an *item handover* exists,
but production is booked (`REQ-ORDERS-025`) long before any delivery — exactly the window in which
people edit orders. The write payload carried no stable line identity either (only a transient
`clientLineId` for sub-assembly provenance), so the server could not have matched lines even if it
had wanted to.

The two are linked: the repair for a drifted line is to re-save the order — which, before this ADR,
would have cost the line its production counter.

### Alternatives considered

**For the edit path.** *(A)* Freeze item-order editing entirely once anything is manufactured. Safe
and one line of code, but it would block the common, legitimate "add another item to the order" while
a single line happens to be in production — and it would make drifted lines unrepairable. *(B)* Match
lines heuristically by `(gameItemId, blueprintId)` instead of a persistent id. Needs no DTO change,
but is ambiguous the moment an order carries two lines of the same item, and silently mis-attributes
production when the blueprint changes — the very case we must support. *(C)* Carry `manufacturedAmount`
in the write payload and restore it onto the rebuilt row. Rejected outright: it makes a
client-supplied number authoritative over booked production.

**For the drift.** *(D)* Auto-heal during the sync: when exactly one blueprint still produces the
ordered item, re-point the line and re-derive its materials. Tempting, and it would have fixed both
prod rows unattended — but it silently changes what people must supply for an in-flight order, after
they may already have collected against the old numbers. *(E)* Re-validate on read and throw. Turns a
data-quality problem into an outage for the whole order page. *(F)* Add a DB constraint tying
`job_order_item.blueprint_id` to the blueprint's output item. Impossible: the blueprint's output is
mutable and owned by the sync, so the constraint would make a legitimate upstream correction fail the
sync instead.

## Decision

**Edits reconcile, they do not rebuild.** `CreateJobOrderItemLineDto` gains the persistent `id` of
the line it updates (`null` = new line), which the item editor renders as a hidden input and posts
back. `JobOrderService#reconcileItemLines` replaces the former clear-and-rebuild for all three
callers (create degenerates to "every line is new"): a matched line is re-derived **in place** via
the new `JobOrderItemService#applyItemLine`, which overwrites the game item, blueprint, amount and
the snapshotted materials but never touches the row's identity or its counters. Lines the payload
drops are removed; lines with no match are added.

Because a booked line describes units that physically exist, three guards apply once
`manufacturedAmount > 0`: the ordered **game item** is frozen, the **amount** may not fall below
what was produced, and the line may not be **removed** — each a 400. The **blueprint may still
change**, deliberately: that is the repair path for a drifted line, and re-pointing a recipe does not
invalidate units already made. The editor mirrors all three client-side (hidden id, pinned `min`, no
remove button, a note stating the produced count) so the rules are visible before the save, not after.

**Drift is detected and surfaced, never auto-healed.** `JobOrderItemDto.blueprintStale` exposes the
mismatch on the read model; the order-detail row renders a warning chip explaining that the shown
materials belong to a foreign recipe. An hourly `JobOrderIntegrityTask` (mirroring
`BankLedgerIntegrityTask`, gated by `app.joborder.integrity.enabled`) feeds
`basetool_job_order_integrity_violations{category="item_line_blueprint_drift"}` and logs one `ERROR`
per drifted line — order display id, ordered item, and what the blueprint produces now, never the
order's user-entered handle. `JobOrderItemBlueprintDrift` alerts on `> 0`, and
`JobOrderIntegritySweepStale` guards against the frozen-gauge false silence. The fix stays a human
action: re-saving the order re-derives the line, and since the stale blueprint is no longer offered
for that item, the editor's picker falls back to one that still produces it.

## Consequences

- **Positive.** Booked production survives an edit — the data-loss bug is closed at its root rather
  than papered over with a freeze. Drift becomes visible within an hour instead of never, on three
  surfaces (read model, UI, metric). The repair is one ordinary save, needs no admin tooling and no
  migration. The persistent line `id` also makes future per-line operations addressable.
- **Negative / cost.** The write payload now carries a persistent id, so any client that builds an
  item-order edit by hand must echo it — omitting it makes the server treat every line as new, which
  is rejected outright once production exists (loud, not silent, which is the point). The reconcile
  is more code than `clear()` + rebuild, and `applyItemLine` mutates a managed entity, so the
  clear-then-re-add of the `materials` set relies on `orphanRemoval` ordering within one flush.
- **Neutral.** The two prod rows are not fixed by this change; they are now *reported*, and repairing
  them is a normal edit of each order. The sweep adds one indexed query per hour.
- **Follow-up.** If drift turns out to be frequent rather than a twice-a-year event, revisit
  alternative *(D)*: auto-healing becomes defensible if it is paired with a notification to the
  order's participants, which is out of scope here.

