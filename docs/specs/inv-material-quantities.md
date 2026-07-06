> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-06-06.
> **Owner area:** INV · **Related ADRs:** none

# Material quantities (SCU / PIECE)

## Context & goal

Every material is measured in one of two **quantity types**: `SCU` (fractional Standard Cargo
Units) or `PIECE` (whole units). The same input rules and storage precision must hold for a
material amount no matter where it is entered — inventory book-in / book-out, material-order
create + edit, material claim, handover, refinery store-to-inventory — so an amount means the same
thing across the app and is never stored with spurious precision. This spec is the single home for
those rules; the **input** (client) and **enforcement/storage** (server) facets are split into
separate requirements but govern the same field. Related: the input fields are styled per the UI
spec ([`ui-design-system.md`](ui-design-system.md)); amounts are persisted per
[REQ-DATA-001](data-persistence.md).

## Requirements

### REQ-INV-001 — SCU amounts: positive, ≤ 3 decimals, either separator

A material whose quantity type is `SCU` is measured in **0.001** increments (microSCU). Every field
that takes an amount of an SCU material accepts a **positive (> 0) decimal** with **at most three
decimal places** (input `step` = `0.001`). The decimal separator may be typed as **either `.` or
`,`** regardless of browser locale and is **normalised internally** to a canonical dot value before
it leaves the field — a native `<input type="number">` only accepts the browser-locale separator,
which is why these fields are `type="text" inputmode="decimal" data-scu-decimal`. A value typed with
**more than three** decimals is **rounded to three using commercial rounding (round half up)** when
the field is committed (on blur) and again before submit. The amount must be **> 0**; the one
exception is the book-out **target stock**, which may be `0` (= "remove all") and opts out via
`data-scu-allow-zero`.

An SCU field may carry an inline decimal-scale **hint** — the focusable `scu-hint` marker whose
tooltip explains the cSCU / microSCU scale. The hint is shown **only** on SCU-typed rows, never on
PIECE rows. Because Thymeleaf resolves `th:replace` / `th:insert` (attribute precedence 1) **before**
`th:if` (3), the condition that gates the hint must sit on an **outer** element (a wrapping
`<th:block>`) and never share the element with `th:replace`; a shared-element combination renders the
hint unconditionally — the refinery store dialog previously showed it on PIECE rows.

**Acceptance**

- [ ] Both `0,01` and `0.01` are accepted and the form submits `0.01`.
- [ ] An amount with > 3 decimals is rounded half up to 3 (`0.0015` → `0.002`, `1.2345` → `1.235`, `12.9995` → `13`).
- [ ] An amount that is `0`, negative, or rounds to `0` (e.g. `0.0004`) is rejected before submit; the book-out target stock may be `0`.
- [ ] The field carries `step="0.001"` and is `type="text" inputmode="decimal" data-scu-decimal`.
- [ ] The SCU decimal-scale hint (`scu-hint`) renders only on SCU-typed rows — the refinery store dialog shows it for an SCU output material and omits it for a PIECE one.

**Enforced by:** _Client_ — `scu-decimal-input.js` (mode read from the live `step` attribute) +
`InventoryPageControllerMvcTest` (render-wiring) + `RefineryStoreScuHintRenderTest` (the SCU hint is
gated to SCU rows only) + web-asset linting (ESLint / HTMLHint / Prettier). _Server_ — see
[REQ-INV-003](#req-inv-003--server-side-enforcement--scu-scale-storage). **Code:**
`frontend/.../static/js/scu-decimal-input.js`, `fragments/head.html` (`window.krtScuI18n`),
`fragments/scu-hint.html`, `refinery-orders-details.html`. **Issues:** PR #465.

### REQ-INV-002 — PIECE amounts: positive whole numbers only

A material whose quantity type is `PIECE` (Stück) is counted in whole units. The same fields, when
the chosen material is PIECE-typed, accept **only positive whole numbers** (≥ 1): decimal separators
are **not** accepted (they are stripped as the user types) and fractional or non-positive values are
rejected before submit. Integer mode is signalled by `step="1"`, which the quantity-type-aware page
scripts set when a PIECE material is selected.

**Acceptance**

- [ ] Only digits can be entered; a typed `.` or `,` is dropped.
- [ ] A value `< 1` (including `0`) is rejected before submit.

**Enforced by:** _Client_ — `scu-decimal-input.js` (integer mode) + web-asset linting. _Server_ —
see [REQ-INV-003](#req-inv-003--server-side-enforcement--scu-scale-storage). **Code:**
`frontend/.../static/js/scu-decimal-input.js`. **Issues:** PR #465.

### REQ-INV-003 — Server-side enforcement & SCU-scale storage

REQ-INV-001 / REQ-INV-002 are enforced server-side as well, so non-browser API clients cannot bypass
them. **Validation:** `@ValidQuantityAmount` rejects `≤ 0` (both types) and fractional `PIECE`
amounts on the validated write DTOs — inventory create/update, refinery store, and **job-order
material (create + the same-shape edit)**; the book-out / handover / claim services apply the same
`> 0` + PIECE-integer checks inline. **Storage:** SCU excess precision is **rounded, not rejected** —
every persisted material amount is normalised to three decimals with `RoundingMode.HALF_UP` at the
persistence boundary via a `@PrePersist`/`@PreUpdate` hook on each amount-bearing entity
(`InventoryItem`, `JobOrderMaterial`, `MaterialClaim`, `JobOrderHandoverItem`), so the rule holds for
operator input, `double` arithmetic on book-out / transfer / handover decrements, and summed refinery
yields (which can land on a binary value like `37.160000000000004`). Rounding is unconditional —
`PIECE` amounts are whole, so it is a no-op for them. This mirrors the frontend (round, don't reject)
rather than refusing over-precise input.

**Acceptance**

- [ ] An amount with more than three decimals is stored rounded HALF_UP to three (`0.0015` → `0.002`, `1.2345` → `1.235`).
- [ ] No write path stores a material amount with more than three decimals.
- [ ] A `0` or negative material amount is rejected on the guarded create/update endpoints (incl. job-order create + edit, which previously accepted `0`).

**Enforced by:** `ValidQuantityAmountValidatorTest`, `MaterialAmountRoundingTest`. **Code:**
`backend/.../validation/ValidQuantityAmount*`,
`backend/.../model/{InventoryItem,JobOrderMaterial,MaterialClaim,JobOrderHandoverItem}` (`roundAmountToScuScale`).
**Issues:** PR #465.

## Out of scope

Whole-unit counts and currency that are **not** material quantities — item-order piece counts,
item-handover entries, personal-inventory quantity (all `Integer`, `≥ 1`) and mission-finance money
amounts (whole aUEC) — are specified in [`whole-number-amounts.md`](whole-number-amounts.md)
(`REQ-MISSION-001`, `REQ-ORDERS-001` / `002`, `REQ-INV-004`). Refinery good input/output quantities
keep their own validation. The DB-precision / migration mechanics live in
[`data-persistence.md`](data-persistence.md).

## Open questions

- Should the DB columns carry an explicit `NUMERIC(p, 3)` scale as a belt-and-suspenders guard behind
  the application-layer rounding, or is the `@PrePersist`/`@PreUpdate` chokepoint sufficient?

