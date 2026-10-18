# ADR-0284 — The Lager decides each stock rule once, and the web and the commands adapt it

- **Status:** Accepted
- **Date:** 2026-10-18
- **Deciders:** @greluc
- **Related:** spec `REQ-INV-065`, `REQ-INV-060`, `REQ-INV-059`, `REQ-INV-053`
  ([`inventory-lager.md`](../specs/inventory-lager.md)) · `REQ-XCH-045` ·
  [ADR-0252](0252-stockcommands-gains-the-pool-and-donation-commands.md) ·
  [ADR-0277](0277-the-lager-publishes-the-personal-and-shared-rebooking-as-rebookcommands.md) ·
  `backend/…/inventory/internal/InventoryStockDecisions.java` · issue #2732

## Context

The published commands (`PoolEntryCommands`, `RebookCommands`) answer a refusal as a value
(ADR-0252, ADR-0277); the web services throw. To answer up front, each command re-implemented the
rule it then handed to the web method, which checked it a second time by throwing. Three rules were
written twice: no mission deduction on item stock (`checkTake` and `bookOut`), no personal marker on
an earmarked row (`InventoryRebookCommands` and `rebookPersonal`), and a split's rest at or above the
Materialbörse offer (`checkSplit`, the pool change and the stolen marking). The test for whole-unit
stock was open-coded at eight sites. Two copies of a rule drift: the command side compared with a
tolerance of `1e-6`, the web side with `1e-9` or exactly, so a command could accept what its own web
call then refused.

## Decision

1. **One decision module, `InventoryStockDecisions`,** in `inventory.internal` holds one function
   per operation: book-out, a row's Umbuchung, a split of a part into a new row, and a lot
   rebooking. Each is a pure function over `InventoryItem` values that were loaded under the
   operation's existing lock, and the offer amount the caller read. It reads no repository and
   writes nothing.
2. **A decision returns a refusal value**, an operation-specific enum, or `null` when the operation
   may happen. The lot rebooking returns `RebookRefusal` directly, together with the amount each
   source row gives.
3. **The web and the commands are adapters.** A web service maps every refusal to the problem it
   threw before, so status codes and problem details stay. A command maps every refusal to its
   published refusal value: a fraction of piece or item stock is `UNIT_MISMATCH`, as the lot
   rebooking already answers it. A part that is not positive, which no caller sends, is thrown as
   the web's problem.
4. **Whole units are decided by `InventoryItem#countsWholeUnits`** and its static form over a
   material and a game item; no site tests the quantity type itself.
5. **The decision runs inside the operation's transaction and lock.** The rules of
   `backend/CLAUDE.md` on locking and on the `…WithinTransaction` pattern are unchanged, and every
   mutation records the audit event it recorded before.

## Consequences

- A rule changes in one place and both surfaces follow it. The decision tests run on plain rows
  without a Spring context or mocks.
- The split and book-out tolerances are the same for both surfaces: a split's rest is compared with
  the offer within `1e-6`, the earmark sums within `1e-6`, and a book-out's or split's amount with
  the row's amount exactly. Before, the web compared the offer within `1e-9` and the commands the
  amount within `1e-6`. Amounts are stored and sent with at most three decimals, so the difference
  only touches floating-point noise: an amount a hair above the row is now refused up front by a
  command (`INSUFFICIENT_STOCK`) instead of by its web call afterwards.
- A command's dry run no longer reports a fractional piece amount as possible: it answers
  `UNIT_MISMATCH` up front, where the real run used to throw the web's 400. The exchange rounds piece
  amounts to whole pieces before it calls the Lager, so its clients see no difference.
- A split's earmark check now runs before the new row is written; it throws the same
  `OverAllocationException` the post-write check threw.
- The web services keep their own order of the checks that are not stock rules: the version, the
  access check, the target pool and the membership.

## Alternatives considered

- **Let the commands call the web methods and catch their exceptions.** Rejected: a caught exception
  inside a transaction leaves the persistence context in an unknown state, and a dry run would need
  to write first.
- **One refusal enum for all operations.** Rejected: an exhaustive switch over a shared enum would
  need arms for refusals the operation can never give.
- **Move the rules into the entity.** Rejected: the lot rebooking decides over several rows and the
  offer amount comes from the Materialbörse, which the entity does not know.
