# ADR-0384 — The currency wipe resets the Bank as of the live time

- **Status:** Accepted
- **Date:** 2026-11-14
- **Deciders:** @greluc
- **Related:** [patch-change spec](../specs/patch-change.md) (REQ-PATCH-040…046) ·
  [bank spec](../specs/bank.md) (REQ-BANK-006, REQ-BANK-013) ·
  [ADR-0039](0039-bank-holder-ledger-decoupled-from-accounts.md) ·
  [ADR-0381](0381-the-live-time-decides-what-a-patch-change-touches.md) · decisions #3258, #3257,
  #3247 (amendment)

## Context

A Star Citizen patch can wipe every player's in-game money at the moment it goes live. The
Kartellbank books what its custodians hold in the game, and its standalone wipe reset
(REQ-BANK-013) zeroes every account and holder balance as it stands when the admin presses the
button. A patch change is often recorded hours after the patch went live (ADR-0381); by then the
Bank has booked post-patch deposits, withdrawals and transfers that the game did not wipe.
Zeroing the current balances would wipe those post-patch facts with the pre-patch money.

The aUEC orders track money that players report as held for an order, outside the Bank, and
pending Bank requests and order Bank attempts may still name money from before the patch.

## Decision

1. **The currency wipe is one option of the patch change**, off by default. It runs at the trigger
   inside the all-or-nothing step, for the aUEC orders first and then for the Bank, with the live
   time as its criterion.
2. **One reset booking offsets exactly the balances as of the live time**, account legs and holder
   legs alike: each balance is the sum of the postings before the live time. Every booking after
   the live time stays. The reset shares its booking with the standalone button, which keeps its
   behaviour.
3. **An overdraft refuses the wipe.** When an account's current balance cannot cover its offset,
   because more was booked out of it than into it since the live time, the no-overdraft rule
   (REQ-BANK-006) holds and the patch change is refused with its reason, in the preview as well.
   The admin corrects those bookings or chooses a later live time.
4. **Pending requests and attempts from before the live time are cancelled**, each with the patch
   change as its reason. An order cancels its own attempts and their Bank requests under its order
   guard, before the Bank takes its request and account locks; the Bank cancels the remaining
   requests.
5. **The orders book what is lost as real shortages**: per holding, the part that existed at the
   live time and is still held. Open commitments from before the live time end; statuses do not
   change.
6. **The Bank's trail records one `WIPE_RESET_EXECUTED`** naming the patch change, the live time
   and the cancelled requests; the job orders record one summary event of their own.

## Consequences

- An admin can record a currency wipe late without losing the Bank bookings members made after the
  patch went live.
- A Bank whose post-patch bookings already spent pre-patch money cannot be wiped until those
  bookings are corrected; the wipe never leaves an account below zero.
- A reset after an earlier standalone reset in the same window would offset the same money twice;
  the overdraft refusal stops it on the accounts, and the admin picks a live time after the earlier
  reset.
- The order history and the Bank both explain each wiped amount by the patch change.

## Alternatives considered

- **Zero the current balances, as the standalone reset does.** Simple, but it wipes every
  post-patch booking with the pre-patch money.
- **Clamp the offset at the current balance.** No overdraft, but the reset no longer says what the
  Bank held at the live time, and the missing part silently stays booked.
- **Let the account go negative.** It keeps the exact offset but breaks the no-overdraft rule and
  the ledger integrity check, which then alerts on a state the Bank created itself.
