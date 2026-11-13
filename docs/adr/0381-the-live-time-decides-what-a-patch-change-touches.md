# ADR-0381 — The live time decides what a patch change touches

- **Status:** Accepted
- **Date:** 2026-11-13
- **Deciders:** @greluc
- **Related:** [patch-change spec](../specs/patch-change.md) (REQ-PATCH-003, REQ-PATCH-004,
  REQ-PATCH-006) · [ADR-0380](0380-the-patchchange-module-records-patch-changes-and-runs-their-effects.md) ·
  [ADR-0097](0097-inventory-piece-scu-stock-merge.md) · decisions #3247 (amendment), #3254
  (amendment)

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
   without a start, are cancelled; only members whose account existed before it are asked for a
   home location. Previews count the same selection.
3. **The live time is fixed once triggered**, because the effects already ran against it. Only the
   label stays correctable.
4. **No mixing.** From the latest live time on, the write-time merge skips every row created before
   it, as survivor and as candidate, and the merge probe does not count it. The write lands its own
   row instead, so a pre-patch row holds only pre-patch stock and its creation time stays the
   criterion. A candidate row without a creation time counts as older; before the first patch change
   the rule does not apply. The Lager asks the patch-change module through
   `inventory.api.StockMergeCutoff`.

## Consequences

- An admin can record a patch change late without destroying what members entered after the
  patch went live.
- Stock of one identity can stand in two rows after a patch, one from before and one from after
  the live time; the group-on-read display stacks them as before.
- Merging pre-patch rows stays off after the last patch change; a later patch change moves the
  cutoff forward.

## Alternatives considered

- **The trigger time as the criterion.** Simple, but it wipes and cancels post-patch facts entered
  between the live time and the trigger.
- **A correctable live time.** The wipe and the refinery effect already ran against it; a corrected
  live time would describe effects that never happened.
- **Merge freely and mark merged rows.** The marker would have to split quantities by origin; the
  creation time is already that marker when no merge crosses the live time.
