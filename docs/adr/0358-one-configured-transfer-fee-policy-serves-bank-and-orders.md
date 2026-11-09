# ADR-0358 - One configured transfer-fee policy serves Bank and orders

- **Status:** Accepted - implementation pending
- **Date:** 2026-11-09
- **Deciders:** Repository owner (@greluc)
- **Amends:** ADR-0052 (shared calculation home and true same-player withdrawal exemption)
- **Related:** [bank.md](../specs/bank.md) (REQ-BANK-033),
  [orders-auec-bank.md](../specs/orders-auec-bank.md) (REQ-ORDERS-072), ADR-0002, ADR-0039,
  [ADR-0357](0357-optional-auec-bank-effects-use-a-thin-composition-adapter.md)

## Context

Bank and aUEC orders need the same centrally configured whole-aUEC transfer-fee calculation.
Submitted coupled operations must preserve the rate and fee through waiting and retries; current
configuration cannot silently reprice them. The calculator and rate parsing currently live in
bank.internal, which JobOrder may not import. Kernel/platform must not acquire Bank or JobOrder
business policy, and unrelated existing financial flows must retain their semantics.

The existing admin module owns runtime system settings and sits below both consumers. The setting
operation.transfer_fee_rate remains the central source; its source default of 0.005 is not a claim
about a production or game rate.

## Decision

Publish one narrow configured transfer-fee policy through admin.api, implemented in admin.internal.
It resolves and validates the central rate, supplies an immutable snapshot and computes exact
whole-aUEC fees from an explicit snapshot with HALF_UP. Both Bank and order commands use it for the
accepted coupled operations. The policy does not read later settings during a replay or historical
correction.

Keep custody, approvals, payer selection and fulfilment in their owning domains. Callers classify a
real transfer, KRT-funded holder reconciliation and a true same-player purpose change. This is not
a user-editable fee or rate field. Existing Bank HOLDER_TRANSFER continues to charge CARTEL, and
the order records that external fee funding without consuming additional order holdings.

A true same-player withdrawal/purpose change without an actual game transfer is fee-free, with
unchanged account permissions and approvals. A matching name alone is insufficient. Other actual
transfers retain their approved fee funding and amount semantics; unrelated existing flows are not
converted to a different rounding or accounting model.

The accepted submitted basis is preserved with the operation. An actual new payment uses its new
applicable rate; correcting a historical entry uses the appropriate historical basis. Bank and order
must describe the same fee once rather than charging it twice.

## Consequences

- Both domains depend downward on an existing configuration-owning module, without importing Bank
  internals, changing ranks or creating another policy owner.
- Rate resolution/validation and whole-aUEC fee calculation have one implementation and contract;
  the two domains retain their different custody and cost-payer rules.
- New acceptance evidence covers rate changes while pending, replay, historical correction,
  KRT-funded reconciliation and genuine no-transfer exemption. No implementation is asserted here.

## Alternatives considered

- **Duplicate rate parsing/calculation in JobOrder:** allows the same payment to acquire different
  fees and makes snapshot replay depend on which consumer runs it.
- **Expose bank.internal or make JobOrder depend on Bank:** crosses the module ownership/rank boundary.
- **Put Bank/order fee policy in kernel/platform:** makes the domain-free shared layers own a
  financial rule and runtime setting they do not govern.
- **Add a dedicated monetary-policy module:** is viable but adds declarations and a module surface
  where the existing configuration owner can supply this narrow contract.
