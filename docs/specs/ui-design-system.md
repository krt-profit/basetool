> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-09-22.
> **Owner area:** UI · **Related ADRs:**
> [0053](../adr/0053-standardize-user-selection-on-searchable-combobox.md) (searchable user pickers, REQ-UI-012) ·
> [0093](../adr/0093-eliminate-inline-style-attributes-csp-style-src-attr-none.md) (no inline `style=""`, REQ-UI-013) ·
> [0120](../adr/0120-per-browser-filter-selection-persistence.md) (REQ-UI-017) ·
> [0164](../adr/0164-an-installable-web-app-without-a-service-worker.md) and
> [0166](../adr/0166-identity-moves-onto-the-app-origin.md) (REQ-UI-020) ·
> [0172](../adr/0172-the-phone-class-gets-its-own-layout-contract.md),
> [0176](../adr/0176-layout-floors-are-defaults-that-controls-opt-out-of.md) and
> [0191](../adr/0191-touch-drags-the-crew-board-through-pointer-events.md) (REQ-UI-009) ·
> [0177](../adr/0177-the-app-has-exactly-one-dialog-shape.md) (REQ-UI-013) ·
> [0197](../adr/0197-shipped-dependencies-pass-a-gpl-compatible-licence-gate-and-are-listed-on-a-public-page.md) (REQ-UI-021) ·
> **Next free id:** `REQ-UI-023` · **Visual source of truth:** the design
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

**Enforced by:** design review. The web-asset linters (`:frontend:lintCss` / `lintJs` /
`lintHtml`) gate syntax and style only; no rule checks that a value uses a token.

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

> **Deprecated aliases — do not use as names.** Three earlier code names survive only as CSS
> aliases so old code resolves: `--color-dept-combat` → Raumüberlegenheit, `--color-dept-research`
> → Forschung, `--color-dept-marine` → Marinekorps (`styles.css` `:root`). Always use the official
> names above; the last remaining consumer is `operation-detail.html`'s inline
> `--color-dept-marine`.

**Acceptance**

- [ ] Department tags/badges use the official token names with the exact hex values.

> **Amended by epic #692 (REQ-ORG-026):** these frozen Bereichsfarben are also applied to **org-chart
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

**Enforced by:** code/design review only. The rule is grep-able, but ESLint's `no-alert` is **not**
enabled in `frontend/eslint.config.mjs` (checked 2026-09-22) — see Open questions.

### REQ-UI-013 — Canonical modal shell + one close convention (S12, #918; one shape, #1891)

