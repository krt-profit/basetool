> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-06-29.
> **Owner area:** UI · **Related ADRs:** none yet · **Visual source of truth:** the design
> skill [`.claude/skills/das-kartell-design/README.md`](../../.claude/skills/das-kartell-design/README.md)
> (+ [`colors_and_type.css`](../../.claude/skills/das-kartell-design/colors_and_type.css)).

# UI & design system

## Context & goal

The Profit Basetool is the squadron-management web app of the "DAS KARTELL" / IRIDIUM
org. Its UI must read unmistakably as that brand: a dark sci-fi HUD, the orange house
colour, and the official corporate-design tokens — applied consistently across every
screen and all four device classes. The design skill is the **visual** source of truth;
this file is the **written, binding** contract that ships with the repo. Where the two
disagree, the skill wins and this file is corrected in the same PR.

> New UI/visual decisions are recorded in an ADR and reflected here and in the design
> skill — see the governance rules in `CLAUDE.md`.

## Requirements

### REQ-UI-001 — The DAS KARTELL design system is binding

Every UI change follows the design system. Do not invent colours, fonts, spacing, or
component shapes; reach for the published tokens in
[`colors_and_type.css`](../../.claude/skills/das-kartell-design/colors_and_type.css) and the
components in `krt-components.css`.

**Acceptance**

- [ ] No hard-coded colour/font/spacing values that duplicate an existing token.
- [ ] New components reuse the skill's component CSS rather than re-styling from scratch.

**Enforced by:** design review + web-asset linting (Stylelint / ESLint / HTMLHint).

### REQ-UI-002 — Brand colour & logo

The primary brand colour is **`#E77E23`** (orange). The logo appears **only** in this
orange, white, or black. Orange marks *action and identity* (CTAs, badges, headings),
never plain data values.

This action-hierarchy is **surface-agnostic** — it governs generated documents (the PDF
exports: handover protocol, bank statement, three-month report) exactly as it governs
screens. In those PDFs orange is reserved for the title, the section headers, a single
accent line under each table header, the balance-chart line, the thin page top-accent bar
and the logo; page surfaces and the data-cell grid stay neutral (black / `#141414` /
`#1C1C1C` fills, `#282828` hairlines), so the orange never overwhelms the document.

**Acceptance**

- [ ] Logo renders only in `#E77E23` / white / black.
- [ ] The single filled-orange CTA marks the one primary action per context.
- [ ] Generated PDFs use orange only as a heading/identity accent (title, section headers,
  one table-header accent line, chart line, top bar, logo); table-head fills and data-cell
  grids are neutral dark/gray, never a full orange fill or an all-orange grid.

### REQ-UI-003 — Dark-only surfaces

Backgrounds are **`#000000`** (page) and **`#141414`** (Grau 4 — header, footer, tables,
cards, sidebar). The page background is **flat** — no ambient pattern or texture (the former
honeycomb/Wabenmuster wash was removed 2026-07); a subtle radial **top bloom**
(`rgba(231,126,35,0.10)` fading to transparent) over the black is permitted on the
login / entry surfaces. `color-scheme: dark`; there is **no light theme**.

> **Contrast note.** Removing the honeycomb wash left muted Grau 2 text at a sub-AA ratio on
> the now-flat black. Muted **text** must use the accessible `--color-gray-2-text` tint per
> REQ-UI-006, not the canonical `--color-gray-2`.

### REQ-UI-004 — Typography

- **One typeface: Lato.** Body / UI default weight Light 300, Bold 700 for emphasis.
- **Headlines:** Lato too — distinguished by **weight (Bold 700)** + **UPPERCASE only** +
  letter-spacing `0.05em`, **not** by a separate display face. (History: Ethnocentric →
  Audiowide → consolidated to **Lato-only** in 2026-06; the Audiowide/Ethnocentric `@font-face`
  rules and font files were removed. `--font-headline` is kept as a Lato alias so existing
  `var(--font-headline)` references keep resolving.)
- The brand ships no monospace face; "mono" contexts use Lato with tabular figures.

### REQ-UI-005 — Department colours (Bereichsfarben) — values are frozen

The org's department colours are authoritative per Corporate Design Manual p.14:
*"Die Farbwerte dürfen weder abgewandelt noch verändert werden."* Use these names and
values exactly:

