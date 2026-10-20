# ADR-0305 — Amount fields format the display and keep the value canonical

- **Status:** Accepted
- **Date:** 2026-10-20
- **Deciders:** @greluc
- **Related:** REQ-UI-037 · REQ-BANK-065 · REQ-BANK-033 · REQ-NOTIF-031 ·
  [`ui-design-system.md`](../specs/ui-design-system.md) · [`bank.md`](../specs/bank.md) ·
  [ADR-0052](0052-bank-transfer-fee-borne-by-debited-account.md)

## Context

The pages show aUEC amounts with thousands separators (`3.000.000 aUEC`), but the fields that take
them were `<input type="number">`, which cannot show a separator, so a member typing seven zeros
had nothing to read them by. Notifications printed the stored plain value (`3000000`).

A field that shows `1.500.000` holds that text as its browser value. The scripts behind the fields
read it with `Number(el.value)` or `parseFloat(el.value)`, and the bank form serializer turns every
`amount`, `target` and `limit` into a JSON number the same way. `Number("1.500.000")` is `NaN`;
`parseFloat("1.500.000")` is `1`. Formatting the visible value alone would make a deposit of one
and a half million arrive as a deposit of one aUEC, without an error.

Members also asked to see, at a deposit, what they have to send so that the requested amount
arrives. The in-game fee rate that answers it was readable by bank staff only.

## Decision

1. **Every aUEC field is `<input type="text" data-auec-input>`**, formatted live by one kernel
   module (`krt-auec-input.js`) with the pure rules in `krt-auec-format.js`: a point between
   thousands, a comma before the fraction, the caret kept beside its digit, Backspace or Delete on a
   separator removing the neighbouring digit.
2. **The field's `value` stays canonical.** The module redefines `value` and `valueAsNumber` on
   each enhanced element: the getter returns `1500000`, the setter formats what it is given. A
   `formdata` listener replaces the field's entries with the canonical value, which covers
   `new FormData(form)` and a native submit alike. No page script, proxy or controller learns a new
   format, and an element the module has not enhanced yet still holds a canonical value.
3. **`min`, `max` and a whole `step` are validated by the module** on the canonical value, because
   a text field has no native range check; `required` stays native.
4. **Notifications group their amount parameters at render time** in the frontend, by parameter
   name; the backend keeps storing locale-free values.
5. **The transfer-fee rate is readable by every authenticated caller.** It is one org-wide
   percentage with no account data, and the member-facing request dialog needs it for the
   „Mit Gebühr" line.

## Consequences

- A new aUEC field is a text input with `data-auec-input` and a `step`; it needs no script of its
  own and its readers keep using `el.value`.
- Code that reads the browser's raw text past the element (`HTMLInputElement.prototype` value
  getter, a Playwright `inputValue()`) sees the grouped text; attribute reads (`value="…"`) are
  unchanged.
- `GET /api/v1/bank/transfer-fee-rate` is open to members on the internal API; it was already
  reachable by bank staff through the same route.
- Material quantities keep their own field rules (REQ-INV-042/-043); unifying them is not part of
  this decision.

## Alternatives considered

- **A hint below the field (`= 1.500.000 aUEC`) and a plain number input.** No risk to readers,
  but the member still types blind into the field itself, which is what was asked to change.
- **A hidden input carrying the name and the canonical value.** Every script that finds the field
  by id would read the visible one and need changing; the instance property keeps them as they are.
- **Formatting on blur only.** Simpler caret handling, but the separators would not help while
  typing, which is when a digit gets lost.
