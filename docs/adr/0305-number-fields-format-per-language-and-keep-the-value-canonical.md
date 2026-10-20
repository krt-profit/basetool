# ADR-0305 — Number fields format per language and keep the value canonical

- **Status:** Accepted
- **Date:** 2026-10-20
- **Deciders:** @greluc
- **Related:** REQ-UI-037 · REQ-INV-042 · REQ-INV-043 · REQ-BANK-065 · REQ-BANK-033 ·
  REQ-NOTIF-031 · [`ui-design-system.md`](../specs/ui-design-system.md) ·
  [`inv-material-quantities.md`](../specs/inv-material-quantities.md) ·
  [ADR-0052](0052-bank-transfer-fee-borne-by-debited-account.md)

## Context

The pages show amounts with thousands separators, but the fields that take numbers were
`<input type="number">`, which cannot show a separator, so a member typing seven zeros had nothing
to read them by. The SCU fields were text fields that took `.` or `,` as the decimal separator
regardless of language. Notifications printed the stored plain value (`3000000`). The tool runs in
German and English, and each language writes numbers its own way: `1.999.999,0` and `1,999,999.0`.

A field that shows `1.500.000` holds that text as its browser value. The scripts behind the fields
read it with `Number(el.value)` or `parseFloat(el.value)`, and the bank form serializer turns every
`amount`, `target` and `limit` into a JSON number the same way. `Number("1.500.000")` is `NaN`;
`parseFloat("1.500.000")` is `1`. Formatting the visible value alone would make a deposit of one
and a half million arrive as a deposit of one aUEC, without an error.

Members also asked to see, at a deposit, what they have to send so that the requested amount
arrives. The in-game fee rate that answers it was readable by bank staff only.

## Decision

1. **Every number field is `<input type="text" data-number-input>`**, formatted live by one kernel
   module (`krt-number-input.js`) with the pure rules in `krt-number-format.js`. No template or
   script renders `type="number"`.
2. **The separators are the page language's**, read from `<html lang>` through
   `Intl.NumberFormat`. Only the language's decimal separator starts the fraction; its group
   separator is dropped wherever it is typed.
3. **The field describes its own rules**: the decimals from `step` (`any` is three, so an SCU amount
   always takes three), a minus sign only when `min` is absent or negative. Typing stops at the
   decimals; a pasted or assigned value with more is rounded half up (REQ-INV-042).
4. **The field's `value` stays canonical.** The module redefines `value` and `valueAsNumber` on
   each enhanced element: the getter returns `1500000`, the setter formats what it is given. A
   `formdata` listener replaces the field's entries with the canonical value, which covers
   `new FormData(form)` and a native submit alike. No page script, proxy or controller learns a
   localized format, and an element the module has not enhanced yet still holds a canonical value.
5. **Validation moves into the module**: a whole `step`, `min` and `max` on the canonical value,
   positivity for SCU and piece fields (`data-scu-decimal`); `required` stays native.
   `scu-decimal-input.js` keeps only its parse and round helpers.
6. **Notifications group their amount parameters at render time** in the member's language; the
   backend keeps storing locale-free values.
7. **The transfer-fee rate is readable by every authenticated caller.** It is one org-wide
   percentage with no account data, and the member-facing request dialog needs it for the
   „Mit Gebühr" line, which uses the page language's separators too.

## Consequences

- A new number field is a text input with `data-number-input` and a `step`; it needs no script of
  its own and its readers keep using `el.value`. A script that builds one and sets its value calls
  `krtNumberInput.enhance` first.
- Code that reads the browser's raw text past the element (`HTMLInputElement.prototype` value
  getter, a Playwright `inputValue()`) sees the localized text; attribute reads (`value="…"`) are
  unchanged.
- A script must not write a localized string into a number field; it assigns the number or its
  canonical string.
- `GET /api/v1/bank/transfer-fee-rate` is open to members on the internal API.
- The amounts the pages print through `#numbers.formatInteger(…, 'POINT')` keep their fixed point
  outside the deposit fee cells; aligning those displays with the page language is separate work.

## Alternatives considered

- **A hint below the field (`= 1.500.000 aUEC`) and a plain number input.** No risk to readers,
  but the member still types blind into the field itself, which is what was asked to change.
- **A hidden input carrying the name and the canonical value.** Every script that finds the field
  by id would read the visible one and need changing; the instance property keeps them as they are.
- **One fixed convention for both languages.** Simpler, but an English page showing `1.500.000`
  reads as one and a half to its members.
- **Formatting on blur only.** Simpler caret handling, but the separators would not help while
  typing, which is when a digit gets lost.