> [!important] There is exactly ONE dialog shape (#1891, ADR-0177)
> The app used to render dialogs in three: this one, legacy A (`.modal` / `.modal-content`) and
> legacy B (`.modal-overlay` / `.modal-box`, opened by an `active` class). All 54 legacy dialogs
> are ported onto the shell below and **both legacy shapes are deleted** — markup, CSS rule sets
> and open/close contracts. `.modal`, `.modal-content`, `.close-modal`, `.modal-overlay`,
> `.modal-box`, `.modal-title` and `.modal-actions` no longer exist; neither do the `open-modal` /
> `close-modal` (`active`-class) handlers in `common-handlers.js`. A new dialog uses this shape or
> it is wrong — a second shape is the defect, not a style choice.
>
> Two guards hold it, and they ask different questions. `MODAL_SHAPES` in
> `TouchClassLayoutE2eTest` (now a **single** entry) drives the browser sweep that measures each
> dialog's real geometry at five widths. `SingleModalShapeTest` reads templates and stylesheets as
> text on every push — no browser, no `e2e` label needed — and fails if any legacy dialog class
> reappears, if an overlay does not carry exactly one frame, or if a footer's submit button sits
> outside the form it submits.

The KRT HUD modal — `.krt-modal-overlay` scrim > `.krt-modal` frame (orange top edge + corner
brackets) > `.krt-modal-head` (title + close-X) — is extracted as the reusable Thymeleaf fragment
`fragments/modal-wrapper.html :: modal(modalId, titleKey, variant, body)`. New `.krt-modal-overlay`
modals and migrations use it rather than hand-copying the shell; the bespoke body/footer is passed
through the `body` fragment expression (`~{::selector}`) so rendering stays identical, and
`variant` appends a `.krt-modal--*` class (e.g. `krt-modal--wide`, `krt-modal--danger`). Modals open
with `data-trigger="open-modal-display"` and **close with the single standardized trigger
`data-trigger="close-modal-display"` + `data-modal-id`** (common-handlers.js). The older
`data-modal-dismiss` convention is being migrated onto it and survives only on the
`mission-detail.html` dialogs (handled in `mission-detail.js`); new dialogs never use it. The overlay's hidden default comes from
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

**Three sanctioned widths, and no fourth.** `.krt-modal` is 440 px (confirms and single-field
prompts), `.krt-modal--wide` is 600 px (form-heavy: 3+ stacked fields) and `.krt-modal--xwide` is
800 px, reserved for dialogs whose body carries a **table** rather than a form — the Auftrag
material/item pickers and the refinery store sheet. A dialog that needs a width none of these gives
is a signal to revisit its content, not to add a per-page `max-width`.

**The head's parts are styled, and both heading levels count.** `.krt-modal-close` carries the
`.close-sidebar-btn` treatment (its already-approved sibling — same job, an ✕ that dismisses a
surface), squared and floored to 44 px in the touch block, with `flex-shrink: 0` so a long German
title cannot squeeze it narrower than its glyph and a `:focus-visible` ring because it is the first
Tab stop inside a dialog. It had **no rule at all** until #1884 found it by measurement, and the
port would have moved the legacy dialogs onto something worse than the styled `.close-modal` they
came from. The head's title rule covers **`h2` and `h3`**: hand-written shells use `h2` and
the `modal-wrapper` fragment emits `h3`, and while only `h2` was styled a fragment-rendered title
fell through to the global heading rule and rendered orange at the browser's default `h3` size
instead of white at `0.85rem`. The close button's accessible name is **`general.a11y.closeModal`**
(it was `bank.a11y.closeModal`, on fifteen pages that have nothing to do with the Bank).

**A wrapper between the frame and its form needs `.krt-modal-flow`.** The frame caps itself at
`90vh` and lets only `.krt-modal-body` scroll, which holds as long as head/body/foot are flex items
of `.krt-modal`. A dialog whose body and footer live inside a live-sync swap container — the
refinery store dialog's `#refinery-store-results`, the `?fragment=store` seam — has a plain `<div>`
in between that would otherwise absorb the column layout and leave the body unscrollable. That
wrapper carries `.krt-modal-flow`.

**One shape means one busy probe.** `krt-live-sync.js` asks "is any dialog open?" by querying
`.krt-modal-overlay`, and that guard is what stops a peer's change from swapping the DOM out from
under an open form and 409-ing the next submit. While three shapes existed the probe saw only one of
them, so 54 dialogs had no such protection and org-chart hand-rolled a `busyTest` to compensate. A
dialog in a new shape would silently lose this again — which is the second reason the shape is
singular.

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

**A fixed-width control in a multi-column `.form-row` declares its real floor.** A `.form-row`
splits its width evenly and its per-column floor is generic (`.form-row > .form-group` declares
250px on mission-detail). A control that cannot shrink below more than that silently spills out of
its column: it eats the row's column gap and overruns the container's padding, and because nothing
clips it the overflow reads as "the spacing is wrong" rather than as a broken layout. The
`.datetime-split-group` is the case in the codebase — its date and time parts are fixed-width and
non-shrinkable (10.5rem + `--space-2` + 7rem = an **18rem** floor). The measured defect is the
participant edit modal: two groups in the 600px `.krt-modal--wide` frame get ~275px each, so the
time part overflows by ~13px and "Endzeit" sits 3px from the modal border — at every desktop width
from 1280px up. The mission Verwaltung form is the other multi-column user of the widget and is
**not** affected today: its row keeps two groups per line at ≥18rem each across 1280–1800px, both
before and after this rule. The rule is stated for the widget rather than scoped to the modal, so
it also covers a future narrower row there.

Such a control declares its true minimum (`min-width: min(18rem, 100%)`) so the row **wraps** onto
full-width lines instead of overflowing — where there is room the groups stay side by side, otherwise
they fall onto their own line — and the `min()` keeps a narrow (mobile) container shrinking rather
than overflowing in turn. A wrapped line keeps the standard field rhythm (`row-gap: 0`; the
`.form-group` margin does the spacing) instead of stacking the row gap on top of it. The rule must
out-specify the page-level `.form-row > .form-group` floor, which is declared later in the cascade.

**Acceptance**

- [ ] A new/migrated `.krt-modal-overlay` modal renders through `modal-wrapper :: modal(...)` with
  its body projected exactly once and closes via `close-modal-display` (no `data-modal-dismiss`).
- [ ] **Exactly one dialog shape exists.** `.modal`, `.modal-content`, `.close-modal`,
  `.modal-overlay`, `.modal-box`, `.modal-title` and `.modal-actions` appear in **no** template and
  **no** stylesheet, and no dialog is opened or closed by an `active` class.
- [ ] Every dialog is a `.krt-modal-overlay`, so `krt-live-sync.js`'s busy probe sees it and a
  peer's change cannot swap the DOM out from under it.
- [ ] The frame's width is one of the three sanctioned variants (default / `--wide` / `--xwide`),
  not a per-page `max-width`.
- [ ] The head carries a `.krt-modal-close` labelled `general.a11y.closeModal`, and its title is an
  `h2` or `h3` (both are styled).
- [ ] A swap container between `.krt-modal` and its `<form>` carries `.krt-modal-flow`, so the body
  still scrolls under the `90vh` cap.
- [ ] `.krt-modal-overlay` is `display:none` by default in the global `styles.css` (not only in a
  page-scoped stylesheet); a modal is shown by adding the `krtm-modal-open` class (`display:flex`),
  never an inline `style.display`.
- [ ] A modal a script closes after an in-place AJAX write toggles its class (`krtm-modal-open` off,
  `krtm-hidden` on), not an inline `style.display = 'none'`, so the next `open-modal-display` re-opens
  it in the same session without a page reload.
- [ ] A modal a script opens with an inline `style.display = 'flex'` still closes via
  `close-modal-display` (the shared handlers clear the inline `display` on both open and close, so the
  visibility class always wins) — e.g. the `delete-operation-modal` Cancel button.
- [ ] No control renders wider than its column: a fixed-width control in a multi-column `.form-row`
  declares its true `min-width` and wraps onto its own full-width line instead of overrunning the
  column gap and the container padding — verified by measurement at several desktop widths, since
  whether a column falls below the floor depends on how many items the row keeps per line.

**Enforced by:** `SingleModalShapeTest` (one shape, one frame per overlay, submit buttons inside
their form — text-level, runs in `check`) · per-screen render MvcTests (shell + single-projection
assertion, e.g. `AdminAuditLogModalRenderMvcTest`, `AdminBankWipeModalRenderMvcTest`) · e2e smoke.
The fits-the-column rule is guarded by `MissionDatetimeSplitLayoutE2eTest`, which measures the
rendered rectangles of every date/time part against its own column on both mission surfaces, at
each of 1280 / 1440 / 1600 / 1800px. Two properties of that test are load-bearing. It compares
bounding boxes rather than `scrollWidth`/`clientWidth`, because the overflow lands inside the
container's right padding and the scrollable overflow region does not account for it — the broken
layout reported `scrollWidth === clientWidth` and would have passed the bug straight through. And
it sweeps widths rather than picking one, because a single viewport proved nothing: against the
unfixed stylesheet the modal check fails at every swept width while the Verwaltung check passes at
all of them, so the latter is a forward-looking invariant, not a reproduction.

### REQ-UI-009 — Responsive across four device classes

Every layout change and new component works on **four** classes:

- **Smartphone** (≤768px) and **Tablet** (768–1024px) — touch first; minimum click target
  **44px**; collapse multi-column grids to one column; wide tables scroll horizontally.
- **Desktop** (1024–1600px) and **Ultra-wide** (1600px+) — exploit space (docked sidebars,
  auto-fit card/dashboard grids) but cap long-form text at `max-width: 80ch` on `<p>`.

**Dense row actions are an explicit exception at 32px.** The two compact variants `.btn-xs`
and `.btn-icon` — the *repeated* per-row actions of a dense table / tree action cluster
(REQ-UI-022) — carry a **32px** minimum hit area on **every** device class, touch classes
included, rather than the 44px floor above. Density in those clusters is what keeps a wide
Lager / bank / mission table readable, and the design system specifies exactly that: `.btn-xs`
at 32px in `krt-components.css`, `.btn-icon` in its README as the icon-only row action that
"saves ~50–60% of the action column in dense tables". Note the design system orders `.btn-xs`
*after* `.btn`, so it has always worked there — the inertness below is an app-side regression
introduced when the declarations were copied in above `.btn`. The exception is narrow: it
covers only those two classes. Every standalone, primary, form and dialog button keeps the 44px floor on every
class, as does `.btn` itself — including on touch-laptops, which report as desktop and are
the reason that floor is applied unconditionally rather than inside a width media query.
Approved by @greluc on 2026-08-01, when both classes were found to have been silently inert
since they were introduced: `.btn` is declared after them in `styles.css` and re-declared in
the ≤1024px touch block, so it won every shared property and each "dense" button had in fact
been rendering full-size.

**The header is compact on the Smartphone class** (owner decision 2026-09-13). It measured 76px on
a 375px screen — 16px padding plus 60px of content — and the 60px came from the wordmark wrapping to
two lines, not from the 50px mark: the wordmark carries the active OrgUnit context (REQ-ORG-024) and
„Profit Basetool – Alle Staffeln" is simply longer than a phone is wide. The phone class therefore
renders the mark at 32px and the wordmark on one line with an ellipsis, for roughly 48px. It stays
**sticky**: menu, mark and bell remain reachable without scrolling, which is why this was chosen
over letting the header scroll away like the footer. Scaling the mark's box is not a REQ-UI-019
deviation — the asset keeps its own proportions and its specified breathing room; nothing is cropped
or forked.

**On the Smartphone class the page footer scrolls with the content** (owner decision 2026-09-13);
on every wider class it stays pinned to the viewport bottom. Pinned, it cost the phone twice: the
column-stacked footer is two rows tall and `main` reserved that height *again* as `padding-bottom`
so the last line could clear it — roughly a fifth of an 812px screen spent on three legal links, on
the class with the least room and the one the app now ships to as its mobile client (REQ-UI-020).

The mechanism is `position: static` inside the `width <= 768px` block, and one consequence is not
optional: **`--krt-footer-height` means "how much of the viewport bottom is covered", not "how tall
the footer is".** `sidebar.js` publishes `0` whenever the footer is not `fixed`, because every
reader of that property — `main`'s reserve, the shared `--krt-panel-viewport-rest` that caps the
Materialbörse / Materialien-Übersicht / Gewinnberechnung / Beförderung panels, the org-chart proxy
scrollbar's `bottom`, the mission-detail action bar's `bottom`, and the mission- and
operation-detail paddings — would otherwise reserve space for a footer that is not there.

**Two amendments to the dense-action exception, owner-approved 2026-09-13** after the first
measured sweep of the touch classes found both:

- **`.btn-xs2` is NOT exempt.** It is a page-local 32px variant declared in an inline `<style>` by
  `mission-detail.html` and `operation-detail.html`, and two of its instances are *form*
  actions — „Ziel hinzufügen", „Schritt hinzufügen" on the Einsatz create form. The rule above is
  explicit that a form button keeps the floor, so the whole variant is raised to 44px on the touch
  classes and keeps its density on desktop. The override is written `.btn.btn-xs2` because the
  page's inline rule is read *after* `styles.css`: at equal specificity the page would win and the
  fix would be silently inert — the same trap this requirement already records for `.btn.btn-xs`.
- **`.master-row` IS exempt**, at 32px. The blueprint list rows on `/personal-inventory/blueprints`
  measured 33px; they are a scan-and-tap list where density is the point, and were ruled equivalent
  to a repeated row action rather than a standalone control.

**A third round of amendments, 2026-09-13**, after the guard first ran with *seeded* data. The
first sweep could only measure what a fresh stack renders, and a fresh stack has empty lists: no
order, refinery order, grant or booking exists, so no detail view and no populated table was ever
loaded. Running the guard inside the destructive e2e suite — where the other flows create those
rows — put 118 findings on surfaces that had never been measured.

- **The floor is on the EFFECTIVE hit area, not on the border box.** A control reaches it just as
  legitimately through a transparent positioned `::after` overlay ("small glyph, fat-finger target")
  or through the `<label>` that activates it, and both are real targets to a finger. This is not a
  relaxation: it is what "hit area" meant all along, and measuring the border box instead reported
  15 org-chart chevrons as defects when `org-chart.css` had stretched each one to
  `var(--touch-target)` with exactly that overlay. A pseudo-element is only counted on a
  non-replaced element, because `<input>`, `<select>` and `<textarea>` render none.
- **Three more in-row controls ARE exempt**, at 32px, on the same reading as `.master-row`:
  `.item-checkbox` (the Lager tree's per-row and per-group selector), `.matrix-flag` (a grant row's
  three permission flags) and `.bank-row-toggle` (a booking row's disclosure chevron). They measured
  26px, 18px and 15px, so all three still had to grow — 15px was below WCAG 2.5.8 AA's 24px, not
  merely below this design system's floor. `.matrix-flag` is the case that shows why the exception
  is not laziness: its three flags sit side by side in one row, so 44px cells would either widen the
  table by a third or, if the target were faked with an overlay, let one flag's overlay reach over
  its neighbours — and a mis-tap there grants the wrong *banking* right.
- **`.demand-sort-btn` is NOT exempt** and is raised to 44px. A column-header sort control is one
  per column, not one per row, so the density argument does not apply to it.
- **The dense floor is the token `--touch-target-dense` (32px)**, mirrored by
  `TouchClassLayoutE2eTest.DENSE_ACTION_FLOOR`. The two must move together.

**Structural defects found in the same run** (fixes, not exceptions): the `/bank/requests` table was
the only `data-table` in the template tree with no `.table-responsive` wrapper, and widening the
layout viewport to 912px on a 375px screen did more than clip the table — `position: fixed` resolves
against the layout viewport, so all three of that page's modals then centred themselves at 456px and
sat mostly off-screen. `.krt-livesync-pill`, the **default** class `krt-live-sync.js` gives the pill,
had no CSS at all while only the mission variant was styled, so every non-mission consumer rendered a
bare browser-default button. The generic `.flex-gap-*` and `.mt-2-flex-gap` row utilities could not
wrap. And `.mission-info-grid` sized its value column `1fr` — shorthand for `minmax(auto, 1fr)`,
whose `auto` minimum is the track's min-content width — so a long mission name widened the tile, the
tile widened its `auto-fit` track and the tablet-landscape class scrolled sideways.

**A fourth amendment, 2026-09-13: the floor is a DEFAULT, not a list** ([ADR-0176](../adr/0176-layout-floors-are-defaults-that-controls-opt-out-of.md)).

Every amendment above added a name to an enumeration, and every round of review found more names the
enumeration had missed — five rounds, ending with `.pa-sort-btn` at 29x18px, which the guard could
not see either because its template renders no sort column unless a category has more than one topic.
That is the shape of the defect rather than an accident of effort: **absence from a list is
indistinguishable from not existing yet**, so every control written after the list starts out
non-compliant, and silently so.

The floor is therefore expressed as a zero-specificity default over
`button, [role="button"], summary, a.btn, input, select, textarea`, which any authored rule beats,
and **a control opts out by DECLARING `min-height: var(--touch-target-dense)`**. The token was
already the marker `TouchClassLayoutE2eTest` reads out of the CSSOM to build its dense set, so the
exemption is still declared by the design system and still followed by the guard with no list on
either side. A control that declares some other sub-floor height is not exempt: it is measured
against the full 44px and fails.

Two consequences worth stating, because both were exemptions this requirement had granted in prose
and no stylesheet had ever declared:

- **`.master-row`'s 32px exemption existed only here.** Nothing in any stylesheet consumed the token
  for it, so the guard's CSSOM-derived set never contained it and the class was being measured
  against 44px the whole time. `personal-inventory.css` declares it now.
- **`.matrix-flag` sized itself with `width`/`height` alone.** A `min-height` clamps a `height`
  regardless of specificity — different properties never compete — so under a default floor it had
  to declare the token to decline it.

Three rules remain written out per control in the touch block, and each states a decision rather than
filling a gap: `.btn.btn-xs2` and `input.item-checkbox` must out-specify a page-local `<style>` rule
that the browser reads after `styles.css`, and the dismiss-button group (`.close-sidebar-btn`,
`.krt-modal-close`) declares its square and its centring. *(2026-09-22: that group used to exist
because `.close-modal` was a `<span>` no element selector reaches; the span went with the legacy
dialog shapes in #1891, and the explicit `min-height` is kept only because it is harmless.)*

Declared `--touch-target-dense` consumers today: `.btn.btn-xs`, `.btn-icon` (`styles.css`),
`input.item-checkbox` (touch block), `.master-row` and `.krt-bp-imp-suggestion`
(`personal-inventory.css`), `.matrix-flag`, `.bank-row-toggle` and `.bank-chart-range-btn`
(`bank.css`), and `.pa-sort-controls .pa-sort-btn` (`promotion-admin.css`).

The same inversion applies to **which rows wrap on the phone class**: a row that directly contains a
control or a link wraps, matched structurally with `:has()` rather than by class name. Enumeration
was hopeless there for an additional reason — many rows carry a generated class from
`inline-migration.css`, and those names are content-hashed.

**Acceptance**

- [ ] Verified at all four breakpoints; interactive targets have an **effective hit area** (own box,
  a positioned `::before` / `::after` overlay, or the `<label>` that activates them) ≥ 44px on touch
  classes, except the dense in-row controls listed above (`.btn-xs`, `.btn-icon`, `.master-row`,
  `.item-checkbox`, `.matrix-flag`, `.bank-row-toggle`, `.pa-sort-btn`, …), which are ≥
  `--touch-target-dense` (32px) on every class. That list is a reader's summary, not the mechanism: the stylesheet's
  `--touch-target-dense` consumers are the source of truth, and the guard derives the set from them.
- [ ] **A new control needs no edit to be compliant.** The floor is a zero-specificity default, so a
  control added with no size rule of its own already meets it; being absent from a list must never
  again be the same thing as being exempt from the floor. The check is the inverse: anything at 32px
  can be shown to declare `--touch-target-dense`, and nothing else is under 44px.
- [ ] Measured with rows in the tables, not only on a fresh stack: a detail view, a populated list
  and a populated table are each a different layout from their empty state, and an empty one hides
  every defect it has.
- [ ] **The sweep's route list is complete, and its coverage is asserted rather than assumed.**
  The list was hand-maintained and was short by seventeen routes for a day while this clause claimed
  otherwise — `/organisation/leitung` among them, whose action rows the same change was fixing. The
  guard now requires every device class to have measured the same set of routes (a class that goes
  quiet mid-sweep is what an expired session looks like) over a floor, and names any list page that
  rendered no row so its detail view is known to be unmeasured. Adding the missing routes found a
  1303px table with no scroll container on `/admin/notification-rules`, broken at **every** class
  including desktop, the first time it ran.
- [ ] **Completeness is gated, not remembered** (2026-09-13). The three E2E classes that each kept
  their own copy of the route list now share one — `FrontendPageRoutes` — and `PageRouteCatalogueTest`
  asks the dispatcher for every mapping it knows, failing when a variable-free `GET` route appears in
  neither `PAGES` nor `NOT_PAGES`. It runs in `check` rather than in the stack-bound `e2e` suite, so
  a controller added on a pull request without the `e2e` label still cannot slip past it. What it
  deliberately does **not** do is decide which of the two lists a route belongs in: a page is
  recognised by its app shell at runtime, and `/inventory/my/stack/entries` (a fragment) and
  `/inventory/my` (a page) are both a `@GetMapping` returning a view name. Its first run found
  `/terms/accept`, which had been in none of the three lists.
- [ ] A compact variant actually renders compact — `.btn.btn-xs` out-specifies `.btn` rather
  than relying on source order, since `.btn` is declared later and again inside the ≤1024px
  touch block. A bare `.btn-xs` selector is silently inert and is a regression.
- [ ] The page never scrolls sideways on a touch class; a table wider than the screen scrolls
  inside a container that itself fits, and **every** `data-table` carries that container.
- [ ] A row of buttons wraps rather than pushing the page wide, including the ones built from the
  generic `.flex-gap-*` / `.mt-2-flex-gap` utilities rather than a named action-row class.
- [ ] A grid track that must hold free text is `minmax(0, 1fr)` and its content may break
  (`overflow-wrap: anywhere`). Neither half works alone: the first lets the track shrink, the second
  lets the text inside it wrap instead of painting straight out of the narrowed track.
- [ ] On ≤768px the footer is `static` and `--krt-footer-height` is `0px`; above it the footer is
  `fixed` and `main`'s `padding-bottom` covers its measured height.

**Enforced by:** [ADR-0172](../adr/0172-the-phone-class-gets-its-own-layout-contract.md) · `TouchClassLayoutE2eTest` (all five device classes — 375×812, 810×1080, 1024×768, 1280×800, 1600×900 — over the page routes of the shared `FrontendPageRoutes.PAGES` catalogue, plus a real detail view per
list and every modal on the page — 96 `.krt-modal-overlay` roots, the one shape `MODAL_SHAPES` holds
since ADR-0177: page-level overflow, cut-off elements, unscrollable tables, control floors, footer
behaviour, chrome share — with a full-page screenshot per page and class) ·
`PageRouteCatalogueTest` (the route list is no longer hand-maintained on trust: it asks the
dispatcher for every mapping it knows and fails when a variable-free `GET` route is in neither
`PAGES` nor `NOT_PAGES`, so a page added next month is swept on the day it is added — and it runs
in `check`, not in the stack-bound `e2e` suite) + code/design
review for the rest · **Code:** `static/css/styles.css` (`.btn`, `.btn.btn-xs`, `.btn.btn-icon`,
`--touch-target-dense`, the `width <= 1024px` touch block and the `width <= 768px` block),
`static/css/bank.css` (`.matrix-flag`, `.bank-row-toggle`), `static/css/personal-inventory.css`
(`.master-row`), `static/css/promotion-admin.css` (`.pa-sort-btn`), `static/js/sidebar.js`
(`--krt-footer-height`).

> **Until 2026-09-13 the two touch classes had no automated coverage at all** — this requirement
> read "Enforced by: code/design review", the only other geometric guard
> (`MissionDatetimeSplitLayoutE2eTest`) sweeps 1280–1800px, and the smoke suite loads pages at the
> default desktop viewport. The first run of the new guard found five pages overflowing the phone
> viewport (`/orders` by 159px) and three chrome controls below the 44px floor on every page: the
> language switcher at 17px, the sidebar close button at 34px and the notification bell at 40px.
> None carried the `.btn-xs` / `.btn-icon` exemption; each was a bare `button` with its own rule,
> and the touch block's selector list enumerates classes, so a control that opts out of `.btn` opted
> out of the floor with it.
>
> **Corrected 2026-09-13: "every modal on the page" was true of one shape out of three.** The sweep
> selected `.krt-modal-overlay` only — 42 roots — while 54 more lived in the two legacy shapes, so the
> sweep was widened to all 96. The selector is declared once (`TouchClassLayoutE2eTest.MODAL_SHAPES`)
> and used at all four sites that have to agree — the count, the probe's measurement loop, and the
> un-hide and restore around the screenshot — because a modal the probe measures but the un-hide
> never reveals is still `display: none`, returns 0×0 rects and is silently skipped.
>
> *Updated 2026-09-22:* #1891 (ADR-0177) then ported all 54 onto the canonical shell and deleted both
> legacy shapes, so `MODAL_SHAPES` now holds one entry. A **new** shape is detected at `check` time
> by `SingleModalShapeTest` (REQ-UI-013), not by this hand-maintained list.

### REQ-UI-022 — Standard action-button icons

> **Renumbered 2026-09-22:** this requirement was `REQ-UI-010` until 2026-09-22; that id also named the per-user category grouping toggle on the trade pages in [`materials-overview-grouping.md`](materials-overview-grouping.md), which keeps it.

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
on open and on every scroll/resize, and likewise **flipped above the trigger** (bottom-anchored)
when there is less room below it than the popover needs and more room above — a fixed box cannot be
scrolled into view, so a trigger low in the viewport would otherwise drop its amount input +
Speichern below the fold, unreachable.

**"Roomier side" is not enough — the popup must fit, and the result is clamped.** Flipping on
`above > below` alone still flips a popover that is taller than the space above it, leaving its
upper end (in pick mode: the combobox) over the viewport top, where a fixed box cannot be scrolled
to. The flip therefore requires the popup to *actually fit* on the chosen side, and the final
placement is **clamped into the viewport** so it is fully visible even when neither side has room.
Beyond reachability this makes the placement independent of the page's exact scroll offset — an
unclamped placement is a function of where the document happens to sit, which is why a 52px change
in footer height (the Fan Kit band moving off the footer, #1529) was enough to break
`InventorySharedLagerLiveSyncE2eTest` with "element is outside of the viewport" on all three
engines while the rendered page looked correct.

Not being clipped from the outside is only half of it: a popup must also **contain its own
controls**. The allocation popover is a fixed 260px box, and its amount editor
(`.assoc-pop__menge`) holds an input plus Speichern plus — in edit mode — Entfernen. Two uppercase
buttons alone claim more than the box's content width, and the input is the only item that can give
way, because `.tree-field input` hands every input in the Lager tree `width: 100%; min-width: 0`
(the popover is a DOM descendant of the tree field even though it is `position: fixed`). Laid out on
one line that collapses the input to an unusable sliver and pushes Entfernen past the popover's
border. The amount input therefore takes a **full-width line of its own** and the buttons share the
next one, with `flex-wrap` as the backstop so a longer translation drops a button to a third line
instead of escaping the box.

**Acceptance**

- [ ] The user picker in the bank "Halter registrieren" modal (and any searchable select in a
  modal) shows its full option list over the modal foot — no option is hidden behind the
  action bar.
- [ ] A searchable select low in the viewport flips its list upward instead of overflowing
  off-screen.
- [ ] The "+ Zuordnen" order/mission popover on a Lager entry near the table's bottom edge renders
  in full — input and option list both — instead of being cut off where it crosses the container
  boundary (regression: Firefox and Chrome clipped the absolute popover there).
- [ ] The "+ Zuordnen" popover on a Lager entry low in the viewport flips above its trigger so its
  amount input + Speichern stay on-screen, instead of dropping below the fold where the fixed
  popover cannot be scrolled into view.
- [ ] Clicking an existing allocation chip opens the amount editor with a full-width, usable amount
  input, and both Speichern and Entfernen sit inside the popover's border — no control is squeezed
  to a sliver and none overflows the box (regression: the input collapsed to 26px and Entfernen
  stuck out 10px to the right).

**Enforced by:** code/design review · **Code:** `static/js/krt-searchable-select.js`,
`static/js/inventory-admin.js`, `static/js/inventory-my.js`,
`static/css/styles.css` (`.krt-combobox__listbox`, `.krt-combobox__listbox--above`, `.assoc-pop`,
`.assoc-pop__menge`).

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

### REQ-UI-017 — Filter selections persist per browser (app-wide convention)

Every **selection-type filter** on a listing/overview surface — checkbox sets, multi-selects,
dropdown selections, boolean toggles, view-mode/tab choices that act as filters, and preset range
selectors — is **persisted per browser** in `localStorage` and restored on the next page load, so a
reload or a visit days later reopens the surface with the last-used selection already applied
(ADR-0120). Two widget families are **deliberately excluded**: free-text search fields and
date-range (`from`/`to`) inputs — a silently restored stale search term or week-old date window
hides data in a way users read as loss; they start fresh on every load.

The mechanics follow the established idiom (REQ-ORDERS-027, REQ-UI-016):

- One JSON object per page under a single storage key; bank surfaces key per user
  (`<name>_<uid>`), other surfaces per browser. Absence of the key means "no saved preference" —
  the server-rendered defaults apply.
- A multi-select dimension whose server default is "no filter" stores all-or-zero checked as
  `null` = "no filter", so options added later stay included; on restore, stale values are dropped
  and an entirely stale subset falls back to the page's rendered no-filter default (all checked on
  the matrix/profit pages, all unchecked on the Lager views — semantically identical). Status
  queues whose server default is a **subset** (orders, refinery: OPEN+IN_PROGRESS) store the
  checked list verbatim and collapse only zero-checked to `null`, so an explicit "show everything"
  choice survives (REQ-ORDERS-027 precedent).
- The selection is persisted immediately on every change (never debounced with the re-fetch).
- On load the restored state is applied through the page's **existing** update path exactly once
  (fragment swap / fetch / guarded `location.replace`), and only when it differs from the rendered
  default — no hand-rolled parallel fetch paths.
- Where filters are mirrored to the URL, an **explicit filter query parameter wins** over the
  stored state and is re-persisted (deeplinks and history navigation stay authoritative).
- Storage access is guarded so a storage-denying privacy mode degrades to the defaults without
  breaking the page.

Covered surfaces (beyond the pre-existing REQ-ORDERS-027 orders queue, REQ-UI-016 price matrix,
bank request-queue/dashboard/org-layout modules and the grouping toggles): Materialbörse (both
boards: mode/tab, min quality, min amount, sort), Mein Lager + Globales Lager (all multi-selects,
min quality, personal-only flags, per view), Raffinerie-Aufträge (status + only-mine),
Profitberechnung (ship + systems), Missionen/Operationen (`showPast`), Meine Bewertungen
(only-open), Persönliche Blueprints (refinery + craftable toggles), Beförderung verwalten
(filters/sort/collapse, migrated sessionStorage → localStorage), Bank-Freigaben (view + account /
employee selection), Bank-Kontodetail chart range (both detail pages) and the Org-Kontodetail tab,
Admin: Audit-Log event-type (per domain), Missionsdaten + SK include-inactive toggles, and the
member selection of the personal-inventory/blueprints admin pages.

**Acceptance**

- [ ] Changing any covered selection filter, reloading, and revisiting later restores the widgets
  and applies the selection to the first data load.
- [ ] Search fields and date ranges are NOT restored.
- [ ] An explicit filter query parameter beats the stored state and is re-persisted.
- [ ] With `localStorage` unavailable every covered page renders with its defaults.

**Enforced by:** `MaterialsOverviewFilterPersistenceE2eTest` (matrix precedent) ·
`FilterPersistenceE2eTest` (representative sweep surfaces) · code review against ADR-0120 ·
**Code:** the per-page JS modules listed in ADR-0120 · **Related:** REQ-UI-016, REQ-ORDERS-027,
ADR-0120.

### REQ-UI-018 — Star Citizen Fan Kit compliance band lives on the home page

The app is a Star Citizen fan project and uses Fan Kit assets, so **two CIG documents bind it, and
they apply cumulatively**:

|             Document             |                                         Requires                                         |
|----------------------------------|------------------------------------------------------------------------------------------|
| Fan Kit **Guidelines** §2/§2b    | the "Made By The Community" logo and the short **trademark notice** (`fankit.trademark`) |
| Fankit **Agreement** clause 2(g) | a separate, longer **non-affiliation notice** (`fankit.disclaimer`)                      |

Clause 2(g) asks for its notice "in a reasonably prominent location, on your fan site or other fan
work wherever materials, trademarks, or properties owned by CIG are located" — which is where the
band already is. The band therefore shows **three** coupled elements, not two.

> Checked, 04.09.2026 — why the marks are named twice: the owner asked whether the §2b line is
> redundant next to the 2(g) notice. It is not, by the letter of both documents. The 2(g) sentence
> carries the *meaning* of the §2b line — it attributes the same marks, plus Squadron 42® — but not
> its *string*: 2(g) adds Squadron 42® and an Oxford comma and has no space before any ®, where §2b
> has one before its third. §2 requires the §2b notice whenever the logo is shown, and the logo is
> not optional: §2a's text alternative ("This is an unofficial Star Citizen Fan Site") needs prior
> approval by CIG's legal department. Neither document mentions the other's notice or offers itself
> as a substitute, so the overlap is CIG's. Dropping the §2b line would satisfy the Agreement and
> the Guidelines only in substance — a low-risk deviation, but a deviation, and this requirement is
> amended first if it is ever taken. The Android app records the same finding in its
> `REQ-APP-SET-007` (`docs/specs/settings.md` in the `basetool-android` repository).

Section 2b accepts three placements on a website — the home page, an always-visible navigation
area, or both. The Basetool uses the **home page**: the band renders at the end of `index.html`'s
`<main>`.

It deliberately no longer sits in the global footer. As a full-width first row it cost every page a
3.25 rem band of a `position: fixed` footer that overlays content, which is worst on the phone/small
device classes of REQ-UI-009. Scrolling with the home page costs no viewport height at all.

Binding details:

- **All three elements ship as one fragment** (`fragments/fankit.html`) and are included together.
  Section 2 requires the trademark notice wherever the logo appears and clause 2(g) requires its
  notice wherever CIG material sits, so none of the three may be rendered, moved or removed on its
  own.
- **Both notices are prescribed legal wording, not UI copy.** Each stays verbatim English in *every*
  locale bundle (`fankit.trademark`, `fankit.disclaimer`) — translating one breaks compliance while
  passing every key-parity check.
- **The two notices differ in details that look like mistakes, and both are correct.** ^fankit-traps
  The §2b line carries a space before its third ®, because CIG's §2b prose writes it that way;
  clause 2(g) carries **no** space before any of its four. Clause 2(g) additionally writes
  `Ltd..` with **two** full stops and an Oxford comma before "and Cloud Imperium®". Harmonising them
  produces a band that satisfies neither document while looking tidier, so
  `FanKitComplianceMvcTest` asserts the difference itself, not just each value.
- **The checked kit version is recorded**, because Agreement clause 11 lets CIG change the documents
  at any time. Verified against `Fankit_2025_11_19` (`06_Fankit_Agreement_2025_11_19.pdf`,
  `08_Fankit_Guidelines.pdf`); the Agreement's 2(g) sentence is byte-identical across the
  2024-04-25, 2025-06-03 and 2025-11-19 kits.
- **Legibility (section 2b):** at least 10 pt and high-contrast. `--fs-sm` (0.9 rem ≈ 10.8 pt) on
  `--color-gray-1` is the floor; the finer `--fs-xs` (≈ 9.6 pt) is not permitted for this text.
- **The artwork is used unmodified** (section 3): no recolour, flip, distortion, outline, drop
  shadow, pattern or effect on the white-artwork variant.
- **The home page stays publicly reachable** (`/` is permitAll in `SecurityConfig`), so the notice
  is visible without a login. Gating `/` behind authentication would forfeit this placement and
  require moving the band back to an always-visible navigation area.
- A mention in the Nutzungsbedingungen or Impressum is a welcome *addition* but never a
  **substitute** — a legal subpage is neither of the two sanctioned surfaces.

  > Correction, 27.08.2026: this bullet used to name `terms.p_9_4` and `terms.p_9_5` as carrying
  > that addition. **Neither key exists** in any bundle, and no migration seeds equivalent text, so
  > the addition was documented but never shipped. The compliance-critical placement is the band,
  > which is where both notices now are.
  >
  > Correction of that correction, 04.09.2026: the 27.08. check grepped the **frontend** bundles
  > only. The keys do exist — `terms.p_9_4` and `terms.p_9_5`, with the rest of § 9 — in the
  > **backend** bundles, because #1594 (2026-08-19) made the terms wording a backend resource
  > served by `TermsDocumentController` (`GET /api/v1/terms/document`) so both clients read one
  > source; #1531 had shipped them in the frontend bundles first. § 9 of the Nutzungsbedingungen
  > therefore does name the fan-project status. It remains an *addition*, never a substitute; the
  > band is unchanged.

**Acceptance**

- [x] An anonymous `GET /` — the landing page, the one page REQ-SEC-052 leaves public — renders the
  Made-By-The-Community artwork, the §2b trademark notice and the clause-2(g) notice.
- [x] `fankit.trademark` and `fankit.disclaimer` are byte-identical in the DE, EN and default
  bundles.
- [x] The two notices keep their **differing** ® spacing, and 2(g) keeps `Ltd..`.
- [ ] Both notices render at ≥ 10 pt with an AA-clearing contrast on their surface.
- [ ] No page carries the logo without both notices.

**Enforced by:** `FanKitComplianceMvcTest` · design review against the Fan Kit Guidelines and the
Fankit Agreement ·
**Code:** `fragments/fankit.html`, `index.html`, `.krt-fankit-*` in `styles.css`,
`fankit.*` in the three message bundles · **Related:** REQ-UI-009.

### REQ-UI-019 — The app wears the Basetool mark; the org mark stays on org surfaces

The Basetool has its **own** logo family (design skill `assets/basetool-*`), derived from the two
parent marks: the DAS KARTELL wedge tilted to a rising course line, with the Profit division's
yield bars growing towards it inside the orbit ring. It is the app's identity, so app surfaces wear
it. The DAS KARTELL mark (`krt.*`) keeps the surfaces where the *organisation*, not the tool, is
the subject.

Binding placement:

|                                    Surface                                    |                                  Asset                                   |
|-------------------------------------------------------------------------------|--------------------------------------------------------------------------|
| Page header brand link (all templates)                                        | `logos/basetool-logo.svg`                                                |
| Browser tab                                                                   | `logos/basetool-favicon.svg` + `-32.png` + `-16.png`                     |
| iOS home screen / pinned site                                                 | `logos/basetool-appicon-512.png`                                         |
| Keycloak login theme                                                          | `img/basetool-logo.svg`                                                  |
| Generated PDF exports (handover protocol, bank statement, three-month report) | `krt.png` / `krt.svg` — **org mark**, unchanged                          |
| Fan Kit compliance band                                                       | `images/made-by-the-community.png` — CIG artwork, untouched (REQ-UI-018) |

Binding details:

- **The favicon is a reduced glyph, not a scaled logo.** Below ~32 px the ring and the star stop
  carrying, so the favicon drops both and keeps wedge + bars. The PNG rasters ship at their exact
  pixel sizes; a browser resampling one large raster into a 16 px tab slot turns the wedge to mush.
  Order in `<head>`: SVG first (what every current engine picks), PNG fallbacks after.
- **`apple-touch-icon` uses the opaque 512 px app icon**, not the favicon glyph — iOS composites its
  own rounded mask and ignores transparency, so a flat glyph would land on a white plate.
- **The header mark is decorative** (`alt=""`). The `.logo-text` wordmark sits inside the same link
  and already names the app at every breakpoint; giving the image an alt as well makes a screen
  reader announce the name twice. The same holds for the Keycloak login, where the `<h1>` does it.
- **`height="50"` on the header mark is the whole box, padding included.** The SVG's 240×240
  viewBox carries roughly 30 % breathing room by design — that is the mark's specified presentation,
  not slack to be cropped. Do not fork a tightened copy of the asset to "fill" the box.
- **The mark renders only in `#E77E23`, white or black** (REQ-UI-002). `basetool-logo-white.svg` is
  the white variant for print/overlay; no other recolour exists.
- The sibling apps draw from the same family — the SC Extractor's desktop icon from
  `basetool-extractor-*`, the Android launcher icon from `basetool-appicon-512.png`. Their
  integration is governed in their own repos; only the shared source of truth (the design skill) is
  common.

**Acceptance**

- [ ] Every rendered page carries `logos/basetool-logo.svg` in the header brand link.
- [ ] `<head>` links the SVG favicon, both PNG rasters and the touch icon.
- [ ] No app page references `logos/krt.webp` or `logos/krt-favicon.webp`.
- [ ] Every referenced brand asset actually ships under `META-INF/resources/logos/`.

**Enforced by:** `BrandMarkRenderMvcTest` · **Code:** `fragments/head.html`, the `.brand` link in
every page template, `META-INF/resources/logos/basetool-*`,
`keycloak-theme/krt-theme/login/login.ftl` · **Related:** REQ-UI-002, REQ-UI-018.

### REQ-UI-020 — The web app is installable, and installs without a service worker

The tool is installable to a phone or tablet home screen as a standalone app. This exists **for
iPhone and iPad**: those members have no native client and cannot get one — the Android app's channel
(a signed artifact from GitHub Releases) has no Apple equivalent a fan project can reach, so a
home-screen web app is the whole mobile story on that platform. The analysis behind that sentence is
`docs/APPLE_PLATFORM_FEASIBILITY.md` in the `basetool-android` repository; the decision is
[ADR-0164](../adr/0164-an-installable-web-app-without-a-service-worker.md).

Binding surface:

|            Piece             |                                                    What it is                                                    |
|------------------------------|------------------------------------------------------------------------------------------------------------------|
| `/manifest.webmanifest`      | Rendered by `WebAppManifestController`, media type `application/manifest+json`, **no `produces` on the mapping** |
| `<link rel="manifest">`      | In `fragments/head.html`, **with `?locale=` and no `crossorigin`** — the fetch must stay anonymous               |
| `theme-color`                | `#141414` — the header fill, not the black page background                                                       |
| `mobile-web-app-capable`     | Standard spelling, plus the `apple-` prefixed legacy one; **both** ship                                          |
| `apple-mobile-web-app-title` | From `pwa.short_name` — short, and the **same in both locales**, because the product name is a proper noun       |
| `apple-touch-icon`           | Already shipped by REQ-UI-019; the manifest reuses the same tile, at its **content-hashed** URL                  |

Binding details:

- **No service worker, by decision.** A worker caching navigations would copy member data — balances,
  rosters, stock — into a second store outside every path that clears the first, while the backend
  marks those reads `no-store` (REQ-SEC-031) precisely so they are not copied. iOS needs no worker
  for „Zum Home-Bildschirm". **The cost is accepted and stated:** Chromium requires a fetch-handling
  worker before it offers its own install prompt, so on Android and desktop the app installs only
  through the browser menu. Adding a worker later is an ADR, not a refactor.
- **The manifest is a controller, not a file under `static/`.** Four of its properties belong to the
  response rather than to a file: the localised `description`, the content-hashed icon URL, the
  media type Spring's resource handler does not know, and `200`-never-`302`. The last is the same
  trap `/.well-known/assetlinks.json` was written for, and both paths sit in the same
  `SecurityConfig` allow-list.
- **The locale travels in the URL, and the fetch carries no credentials.** The page is
  server-rendered and already knows the reader's locale, so the link is
  `@{/manifest.webmanifest(locale=${#locale.language})}` and the response is a pure function of its
  URL. **`crossorigin="use-credentials"` is expressly rejected** — it makes the fetch an
  *authenticated* request, and the unscoped layout `@ControllerAdvice` beans run before every
  handler, `@RestController`s included, so one manifest fetch cost **five** backend round trips and
  needed carve-outs in `TermsAcceptanceGateFilter` and `BackendRoleSyncFilter` for a public
  document. It bought nothing measurable: `name` and `short_name` are identical in both bundles,
  Safari implements neither `lang` nor `description`, and the iOS home-screen label comes from
  `apple-mobile-web-app-title` on the page. Re-adding it needs an ADR.
- **An unsupported `?locale=` renders the default, never a mismatched `lang`.** The controller
  clamps to the shipped bundles. `spring.messages.fallback-to-system-locale` is `false` and the base
  bundle holds **German** copy, so passing a client value through produced a manifest declaring e.g.
  `"lang": "fr"` over German text — and an empty `lang`, which the specification forbids, for a
  malformed value.
- **No `produces` on the mapping.** It makes content negotiation part of the match, and
  `application/json` is not compatible with `application/manifest+json`, so a caller asking for JSON
  — `krtFetch`'s shape, and a blackbox probe with a header set — got a `500` and an `ERROR` log line
  from the `Exception` catch-all. The content type is set on the response instead.
- **The icon is emitted at its content-hashed URL**, resolved through `ResourceUrlProvider`.
  `/logos/**` is served `immutable` for a year, so a manifest naming the bare path would pin every
  installed home screen to a URL no browser revalidates — a redesigned icon would never arrive.
- **`scope` and `start_url` are the application root, and sign-in is inside them.** A `scope` is one
  URL prefix and cannot span two origins, so while Keycloak answered on a host of its own the
  authentication hop was outside it. On iOS a navigation out of scope opens in a Safari View
  Controller, which has its own storage; Apple keeps OAuth in the app **by heuristic** rather than by
  rule (WWDC23) and asks for feedback when that misfires.
  [ADR-0166](../adr/0166-identity-moves-onto-the-app-origin.md) moved Keycloak to `/auth` on this
  origin, so `/oauth2/authorization/keycloak`, Keycloak's own login form, the callback and the
  end-session redirect are all same-origin, all inside the scope, and none of them depends on that
  heuristic.
- **The icon is never declared `maskable`.** Android crops a maskable icon to its own shape and
  guarantees only the inner ~40 %; claiming it for artwork not drawn with that safe zone cuts into
  the mark. A dedicated maskable asset is a request to the design system — see Open questions.
- **`display: standalone`, and `orientation` is deliberately unset.** The layout is responsive;
  locking orientation would make a tablet worse.
- **Status bar `black`, not `black-translucent`.** Translucent draws the page under the status bar and
  needs safe-area padding throughout the layout, which this requirement does not ship.

**Acceptance**

- [ ] `GET /manifest.webmanifest` answers `200` as `application/manifest+json` to an **anonymous**
  request, with no redirect, **for every `Accept` header**.
- [ ] Its `name`, `short_name` and `description` come from the bundles and follow `?locale=`; an
  unsupported or malformed value renders German with `"lang": "de"`, never a mismatched pair.
- [ ] `display` is `standalone`; `start_url`, `scope` and `id` are the application root.
- [ ] `icons` holds **exactly one** entry: the opaque 512 px tile at its **content-hashed** URL,
  `purpose` `any`, never `maskable`.
- [ ] `Cache-Control: max-age=3600, public` and **no** `Vary`, because the body depends only on the
  URL.
- [ ] The rendered page carries the manifest link **with `?locale=` and without any `crossorigin`
  attribute**, the `theme-color` meta matching both the controller constant **and**
  `--color-bg-dark-gray` in `styles.css`, and both standalone hints.
- [ ] No service worker is registered anywhere in the frontend.
- [ ] The path is probed from outside by `blackbox-public-surface` with `follow_redirects: false`,
  and `EdgePublicSurfaceNot200` alerts when it stops answering `200` (REQ-OBS-012).

**Enforced by:** `WebAppManifestControllerTest` (including the no-service-worker sweep and the
stylesheet colour pin), `SecurityConfigStaticAssetPermitAllTest` (the no-redirect contract),
`AnonymousSurfaceSweepMvcTest` (the REQ-SEC-052 registry) · **Code:** `WebAppManifestController`,
`fragments/head.html`, `SecurityConfig`, `RequestLoggingFilter`, `pwa.*` in the three message
bundles, `monitoring/blackbox/blackbox.yml` + `monitoring/prometheus/prometheus.yml` +
`monitoring/prometheus/alerts/infrastructure.yml` ·
**Related:** REQ-UI-019 (the icon family), REQ-SEC-031 (`no-store`), REQ-SEC-052 (the public-path
table), REQ-OBS-012 (the edge posture probes).

### REQ-UI-021 — The shipped third-party components are listed on a public „Open-Source-Lizenzen“ page

The Basetool is GPL-3.0-only and its images are distributed, so every third-party component inside
them is owed a notice, and every library among them must be GPL-3.0-compatible. Both halves are
decided in [ADR-0197](../adr/0197-shipped-dependencies-pass-a-gpl-compatible-licence-gate-and-are-listed-on-a-public-page.md);
this requirement is the user-facing one. The Android app has the same screen (`REQ-APP-SET-006` in
`basetool-android`), and the web page follows it.

- **Where.** `/licenses`, titled „Open-Source-Lizenzen" / "Open-source licences". Linked from the
  footer directly after „Nutzungsbedingungen", from the sidebar's „Rechtliches" group and from the
  landing page's legal links. **Public** (`REQ-SEC-052`) and a legal page for both session gates.
- **What.** Every runtime library of the four shipped modules (`backend`, `frontend`, `ingest`,
  `keycloak-spi`), read from each module's Licensee report, plus the non-Maven components listed in
  `frontend/oss-bundled-components.json` (the Lato font, the Temurin JRE and the Alpine layer of the
  images). Generated by `:frontend:generateOssLicenses` on every build and **never committed**, so
  the page states the versions of the build serving it.
- **How it reads.** Intro, the choice rule („Bietet eine Komponente mehrere Lizenzen zur Wahl an,
  erscheint sie unter jeder davon."), the project's own licence with a link to the source, and a
  meta line `N Komponenten · M Lizenzen · v<version>`. Then one folding section per licence, sorted
  by licence name: the name, `N Komponenten · SPDX: <id>`, a „Lizenztext" link, and one row per
  component — coordinate (linked to its source repository where the POM names one), version, and a
  muted chip per shipped module. A closing line names the generator. On a phone the section's count
  wraps under the licence name.
- **Licences are named by SPDX.** A POM licence URL Licensee cannot map is filed under the
  identifier its text actually is (`ossLicenseUrlAliases` in the root build), the same table the
  build gate reads — so the page and the gate cannot disagree.
- **No data, no backend call.** Read once at startup by `OssLicenseCatalog`. A missing or
  unreadable report renders „Das Lizenzverzeichnis kann gerade nicht angezeigt werden." instead of
  an empty list, and does not fail startup.
- **Nothing on it mutates**, so there is no live-update wiring (`REQ-FE-001…010` do not apply). The
  sections fold with a native `<details>`.
- **Licence texts of bundled files ship beside them:** `OFL.txt` next to every Lato copy
  (`OssBundledComponentsTest`).

Tests: `OssLicenseCatalogTest` (the generated report exists, merges the modules, contains no
AspectJ, and every library carries an allowed SPDX licence), `OssLicensesControllerTest` (public,
rendered, linked beside the terms in the footer), `OssBundledComponentsTest`, and the public-page
entries in `AnonymousSurfaceSweepMvcTest`, `PublicPathsTest`, `TermsAcceptanceGateFilterTest` and
`AnonymousSurfaceE2eTest`. Monitoring: `/licenses` is a `blackbox-public-surface` target
(`REQ-OBS-012`).

## Out of scope

The brand assets themselves — their artwork, variants and rasters — are authored and versioned in
the design skill `assets/`; this spec governs only *where the app applies them* (REQ-UI-019). The
desktop SC Extractor's GUI design lives in
[`docs/DESIGN_SC_EXTRACTOR.md`](../DESIGN_SC_EXTRACTOR.md).

**Material-amount input fields** (SCU/PIECE precision, positivity, the `.`/`,` separator) are
cross-cutting (inventory, orders, refinery), so their rules live in their own spec —
[`inv-material-quantities.md`](inv-material-quantities.md) (REQ-INV-042 / REQ-INV-043) — not here.
This spec still governs how those fields *look*.

## Open questions

- Should REQ-UI-008 (no native dialogs) and REQ-UI-005 (frozen hex values) get a dedicated
  ESLint/Stylelint rule so they are gate-enforced, not review-enforced? (Promote to an ADR
  if yes.)
- ~~**REQ-UI-020: can an installed standalone app actually sign in?**~~ **Closed 2026-09-13 by
  [ADR-0166](../adr/0166-identity-moves-onto-the-app-origin.md)**, by construction rather than by a
  device report — and the question turned out to be less dire than it was written, which is worth
  recording. A `scope` is one URL prefix and cannot span two origins, so the hop to a Keycloak on its
  own host was outside it; on iOS that means a Safari View Controller with its own storage, in which
  the PKCE verifier and `state` written before the hop are in the wrong jar. But Apple keeps OAuth
  navigations in the web app **by heuristic** (WWDC23), so it most likely worked. What the ADR
  removes is the dependency on that heuristic — undocumented, vendor-acknowledged as imperfect, and
  carrying the one flow every member must pass. Keycloak now answers at `/auth` on the app origin and
  nowhere else. A device test is still worth doing, as confirmation rather than as a gate.
- **REQ-UI-020: a dedicated `maskable` icon.** The current artwork was not drawn with Android's
  ~40 % safe zone, so the manifest declares `purpose: any` only. A maskable variant is a request to
  the design system.
- **REQ-UI-020: a 192 px icon.** Chromium's documented install criteria name a 192 px and a 512 px
  entry; the implementation constant is a 144 px minimum, which the single 512 px tile satisfies.
  One extra entry would remove the question rather than leave it resting on an implementation
  detail.
