# ADR-0359 — Orders record money operations and replay their outcomes

- **Status:** Accepted — implementation pending
- **Date:** 2026-11-09
- **Deciders:** @greluc
- **Related:** [aUEC orders](../specs/orders-auec.md), REQ-ORDERS-057…068; [edit modules](../specs/aggregate-edit-modules.md), REQ-SEC-085 / REQ-ORDERS-046; [ADR-0287](0287-mission-and-job-order-each-edit-through-one-edit-module.md) (amended for scoped order edits); [ADR-0232](0232-modules-interact-through-commands-observers-and-after-commit-events.md); [technical decision](https://github.com/krt-profit/basetool/issues/3202#issuecomment-6741369845)

## Context

Manual order money must remain explainable through corrections, holder changes, retries and
optional Bank decisions. Independently edited balances plus an incidental activity log could
disagree. One parent order version would also make unrelated holder and note changes conflict.
A Bank request lock protects one request, not one real payment across replacement attempts.

The owner chose authoritative monetary operations and rebuildable sums, durable submitted
operation recovery, and narrow edit boundaries. The model does not require a new user
bookkeeping process or application-wide event sourcing.

## Decision

The effective monetary operation history, including linked corrections, determines the order's
financial projections. Stored totals can accelerate reads but cannot become a second editable
authority. Preserve identity and lineage through holder linking and corrections, subject to the
existing privacy contracts; admin audit remains a separate trail with its own retention.

A persistent logical operation has submitted revisions/attempts and a recorded outcome.
Identical authorised replay returns that outcome without another effect, including when the
original client versions are now stale. Incompatible terms under the same submitted identity
conflict. A deliberate correction or replacement preserves lineage; a genuinely new payment is
another operation. An authorised outcome lookup supports unknown browser results and reload.

Keep independent client versions for the data actually edited or relied upon. Protect related
holder effects and target/net-handover/lifecycle derivation through narrow internal guards,
without using a screen-wide client version. Financial legs, explicit provision, commitment
credit, status consequences, history and audit commit together. Cross-order changes take the
whole affected set in deterministic order and check both orders' authority and eligibility.

Extend the existing order edit module to own scoped authorisation/version/audit/projection
contracts, rather than bypassing it. Existing MANDATORY/WithinTransaction and stock-lock rules
remain binding. The optional Bank adapter has its own [ADR-0357](0357-optional-auec-bank-effects-use-a-thin-composition-adapter.md);
material capacity has [ADR-0360](0360-material-promises-track-uncovered-demand-and-explicit-provision.md).

## Consequences

The required complete money history can explain and reproduce displayed balances. Browser
response loss does not ask for another game payment. Independent edits remain independent,
while genuine shared financial invariants serialize internally.

The implementation must persist operation outcomes and enforce payload identity, active-attempt
exclusion, deterministic locking and atomic rollback. It must test real PostgreSQL races and
all permission paths; button disabling, HTTP correlation or an idempotent callback alone are
insufficient. Privacy registries must cover new identities/history. No successful runtime
evidence is claimed by accepting this ADR.

## Alternatives considered

- **Mutable balances as the independent authority:** rejected because the required history and
  balance projections could diverge after corrections or a partial write.
- **One order version for all screen edits:** rejected because unrelated holder and note changes
  would collide.
- **Request-level idempotency or browser-only suppression:** rejected because it does not cover
  response loss, replacement Bank attempts or duplicate effective financial legs.
- **Application-wide event sourcing or asynchronous financial compensation:** rejected because
  the scoped model and shared database transaction already provide the required boundary.
