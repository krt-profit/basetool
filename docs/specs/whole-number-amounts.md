> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-09-22.
> **Owner area:** MISSION / ORDERS / INV · **Related ADRs:**
> [ADR-0002](../adr/0002-whole-number-amounts.md)

# Whole-number amounts (currency & counts)

## Context & goal

Several amounts in the app are conceptually **whole numbers** — money in aUEC, counts of game items —
yet are entered through generic numeric inputs that historically allowed fractional values. This
spec is the single home for the rule that *these* amounts are entered, stored, and shown as whole
numbers. It is the non-material companion to [`inv-material-quantities.md`](inv-material-quantities.md)
(which owns the fractional **SCU** / whole **PIECE** rules for squadron *material* quantities); the
amounts here are deliberately **out of scope** of that spec.

Item-order counts, item-handover counts, and personal-inventory quantity were already enforced as
positive whole `Integer`s at every layer (typed field + client filter + `@Min(1)`); PR #465 formalises
those invariants as requirements and adds the new rule for **mission finance** — whole aUEC on input
and display, with fractional precision retained only for system-derived totals.

The Kartell bank applies the same contract to every booking amount (`@WholeNumber` +
`@DecimalMin("1")`, HALF_UP display); that rule is canonical in
[`bank.md`](bank.md#req-bank-005--whole-auec-amounts) (REQ-BANK-005) and is not restated here.

## Requirements

### REQ-MISSION-001 — Mission finance amounts: whole aUEC in, whole aUEC out

A mission **finance entry** (income / expense) is a money amount in aUEC. It is **entered by the user
only as a whole number** and is **always displayed as a whole number, commercially rounded
(`RoundingMode.HALF_UP`)**. The system may keep fractional precision **internally** for *derived*
figures — a mission's bottom line folds in refinery-order profit (`oreSales − expenses −
otherExpenses`), which is fractional — but it never stores a fractional **operator-entered** finance
amount.

- **Input** — the add and edit finance modals use `<input type="number" step="1" inputmode="numeric">`
  (no `step="0.01"`); the edit modal pre-fills the HALF_UP-rounded whole value, never the raw stored
  decimal. That pre-filled `data-amount` must bind the rounded value through `th:with` and read only
  the resulting variable — Thymeleaf 3.1 evaluates default/unknown attributes (`th:data-*`) in a
  **restricted** expression context that forbids `@bean` references, so calling `@moneyFormat.round(…)`
  directly inside `th:data-amount` throws a `TemplateProcessingException` and 500s the whole
  mission-detail page for any mission that owns at least one finance entry. Server-side, a
  **value-based** `@WholeNumber` constraint on the create **and** update DTOs
  (mirrored on the frontend form) rejects a genuinely fractional amount (`500.5`) while accepting any
  whole value regardless of representation (`500`, `500.00`) — so a non-browser client cannot bypass
  the rule, yet a whole amount sent with trailing zeros is not spuriously refused. This mirrors the
  project's value-based PIECE rule rather than the scale-based `@Digits`. The existing `≥ 0` lower
  bound (`@DecimalMin("0.0")`) is kept — finance amounts are whole, **not** strictly positive, so a
  `0` entry is valid — and the backend DTOs cap the amount at 1 000 000 000 aUEC
  (`@DecimalMax("1000000000.0")`) so a `1e100` cannot reach the entity. The stored value is never
  rounded server-side: a whole amount is persisted
  exactly, keeping internal precision for the derived totals it feeds.
- **Display** — every user-facing finance figure (per-entry amount, mission total, operation-wide
  total, per-mission roll-ups, the refinery contribution line) is rounded HALF_UP to whole aUEC via
  the `MoneyFormat` Thymeleaf bean. This bean exists because `#numbers.formatInteger` would otherwise
  apply `DecimalFormat`'s default **HALF_EVEN** (banker's) rounding, which is not the commercial
  rounding users expect.

**Acceptance**

- [ ] The finance amount field is `type="number" step="1"`; a fractional value is rejected before submit and by the API.
- [ ] A fractional amount sent to the create/update endpoint is rejected (`@WholeNumber`); a whole amount (incl. `500.00`) and `0` are accepted; a negative amount is rejected.
- [ ] Every displayed finance amount/total is a whole number rounded HALF_UP (`0.5 → 1`, `2.5 → 3`).
- [ ] A mission total that folds in fractional refinery profit keeps full precision internally and is rounded only at display.
- [ ] The edit-finance button's `data-amount` pre-fill renders without a Thymeleaf restricted-context error when a mission owns ≥ 1 finance entry (regression: the rounded value is bound via `th:with`, not by calling `@moneyFormat` inside `th:data-*`).