|              Department               |               Token               |    Hex    |
|---------------------------------------|-----------------------------------|-----------|
| Raumüberlegenheit (Space Superiority) | `--color-dept-raumueberlegenheit` | `#37BBC0` |
| Forschung (Research)                  | `--color-dept-forschung`          | `#355DDC` |
| Sub-Radar (covert)                    | `--color-dept-sub-radar`          | `#A3000A` |
| Marinekorps (Marine Corps)            | `--color-dept-marinekorps`        | `#7A5E96` |
| Profit                                | `--color-dept-profit`             | `#239E33` |
| Search and Rescue                     | `--color-dept-search-rescue`      | `#FFD23F` |

> **Deprecated aliases — do not use as names.** The shipped `styles.css` historically
> mis-named these (it called `#A3000A` "combat", `#355DDC` "sub-radar", `#37BBC0`
> "research"). Those survive only as deprecated CSS aliases (`--color-dept-combat`,
> `--color-dept-research`, `--color-dept-marine`) so old code resolves; always use the
> official names above. *(This corrects the inverted mapping that previously lived in
> `CLAUDE.md`.)*

**Acceptance**

- [ ] Department tags/badges use the official token names with the exact hex values.

> **Amended by epic #692 (REQ-ORG-018):** these frozen Bereichsfarben are also applied to **org-chart
> nodes**, tinting each Bereich's sub-tree with its colour. This applies the existing tokens (no new
> hues); node text must keep ≥ 4.5:1 contrast (use the accessible `--color-*-text` tints where the hue
> would become small text).

### REQ-UI-006 — Semantic status colours

Status hues reuse Bereichsfarben values by appearance: danger `#A3000A`, success
`#239E33`, warning `#FFD23F`, info `#355DDC`. Treat them as status, not as the department.

**Accessible text tints.** The canonical danger/info/success hues are dark on the black
canvas (danger ≈ 2.3:1, info ≈ 3.6:1) and fail WCAG AA as small text. When a semantic
colour is the **text itself** (inline validation messages, status labels, price up/down),
use the lightened tints — `--color-danger-text` `#F2564B`, `--color-info-text` `#6C93EF`,
`--color-success-text` `#2EBC3D` (all ≥ 5:1 on black). Keep the canonical hues for fills,
borders and the brand Bereichsfarben tags. An invalid field additionally takes a red
hairline (`.input-error`) beside its `.field-error` message.

The same rule applies to the **muted grayscale**: Grau 2 (`--color-gray-2` `#646464`)
reads at only ≈ 3.5:1 on the flat-black page and fails WCAG AA as small text (the former
honeycomb wash masked this from the automated a11y gate; the flat-black surface of
REQ-UI-003 exposed it). When muted grey **is the text itself** — `.text-muted`, secondary
labels, hints, placeholders, the quiet-danger button label — use `--color-gray-2-text`
`#8A8A8A` (≈ 6.1:1 on black, ≥ 4.9:1 on the `#141414` / `#1C1C1C` surfaces). Keep the
canonical `--color-gray-2` for hairline borders, scrollbar thumbs, disabled fills and
purely decorative glyphs.

**Acceptance**

- [ ] Semantic colour used as small text uses the matching `*-text` tint, not the dark
  canonical hue; the canonical hues stay on fills/borders/tags.
- [ ] Muted grey used as small text uses `--color-gray-2-text`, not the canonical
  `--color-gray-2`; the canonical Grau 2 stays on borders/scrollbars/decorative glyphs.

### REQ-UI-007 — Visual style: square-first sci-fi HUD

Sci-fi / space-organisation / technical-HUD aesthetic: geometric shapes (rings,
triangles), thin technical markers framing content. **Corners are sharp** (`--radius-none`)
everywhere except pills (chips/badges) and circular controls. The orange bloom glow is the
only "shadow" idiom.

### REQ-UI-008 — No native browser dialogs

**Never** use `confirm()`, `alert()`, `prompt()`, or any native browser dialog. Build
KRT-styled modals/toasts instead.

**Acceptance**

- [ ] No `confirm(` / `alert(` / `prompt(` calls in frontend JS.

The shared `krtFetch` mutation layer (REQ-FE-001..005,
[`frontend-ajax-mutations.md`](frontend-ajax-mutations.md)) surfaces every success / error /
optimistic-lock outcome through the KRT toast/confirm infrastructure precisely so this rule holds
app-wide; new AJAX call sites inherit it for free.

**Enforced by:** code/design review + ESLint (mechanical grep-able rule).

### REQ-UI-013 — Canonical modal shell + one close convention (S12, #918)

