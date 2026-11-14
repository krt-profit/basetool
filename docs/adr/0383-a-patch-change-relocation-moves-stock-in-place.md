# ADR-0383 — A patch change's relocation moves stock and ships in place

- **Status:** Accepted
- **Date:** 2026-11-14
- **Deciders:** @greluc
- **Related:** [patch-change spec](../specs/patch-change.md) (REQ-PATCH-020…029) ·
  [ADR-0380](0380-the-patchchange-module-records-patch-changes-and-runs-their-effects.md) ·
  [ADR-0381](0381-the-live-time-decides-what-a-patch-change-touches.md) ·
  [ADR-0229](0229-exchange-stock-writes-take-an-advisory-lock-per-lot.md) ·
  [ADR-0360](0360-material-promises-track-uncovered-demand-and-explicit-provision.md) ·
  decisions #3249, #3252 (amendment), #3253 (amendment), #3255, #3257

## Context

A patch that delivers everything to a new home location moves, in the game, every item and ship a
player held before it went live. The Basetool follows once the member names the new home. Until
then nobody but the member knows where the stock really is, so it must not change for others.

The Lager is append-only (REQ-INV-001): a move inserts a new row and lowers or deletes the source.
Applied to a relocation that would drop every row's identity, and with it the job-order and mission
earmarks, the donation pledges and the Materialbörse offers that stand on the rows. The bulk rebook
`LOCATION` cannot move everything a member holds either: it is owner-scoped, stamps one org unit
and aborts on a pledge (#3249). `ShipRepository#setLocationForOwner` is a bulk update that bumps no
version, so a stale form or client could write the old location back.

## Decision

1. **The relocation changes the location in place**, as the one exception to REQ-INV-001. Every
   row keeps its identity, quantity, quality, markers, pool, earmarks, pledges and offers. Nothing
   merges at the target, and no material promise is credited again (ADR-0360). Ships take the new
   location on the managed rows. Every moved row and ship gets a new version.
2. **Only what existed before the live time moves**: stock rows (own and pool) and ships created
   before it, or without a creation time (ADR-0381). Rows and ships already at the target stay.
3. **One transaction per member, all or nothing, once per phase.** It runs inside
   `PatchChangeWrites.runFor` of the phase, so the exchange feed attributes it to the `patch`
   source. It takes the patch-change history's lock in shared mode, claims the member's record
   with an `INSERT … ON CONFLICT DO NOTHING`, then the shared hierarchy lock, the lot locks of the
   old and new keys in ascending order and the rows. It validates no linked job order: like the
   wipe, it is a game fact and no order write (REQ-ORDERS-055).
4. **A lock holds the rest of the system off until the member relocates.** Each lower module owns
   an SPI the patch-change module implements (`inventory.api.StockRelocationLock`,
   `hangar.api.ShipRelocationLock`, ADR-0380 item 4). The Lager notes every row Hibernate updates
   or deletes in a writing transaction through an entity listener and checks them before the
   commit, after a final flush; the hangar checks its ship writes directly. The refusal is
   `409 RELOCATION_PENDING`, on the web for everyone but the member and admins, on the exchange
   for every client. Writes of a patch change itself and work without a caller pass.
5. **The phase belongs to the latest patch change with the home-location option on.** A later one
   with the option supersedes it and asks again; one without the option leaves it open; an admin
   may end it early, which writes only the phase's columns.

## Consequences

- Earmarks, pledges and offers survive a relocation without being copied, and no consumer of a row
  id breaks.
- The exchange stock feed shows a relocated lot as a tombstone for the old key and an item for the
  new key, which also includes stock already at the target; the client documentation says so.
- The lock's check of the Lager is central and sees only what Hibernate flushes; a bulk statement
  is not checked. Today the bulk deletes and updates on Lager rows are the admin's global wipe, the
  account erasure and the patch change's own effects, none of which the lock should refuse.
- The guard flushes before the commit of every writing transaction that loaded a Lager row.

## Alternatives considered

- **Append-only moves (insert at the target, delete the source).** Loses every earmark, pledge and
  offer, or copies them with all their guards; the decision #3253 rejected it.
- **The bulk rebook `LOCATION`.** Owner-scoped, one org unit, aborts on a pledge (#3249).
- **A lock check in each write path or in the order-write gate.** Several paths change rows without
  passing the order-write gate's validation (a note, a delivery flag), and the gate's validation
  also sees rows a write only observes; the listener sees exactly the rows that change.
- **A per-member snapshot of the asked members at the trigger.** The live time and the accounts'
  creation times answer it without a second source of truth.