**Enforced by:** `MissionFinanceEntryValidationTest`, `MissionFinanceEntryFormValidationTest`,
`MoneyFormatTest`,
`MissionPageControllerMvcTest#missionDetail_WithFinanceEntry_ShouldRenderEditButtonWithoutTemplateError`,
`MissionFinanceEntryE2eTest` (e2e create→view: add a finance entry, reopen the detail page, assert
200 + the rendered `data-amount`). **Code:** `backend/.../model/dto/MissionFinanceEntry{Create,Update}Dto`
(`@WholeNumber`, `@DecimalMin`, `@DecimalMax`), `frontend/.../model/form/MissionFinanceEntryForm`
(`@WholeNumber`), `backend/.../validation/WholeNumber` + `frontend/.../validation/WholeNumber`,
`frontend/.../view/MoneyFormat`, `templates/mission-detail.html`, `templates/operation-detail.html`.
**Issues:** PR #465.

### REQ-ORDERS-001 — Item-order piece counts: positive whole numbers

An **item-order** line orders a count of whole game items. The line amount is a **positive whole
number** (`Integer`, `≥ 1`): fractional or non-positive input is rejected. The create and edit forms
use `<input type="number" step="1" min="1">`; the write controller drops any line whose amount is
blank or `≤ 0` before calling the backend; `CreateJobOrderItemLineDto.amount` is
`@NotNull @Min(1) Integer`.

**Acceptance**

- [ ] An item-order line amount is a whole number `≥ 1`; fractional or `≤ 0` input never reaches a persisted line (HTML `step`/`min` + the controller's `> 0` filter + backend `@Min(1)`).

**Enforced by:** `JobOrderItemServiceTest`, `JobOrderPageControllerItemEditMvcTest`. **Code:**
`backend/.../model/dto/CreateJobOrderItemLineDto`, `backend/.../model/JobOrderItem` (`amount` is
`Integer`), `templates/orders-create.html`, `JobOrderWriteController` (line filter). **Issues:** PR #465.

### REQ-ORDERS-002 — Item-handover counts: positive whole numbers

A handover line for an item order delivers a **positive whole number** of items (`Integer`, `≥ 1`),
capped at the line's outstanding amount. The handover modal uses
`<input type="number" step="1" min="1" th:max="${outstanding}">`; the write controller filters out
blank / `≤ 0` entries and rejects an all-empty handover with a toast;
`JobOrderItemHandoverEntryCreateDto.amount` is `@NotNull @Min(1) Integer`; the service rejects
over-delivery beyond the outstanding amount.

**Acceptance**

- [ ] An item-handover entry amount is a whole number `≥ 1` and `≤ outstanding`; blank / `0` rows are dropped and an all-empty handover is rejected.

**Enforced by:** `JobOrderItemHandoverServiceTest`. **Code:**
`backend/.../model/dto/JobOrderItemHandoverEntryCreateDto`,
`backend/.../model/JobOrderItemHandoverEntry` (`amount` is `Integer`),
`templates/orders-detail.html`, `JobOrderWriteController#createItemHandover`. **Issues:** PR #465.

### REQ-INV-004 — Personal-inventory quantity: positive whole numbers

A **personal-inventory** item (a member's own item stash) is counted in whole units. Its quantity is
a **positive whole number** (`Integer`, `≥ 1`). The user and admin forms use
`<input type="number" min="1" step="1" pattern="[0-9]*" inputmode="numeric">` plus a JS sanitiser that
strips non-digits and floors the value at `1`; `PersonalInventoryItem{Create,Update}Request.quantity`
is `@NotNull @Min(1) Integer` and the frontend form mirrors it.

This requirement is numbered in the `INV` area but lives here, not in
[`inv-material-quantities.md`](inv-material-quantities.md): `REQ-INV-001..003` cover **material**
(SCU/PIECE) amounts, whereas personal inventory is a distinct feature whose quantity follows the
non-material whole-number rule shared with the order counts above.

**Acceptance**

- [ ] Personal-inventory quantity accepts only positive whole numbers; `0`, negative, or fractional input is rejected at the form and the API.

**Enforced by:** `PersonalInventoryItemRequestValidationTest` (`quantityBelowOneShouldBeRejected`).
**Code:** `backend/.../model/dto/PersonalInventoryItem{Create,Update}Request`,
`backend/.../model/PersonalInventoryItem` (`quantity` is `Integer`),
`static/js/personal-inventory.js`, `templates/personal-inventory.html`,
`templates/admin/personal-inventory.html`, `frontend/.../model/form/PersonalInventoryForm`.
**Issues:** PR #465.

## Out of scope

The fractional **SCU** / whole **PIECE** rules for squadron *material* quantities (book-in / book-out,
material order, claim, material handover, refinery store) — those are
[`inv-material-quantities.md`](inv-material-quantities.md) (`REQ-INV-001..003`). Refinery good
input/output quantities keep their own validation. Currency display formatting (grouping separators,
the `aUEC` suffix, department colours) is governed by [`ui-design-system.md`](ui-design-system.md).
