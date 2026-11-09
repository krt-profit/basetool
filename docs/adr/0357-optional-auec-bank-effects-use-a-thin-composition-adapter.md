# ADR-0357 - Optional aUEC Bank effects use a thin composition adapter

- **Status:** Accepted - implementation pending
- **Date:** 2026-11-09
- **Deciders:** Repository owner (@greluc)
- **Amends:** ADR-0021 (authorisation-only outcome), ADR-0254 (optional purpose command/outcome coverage)
- **Related:** [orders-auec-bank.md](../specs/orders-auec-bank.md),
  [bank.md](../specs/bank.md), ADR-0232, ADR-0233, ADR-0287, ADR-0352, ADR-0353,
  [ADR-0358](0358-one-configured-transfer-fee-policy-serves-bank-and-orders.md)

## Context

An aUEC order can operate without the Kartellbank. Its optional Bank use includes early deposits,
existing-fund provision, account/custody transfers, requester delivery and returns. Selected Bank
outcomes and their order effects must commit together, including the refusal of effects under
invalid responsibility. The Bank must retain its own authority, global custody and append-only
ledger; an order must not depend on Bank state merely to operate.

The module map places JobOrder at rank 10, Bank at 11 and the application composition at 14.
JobOrder cannot import the higher Bank module. The Bank independence rule also forbids importing
JobOrder aggregates, even though the rank order alone would permit that direction. The present
purpose API covers only a subset of required payments, and its confirmed request always refers to
a ledger transaction. It has no outcome for authorisation without a posting or later ledger reversal.

## Decision

Use a thin adapter in the existing application composition. It consumes only the public JobOrder
and Bank APIs for this integration and owns neither aggregate nor monetary policy. JobOrder owns
logical operations, submitted attempts and financial order effects. Bank owns admission, approvals,
requests, custodians and ledger effects. Each module alone mutates its data.

A narrow JobOrder-owned coordination port is implemented by the adapter. Bank-owned synchronous
outcome/reversal SPIs connect through the adapter to narrow JobOrder commands. The lower modules
do not reference adapter implementations, and the existing ranks remain unchanged. A required
missing or ambiguous result handler fails closed.

Effect-bearing cross-module calls are MANDATORY in one transaction, as ADR-0232 requires. Bank and
order effects, their audit, and the once-only operation/attempt checks succeed together. An invalid
responsibility or otherwise refused order effect rolls back the Bank decision/posting. The outcome
command is narrow integration authority; it grants Bank staff no general JobOrder edit privilege.
Real game payments cannot be rolled back by the database and remain recoverable facts of the same
operation.

Extend the Bank's public contract only for the accepted direct/requested payments, holder-transfer
requests, actual counterparties, authorisation-only provision and reversal outcomes. A successful
no-ledger authorisation retains an auditable identity and debit-equivalent controls, including the
single-account coverage check, without a fake transaction or a Bank reservation. The Bank purpose
stays opaque. The contract distinguishes a posted transaction, an authorised no-posting outcome and
an unresolved/refused attempt without treating HTTP acceptance as booked value.

Specify one deterministic resource protocol for order-originating commands, Bank confirmation,
cancellation, direct posting and reversal. It must prevent inverse request/account/order lock
ordering and concurrent acceptance of superseded attempts. No duplicate Ledger booking may be
hidden by merely deduplicating the later order callback.

Notifications and live propagation remain post-commit effects. They are not used to settle financial
invariants. The connected-client Bank exchange contract and its separate admission policy are not
expanded by this integration.

## Consequences

- Bank and JobOrder remain replaceable behind their own public contracts, without a rank change,
  cycle, extra deployable service or foreign repository writes.
- Required atomic callbacks include direct and no-ledger outcomes and reversals; adding one purpose
  enum value to the existing request API is insufficient.
- Operation identity, immutable terms and active-attempt lineage protect both domains through
  recovery, replacements and delayed outcomes.
- Architecture, transaction, real-database race and rollback tests must prove the contracts before
  implementation is considered complete. This ADR records no executed implementation checks.

## Alternatives considered

- **JobOrder imports Bank internals or its higher-ranked API:** violates module direction and hides
  the owning-domain authority boundary.
- **Bank directly imports the order aggregate:** violates Bank independence and couples its ledger
  to one consumer's state model.
- **After-commit financial handling:** can commit the Bank while refusing the order or audit; it
  cannot provide the agreed atomicity.
- **A new integration service/module:** adds another deployment/module surface for one bounded
  composition seam without improving the required ownership boundary.
