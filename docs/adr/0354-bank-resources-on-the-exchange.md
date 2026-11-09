# ADR-0354 — Bank resources on the exchange

- **Status:** Accepted
- **Date:** 2026-11-08
- **Deciders:** Repository owner (@greluc)
- **Related:** amends [ADR-0216](0216-the-exchange-api-is-a-separate-contract-on-the-ingest-gateway.md)
  (the exchange's resource allow-list) · spec [`bank-exchange.md`](../specs/bank-exchange.md)
  `REQ-BXCH-*` · [`external-exchange.md`](../specs/external-exchange.md) · [`bank.md`](../specs/bank.md) ·
  ADR-0153 (client attribution on bank audit events) · ADR-0352 (one explicit bank access policy) ·
  decided in [#3113](https://github.com/krt-profit/basetool/issues/3113),
  [#3121](https://github.com/krt-profit/basetool/issues/3121) and
  [#3123](https://github.com/krt-profit/basetool/issues/3123)

## Context

ADR-0216 made the exchange a separate contract with its own resources: blueprints, stock, ships,
org demand, org units, org stock and drafts. The Kartellbank was not among them, so a connected
application such as VerseKit could show neither a member's accounts nor their booking requests.

The bank holds the most sensitive data of the Basetool: balances, booking history and the
bank-staff queue, which is a map of who wants to move how much money from which account. A client
on a member's device is a new place for that data to leak from, and a new way to raise requests in
bulk.

## Decision

1. **The bank joins the exchange's allow-list as its own route family**, `/exchange/v1/bank/**` on the
   gateway and `/api/v1/exchange/bank/**` on the backend. The rest of ADR-0216 holds unchanged:
   own DTOs, capabilities as the unit of approval, routes listed one by one.
2. **Three capabilities.** Reading the member's own bank data, raising and cancelling the member's
   own booking requests, and reading the bank-staff queue. The queue has a scope of its own with its
   own consent line, so a staff member's ordinary login carries no staff view.
3. **No money moves through a client, and no bank-staff action.** A client may raise, correct and
   cancel the member's own requests; confirming, rejecting, booking and every grant change stay in
   the Basetool's web interface. The bank access policy (ADR-0352) is the only decision point.
4. **A bank switch beside the global switch**, `exchange_settings.bank_enabled` with its own version
   counter, flipped by admins only and **off by default**. While it is off, every path of the bank
   family answers `503 BANK_EXCHANGE_DISABLED` with `Retry-After: 300`, whether a route serves it or
   not; the global switch still wins with `503 EXCHANGE_DISABLED`. The switch is mirrored to the
   gateway like the global one and re-checked by the backend's exchange gate.
5. **No journal and no undo for bank requests.** A request is a proposal the bank staff decide on;
   the member cancels it through the client or the web, and the bank's own audit trail (with the
   client on `bank_audit_event.client_id`, ADR-0153) records it.

## Consequences

- The bank can be shut off on the exchange in one step without stopping a client's other areas; a
  client keeps syncing the rest and retries the bank later.
- A bank route slice only adds its route under the bank prefix; the switch covers it without a change
  to the gate.
- The registry mirror document carries a second switch, `bankEnabled`. A document without it reads
  as off, so the gateway fails closed until the backend has written the new format.
- Monitoring gains the bank counters and alerts (REQ-OBS-024); the open-request cap of 20 pending
  requests per member brakes request spam on top of the daily write quota.
- Exchange code reaches the bank only through the policy, `BankActors` and the bank's published
  reads with their value types; an architecture rule fails the build otherwise (REQ-BXCH-001).
- A relayed path may carry a whole `{id}` segment, as the account reads do; the frozen seam still
  lists every path one by one, never a wildcard.