The KRT HUD modal — `.krt-modal-overlay` scrim > `.krt-modal` frame (orange top edge + corner
brackets) > `.krt-modal-head` (title + close-X) — is extracted as the reusable Thymeleaf fragment
`fragments/modal-wrapper.html :: modal(modalId, titleKey, variant, body)`. New `.krt-modal-overlay`
modals and migrations use it rather than hand-copying the shell; the bespoke body/footer is passed
through the `body` fragment expression (`~{::selector}`) so rendering stays identical, and
`variant` appends a `.krt-modal--*` class (e.g. `krt-modal--wide`, `krt-modal--danger`). Modals open
with `data-trigger="open-modal-display"` and **close with the single standardized trigger
`data-trigger="close-modal-display"` + `data-modal-id`** (common-handlers.js) — the former
`data-modal-dismiss` convention is being migrated onto it. The overlay's hidden default comes from
the **global** `.krt-modal-overlay { display:none }` in `styles.css` (loaded on every page;
`bank.css` duplicates it as defense-in-depth), so the fragment injects no inline style. A modal is
made visible by adding the `krtm-modal-open` class (`display:flex`, in `inline-migration.css` which
is loaded last so it wins) — at runtime via `open-modal-display` (which toggles `classList`, not an
inline `style.display`) or a server-rendered `th:classappend`; the global default must never be
`display:flex`, or a page whose scoped stylesheet fails to load would render every closed modal open
on load (#1003 WebKit flake). A page script that **closes** a modal after an in-place AJAX write
(e.g. the bank confirm/reject modal on success, `bank.js`) must close it the **same** class-based
way — remove `krtm-modal-open` (and add `krtm-hidden`), never write an inline `modal.style.display =
'none'`. An inline `display` outranks the non-`!important` class rule, so an inline close leaves a
stale inline style that the class-toggling `open-modal-display` cannot beat, and the modal can never
be re-opened without a full page reload (a bank staffer confirming a second request straight after
the first got a dead button). The inverse also holds: a modal a page script OPENS with an
inline `style.display = 'flex'` must not be closed through the class-only `close-modal-display`
alone, or the inline `display:flex` outranks `krtm-hidden` and the modal stays on screen (the
`delete-operation-modal` Cancel button). As a defensive backstop **both** shared handlers clear any
inline `display` on the modal — `open-modal-display` before showing it, `close-modal-display` before
hiding it — so the class always wins regardless of how the other side toggled visibility.

**No inline `style=""` attributes (CSP hardening).** Templates must not use inline `style=""`
attributes: the CSP pins `style-src-attr 'none'`, so an inline style attribute is blocked by the
browser (closing the CSS-injection residual). Static styling goes in a CSS class; a former inline
value already has a `krtm-*` class in `inline-migration.css` (generated, one class per distinct
value, loaded last). Data-driven values use a class toggle (`th:classappend`, e.g. the modal
`krtm-modal-open`/`krtm-hidden` pair, `krtm-opacity-05/06`, `krtm-color-danger`) or, when the value
is genuinely dynamic (progress-bar widths), a `data-krtm-width` attribute applied to `style.width`
via the CSSOM in `inline-style-apply.js` — the CSSOM is not governed by `style-src-attr`. Setting a
style through JavaScript (`element.style.x = …`) stays allowed; only literal `style=""` attributes in
the rendered HTML are forbidden (ADR-0093).

The prohibition covers `style=""` attributes emitted by **JavaScript**, not just server-rendered
templates: a `style="…"` inside an `innerHTML` string is parsed as an inline style attribute and
blocked exactly the same way (this is what broke the `/materials/overview` virtual-scroll spacer
rows — a JS-built `style="height:…"` — after the CSP was pinned). A value a script computes goes
through a CSS class (static) or the same `data-krtm-*` → CSSOM path (`element.style.x = …`, genuinely
dynamic — e.g. the `/materials/overview` virtual-scroll spacer heights) instead. And when a script
toggles the visibility of an element whose hidden state is a **class** (e.g. the skeleton hides it
with `krtm-display-none-*`, or the scu-hint fragment with `krtm-hidden`), it must **toggle that
class** — clearing `element.style.display` does not override a class rule, so `el.style.display = ''`
leaves a class-hidden element hidden. Setting a non-empty display (`el.style.display = 'flex'`) still
works (inline beats a non-`!important` class), and a pure JS filter loop that both hides and reveals
rows via `el.style.display` (no class) is fine; only the reveal-over-class case is the trap.

**Acceptance**

- [ ] A new/migrated `.krt-modal-overlay` modal renders through `modal-wrapper :: modal(...)` with
  its body projected exactly once and closes via `close-modal-display` (no `data-modal-dismiss`).
- [ ] `.krt-modal-overlay` is `display:none` by default in the global `styles.css` (not only in a
  page-scoped stylesheet); a modal is shown by adding the `krtm-modal-open` class (`display:flex`),
  never an inline `style.display`.
- [ ] A modal a script closes after an in-place AJAX write toggles its class (`krtm-modal-open` off,
  `krtm-hidden` on), not an inline `style.display = 'none'`, so the next `open-modal-display` re-opens
  it in the same session without a page reload.
- [ ] A modal a script opens with an inline `style.display = 'flex'` still closes via
  `close-modal-display` (the shared handlers clear the inline `display` on both open and close, so the
  visibility class always wins) — e.g. the `delete-operation-modal` Cancel button.

**Enforced by:** per-screen render MvcTest (shell + single-projection assertion) + e2e smoke.

### REQ-UI-009 — Responsive across four device classes

Every layout change and new component works on **four** classes:

- **Smartphone** (≤768px) and **Tablet** (768–1024px) — touch first; minimum click target
  **44px**; collapse multi-column grids to one column; wide tables scroll horizontally.
- **Desktop** (1024–1600px) and **Ultra-wide** (1600px+) — exploit space (docked sidebars,
  auto-fit card/dashboard grids) but cap long-form text at `max-width: 80ch` on `<p>`.

**Acceptance**

- [ ] Verified at all four breakpoints; interactive targets ≥ 44px on touch classes.

### REQ-UI-010 — Standard action-button icons

The recurring CRUD actions use one fixed glyph from the in-house sprite (`fragments/icons.html`
in the app, `ui_kits/basetool/icons.jsx` in the design system): **delete / remove →
`krt-icon-trash`**, **edit → `krt-icon-edit`**, **save → `krt-icon-save`** (inventory book-out →
`krt-icon-bookout`). In **dense rows** (table / tree / compact action clusters) they render as
**icon-only** `.btn-icon` squares carrying their label in `title` + `aria-label`; in **forms and
dialogs** they render as **icon + text** (the glyph prepended before the label, which stays in a
`<span th:text>`). Decorative button glyphs set `pointer-events: none` (via `.btn .krt-icon`) so a
click always lands on the host `<button>` / `<a>`, never the inner `<svg>`. Danger styling
(`btn-quiet-danger` / `btn-outline-danger`) and existing `data-*` hooks are preserved. Mode toggles
whose label flips with state (e.g. the org-chart edit toggle) keep their text label.

**Acceptance**

- [ ] Delete / edit / save buttons use the matching sprite glyph; dense-row instances are icon-only
  with an accessible name in `aria-label` / `title`, form / dialog instances keep a visible label.
- [ ] Clicking the glyph triggers the button's action (no dead clicks on the inner `<svg>`).

**Enforced by:** code/design review · **Code:** `fragments/icons.html`, `static/css/styles.css`
(`.btn-icon`, `.btn .krt-icon`), the per-feature templates.

### REQ-UI-011 — Overlay popups are not clipped by their container

Floating popups that overflow their host field — the searchable-select dropdown
(`.krt-combobox__listbox`, the type-to-filter list that progressively enhances a `<select>`) and
the inventory allocation popover (`.assoc-pop`, the Variante-C "+ Zuordnen" order/mission picker
in the Lager tree, REQ-INV-027) — must overlay the surrounding chrome, **not** be cropped by an
ancestor's `overflow`. Two ancestor shapes make this bite: inside a modal `.krt-modal-body`
scrolls (`overflow-y: auto`), so an in-flow `position: absolute` popup would be chopped at the
body's bottom edge — i.e. behind the pinned `.krt-modal-foot` action bar; and in the Lager tree
the table sits in horizontally-scrolling wrappers (`#tableContainer.overflow-x-auto` +
`.table-responsive`), whose `overflow-x: auto` **forces `overflow-y` to `auto` too** (a CSS
invariant — you cannot pair horizontal scroll with visible vertical overflow), so an absolute
popover is likewise clipped at their bottom edge (both Firefox and Chrome). The popup is therefore
anchored to its trigger in viewport space (`position: fixed`), kept glued to it while the window
or any scroll container scrolls/resizes: the searchable-select list by `krt-searchable-select.js`
(`positionListbox` / reposition on scroll+resize), flipped above the field when there is more room
there than below (`.krt-combobox__listbox--above`) with its height capped to the available space so
no option lands off-screen; the allocation popover by `inventory-admin.js` / `inventory-my.js`
(`assocPositionPop` / `assocRepositionOpenPop`), re-anchored to the `.assoc-add-wrap` trigger's rect
on open and on every scroll/resize.

**Acceptance**

- [ ] The user picker in the bank "Halter registrieren" modal (and any searchable select in a
  modal) shows its full option list over the modal foot — no option is hidden behind the
  action bar.
- [ ] A searchable select low in the viewport flips its list upward instead of overflowing
  off-screen.
- [ ] The "+ Zuordnen" order/mission popover on a Lager entry near the table's bottom edge renders
  in full — input and option list both — instead of being cut off where it crosses the container
  boundary (regression: Firefox and Chrome clipped the absolute popover there).

**Enforced by:** code/design review · **Code:** `static/js/krt-searchable-select.js`,
`static/js/inventory-admin.js`, `static/js/inventory-my.js`,
`static/css/styles.css` (`.krt-combobox__listbox`, `.krt-combobox__listbox--above`, `.assoc-pop`).

### REQ-UI-012 — User-facing labels show the display name, never the raw username

Wherever the tool renders a person's identity to a user, it shows that user's **effective name** —
the **display name** when one is set (non-blank), otherwise the **username** as the fallback. The raw
username is never the visible label when a display name exists. This is the project-wide
identity-presentation rule and holds on **every** surface that names a user — table and list rows,
badges and avatars, detail and profile pages, dropdown / picker option labels, the org chart and
Leitung views, mission / order / bank / inventory / refinery rows, the audit viewer's actor column,
notifications, and the generated PDF exports — across all four device classes.

The single source of truth is the backend: `User.getEffectiveName()` returns the display name when
present and falls back to the username, and the `effectiveName` field carried on `UserDto` /
`UserReferenceDto` (and every projection derived from them) is what templates bind to. A surface that
binds the raw `username` for display, or re-derives the fallback itself, is a defect — bind
`effectiveName`. Any new user-bearing DTO or projection must expose `effectiveName` so the surface has
it to bind.

**Carve-outs** (the username may legitimately appear):

- **Account administration of the identity itself.** The admin member-edit form, registration
  approval, and the profile screen show and edit the raw `username` and `displayName` because the
  username *is* the datum being managed there, not a label standing in for a person.
- **Search / disambiguation as a secondary term, not the primary label.** The shared searchable user
  pickers (REQ-FE-011, ADR-0053) display the display name as the option label and fold the username
  into the filter haystack (`data-search`) so a person is findable by login handle; the username stays
  a hidden search term, it does not replace the visible name.

**Acceptance**

- [ ] On every user-naming surface, a user with a display name set is shown by that display name, and
  a user without one falls back to the username.
- [ ] No display surface binds the raw `username` as the visible person label where an
  `effectiveName` is available; templates bind `effectiveName`.
- [ ] The carve-out screens (member-edit, profile, registration approval) still show the raw username
  as the managed account field; the searchable pickers still match on the username without showing it
  as the label.

**Enforced by:** code/design review · **Code:** `User.getEffectiveName()`, the `effectiveName` field
on `UserDto` / `UserReferenceDto` and downstream DTOs, the per-feature Thymeleaf templates ·
**Related:** REQ-FE-011, ADR-0053.

## Out of scope

Brand assets/logos themselves (managed in the design skill `assets/`), and the desktop SC
Extractor's GUI design (see [`docs/DESIGN_SC_EXTRACTOR.md`](../DESIGN_SC_EXTRACTOR.md)).

**Material-amount input fields** (SCU/PIECE precision, positivity, the `.`/`,` separator) are
cross-cutting (inventory, orders, refinery), so their rules live in their own spec —
[`inv-material-quantities.md`](inv-material-quantities.md) (REQ-INV-001 / REQ-INV-002) — not here.
This spec still governs how those fields *look*.

## Open questions

- Should REQ-UI-008 (no native dialogs) and REQ-UI-005 (frozen hex values) get a dedicated
  ESLint/Stylelint rule so they are gate-enforced, not review-enforced? (Promote to an ADR
  if yes.)

