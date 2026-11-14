# ADR-0381 — The live time decides what a patch change touches

- **Status:** Accepted
- **Date:** 2026-11-13
- **Deciders:** @greluc
- **Related:** [patch-change spec](../specs/patch-change.md) (REQ-PATCH-003, REQ-PATCH-004,
  REQ-PATCH-006) · [ADR-0380](0380-the-patchchange-module-records-patch-changes-and-runs-their-effects.md) ·
  [ADR-0097](0097-inventory-piece-scu-stock-merge.md) · decisions #3247 (amendment), #3254
  (amendment), owner decision D3 on the review of #3423

## Context

An admin records a patch change some time after the patch went live, often hours later. In
between, members keep playing and keep entering what they hold: stock booked after the patch, a
refinery job started after it, a new account. An effect keyed on the moment of the trigger would
wipe, cancel or relocate these post-patch facts with the pre-patch ones.

The write-time stock merge (REQ-INV-026) folds rows of one stock identity into the newest row. A
post-patch booking would absorb a pre-patch row, or add to it, and a row's creation time would no
longer say whether its stock predates the patch.

## Decision

1. **Each patch change has a live time**: when the patch went live. It defaults to the trigger,
   may be backdated to any point after the previous patch change's live time, and never lies in the
   future.
2. **The live time decides what every effect touches.** Only stock rows and ships created before
   it are wiped, relocated or locked; only refinery orders started before it, or created before it
   without a start, are cancelled, and an order with neither time counts as started before it; only
   members whose account existed before it are asked for a home location. A row or ship without a
   creation time counts as created before the live time everywhere. Previews count the same
   selection.
3. **The live time is fixed once triggered**, because the effects already ran against it. Only the
   label stays correctable.
4. **No mixing while a row can still be relocated.** The wipe runs at the trigger, so it needs no
   lasting window; the relocation runs later, once per member. While a member's relocation is
   pending in the open home-location phase, the write-time merge skips every row of that member
   created before the phase's live time, or without a creation time, as survivor and as candidate,
   and the merge probe does not count it. The write lands its own row instead, so the pre-patch row
   holds only pre-patch stock and its creation time stays the relocation's criterion. Once the
   member relocates, or the phase ends or is superseded, the member's rows merge again; before the
   first phase the rule does not apply. The Lager asks the patch-change module per holder through
   `inventory.api.StockMergeCutoff#cutoffFor`.
5. **A provision correction is the one exception.** A provision correction that adds quantity (the
   truthful upward correction of an order's material provision) restores a historic fact on the
   very row the provision came from, so it may add quantity in place to a row created before the
   live time, even while the holder's relocation is pending. The corrected quantity was always the
   row's; nothing post-patch mixes in.

## Consequences

- An admin can record a patch change late without destroying what members entered after the
  patch went live.
- Stock of one identity can stand in two rows while a member's relocation is pending, one from
  before and one from after the live time; the group-on-read display stacks them as before, and the
  next write after the relocation merges them.
- A member who never relocates keeps the two rows until the phase ends; the merge resumes then.

## Alternatives considered

- **The trigger time as the criterion.** Simple, but it wipes and cancels post-patch facts entered
  between the live time and the trigger.
- **A correctable live time.** The wipe and the refinery effect already ran against it; a corrected
  live time would describe effects that never happened.
- **Merge freely and mark merged rows.** The marker would have to split quantities by origin; the
  creation time is already that marker when no merge crosses the live time.
- **Keep pre-patch rows apart for good after every patch change.** It splits stacks long after the
  relocation, when no effect reads the creation time any more, which is wider than the decision on
  #3247 asks for.
- **Refuse a provision correction on a pre-patch row.** The order's history would then disagree
  with the stock the member actually gave, for a pure bookkeeping reason.
