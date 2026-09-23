# ADR-0002 — Whole-number amounts: value-based validation, reject-not-round, display-only rounding

- **Status:** Accepted — the "no shared code module" clause under *Consequences* is superseded
  for log hygiene by [ADR-0205](0205-log-hygiene-lives-in-one-shipped-logging-support-module.md)
  (2026-09-23)
- **Date:** 2026-06-06
- **Deciders:** Repository owner (@greluc)
- **Related:** spec `REQ-MISSION-001`, `REQ-ORDERS-001` / `002`, `REQ-INV-045`
  ([`whole-number-amounts.md`](../specs/whole-number-amounts.md)) · contrasts with
  `REQ-INV-003`, `REQ-INV-042`, `REQ-INV-043` ([`inv-material-quantities.md`](../specs/inv-material-quantities.md)) · PR #465

## Context

Several amounts are conceptually **whole numbers** — mission-finance money (aUEC), item-order piece
counts, item-handover counts, personal-inventory quantity — but were entered through generic numeric
inputs that allowed fractional values (mission finance used `<input type="number" step="0.01">` over
a `BigDecimal` / `NUMERIC(19,4)` column).

A sibling decision already governs squadron **material** quantities
([`inv-material-quantities.md`](../specs/inv-material-quantities.md)): **SCU** amounts are fractional
(≤ 3 dp) and excess precision is **rounded HALF_UP at persistence**; **PIECE** amounts are whole,
enforced by a **value-based** check. The non-material amounts above needed their own deliberate rule,
with one twist the owner stated explicitly for finance: *users enter and see whole aUEC, but the
system may keep fractional precision internally* — a mission's bottom line folds in refinery-order
profit (`oreSales − expenses − otherExpenses`), which is fractional.

Two design forces collide:

1. **"Whole" can be checked by scale or by value.** `@Digits(fraction = 0)` inspects the
   `BigDecimal` scale and rejects the mathematically-whole `500.00`; a value check
   (`remainder(1) == 0`) accepts it. An existing MockMvc test posts exactly `"amount":500.00` and
   expects success, and JSON clients legitimately send trailing zeros.
2. **A stray fractional amount can be rejected or silently rounded.** Rounding a user-entered finance
   amount (as the SCU path does) would mutate the input and discard precision; the owner asked to
   *keep* internal precision.

## Decision

We will treat these amounts as **whole at the edges, precise in the middle**.

- **Validation is value-based, not scale-based.** A custom `@WholeNumber` constraint (one copy per
  module — `backend` and `frontend` share no code) passes when `value.remainder(BigDecimal.ONE)` is
  zero, so `500`, `500.00` and `500.0000` are accepted and only genuinely fractional values (`500.5`)
  are rejected. We do **not** use `@Digits(fraction = 0)`. This matches the value-based PIECE rule.
- **Mission-finance: reject fractional input, never round it.** `@WholeNumber` on the create/update
  DTOs and the form returns 400 for a fractional amount rather than rounding it; the stored
  `BigDecimal` is persisted exactly. The `≥ 0` lower bound (`@DecimalMin("0.0")`) stays — finance is
  whole, **not** strictly positive, so `0` is valid.
- **Round only for display.** Every user-facing finance figure is rounded HALF_UP to whole aUEC by
  the `MoneyFormat` Thymeleaf bean (Thymeleaf's `#numbers` would otherwise round HALF_EVEN). Derived
  totals keep full precision in storage and computation and are rounded only when rendered. The
  finance input is `type="number" step="1"` and the edit modal pre-fills the rounded value.
- **Counts stay `Integer @Min(1)`.** Item-order, item-handover and personal-inventory quantities are
  positive whole numbers by type; the HTML is aligned (`step="1"`, `min="1"`).

This deliberately differs from the SCU decision (round excess precision at persistence): SCU amounts
are *inherently* fractional and sub-microSCU digits are a UI artefact worth absorbing, whereas a
finance amount is a user-entered whole value where a fractional input is a mistake worth surfacing —
and rounding it would contradict the "keep internal precision" requirement.

## Consequences

- **Easier:** one consistent, value-based "is it whole" rule; whole values survive any JSON
  representation (`500.00`), so no spurious 400s and no existing-test churn; stored finance values are
  never silently mutated, so derived-total precision is preserved.
- **Harder / accepted costs:**
  - `@WholeNumber` is duplicated across the two modules (no shared code module) — a small, deliberate
    copy kept in sync by identical unit tests.
    *Amended 2026-09-23:* a shared module now exists — `logging-support`, for log hygiene only
    ([ADR-0205](0205-log-hygiene-lives-in-one-shipped-logging-support-module.md)). Its scope is
    closed to domain meaning, so `@WholeNumber` stays per module; moving it would be its own
    decision.
  - A non-browser client that sends a *genuinely* fractional finance amount gets a 400 rather than a
    rounded value. This is intentional (input is whole) and contrasts with the SCU path; the
    divergence is recorded here so it is not mistaken for an inconsistency.
  - Display rounding is presentation-only: the sum of displayed whole entries may differ by ≤ 1 aUEC
    from a separately-rounded total (already true, since refinery profit feeds the total but renders
    as its own row). Acceptable for a credits figure.

## Alternatives considered

- **`@Digits(fraction = 0)` (scale-based).** Rejected: rejects the mathematically-whole `500.00`,
  breaks the existing security MockMvc test, and would 400 any client that sends trailing zeros.
- **Round fractional finance input at persistence (mirror SCU).** Rejected: silently mutates a
  user-entered amount and discards precision, contradicting the "keep internal precision"
  requirement; rejecting is more honest for a value that should have been whole.
- **Migrate finance to a `Long` / integer column.** Rejected: would force the fractional derived
  totals (refinery profit) to lose precision or live elsewhere, and is a heavier DB migration for no
  user-visible gain over "whole at the edges, precise in the middle".
- **No frontend constraint (rely on HTML `step="1"` + backend).** Rejected: the form already
  validates its other fields; an inline field error beats a backend round-trip, and the client/server
  mirror keeps the rule identical on both sides.
