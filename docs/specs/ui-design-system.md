> **Doc type:** Living spec — kept in sync with `main`. Last reviewed: 2026-10-17.
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
> [0212](../adr/0212-every-stylesheet-sits-in-a-cascade-layer.md) (REQ-UI-024) ·
> [0239](../adr/0239-the-browser-baseline-is-baseline-2025-and-trusted-types-follow.md) (supported browsers) ·
> [0240](../adr/0240-navigation-is-a-structured-drawer-a-quick-access-and-a-phone-tab-bar.md) (REQ-UI-026) ·
> [0242](../adr/0242-page-patterns-a-to-c-and-the-design-system-update-2026-10.md) (REQ-UI-027, REQ-UI-009) ·
> [0243](../adr/0243-colours-and-stacking-layers-are-written-through-tokens.md) (REQ-UI-001, REQ-UI-023) ·
> [0280](../adr/0280-touch-ergonomics-follow-the-pointer-and-the-tablet-is-supported-in-both-orientations.md) (REQ-UI-009, REQ-UI-020) ·
> ADR-0297, ADR-0298 and ADR-0299 (REQ-UI-026 amendment, REQ-UI-032…035, planned; REQ-UI-033
> shipped) ·
> **Next free id:** `REQ-UI-036` · **Visual source of truth:** the design
> skill [`.claude/skills/das-kartell-design/README.md`](../../.claude/skills/das-kartell-design/README.md)
> (+ [`colors_and_type.css`](../../.claude/skills/das-kartell-design/colors_and_type.css)),
> read at the design system's latest `main` ([ADR-0259](../adr/0259-the-design-system-submodule-follows-its-main.md)).

# UI & design system

## Context & goal

The Profit Basetool is the squadron-management web app of the "DAS KARTELL"
org. Its UI must read unmistakably as that brand: a dark sci-fi HUD, the orange house
colour, and the official corporate-design tokens — applied consistently across every
screen and all four device classes. The design skill is the **visual** source of truth;
this file is the **written, binding** contract that ships with the repo. Where the two
disagree, the skill wins and this file is corrected in the same PR.

> New UI/visual decisions are recorded in an ADR and reflected here and in the design
> skill — see the governance rules in `CLAUDE.md`.

**Supported browsers — "Baseline 2025".** The UI targets at least **Chrome 122, Firefox 131 and
Safari / iOS 18.4** (owner decision D-16, [ADR-0239](../adr/0239-the-browser-baseline-is-baseline-2025-and-trusted-types-follow.md)).
Set methods, iterator helpers, the `popover` attribute and same-document view transitions are
available without a polyfill; `Promise.try`, `RegExp.escape` and `Float16Array` are not, and ESLint
rejects them. The script-side rule — the type check and ESLint at ES2025 — is REQ-FE-018.

## Requirements

### REQ-UI-001 — The DAS KARTELL design system is binding

Every UI change follows the design system. Do not invent colours, fonts, spacing, or
component shapes; reach for the published tokens in
[`colors_and_type.css`](../../.claude/skills/das-kartell-design/colors_and_type.css) and the
components in `krt-components.css`.

**Acceptance**

- [ ] No hard-coded colour/font/spacing values that duplicate an existing token.
- [ ] New components reuse the skill's component CSS rather than re-styling from scratch.
- [x] A token is referenced as `var(--token)`, never `var(--token, #hex)`: every token is defined
  on `:root` in `styles.css`, so a hex fallback never applies — it only hides a token that was
  renamed or dropped, painting the stale colour instead of failing visibly. 97 such fallbacks
  (84 in `personal-inventory.css`) were removed on 2026-09-22 (FE-MOD-01); every one named a token
  that resolves, so none of them changed a rendered colour.
- [x] Categorical palettes are tokens too. The price matrix's planet tints (header fill + body-cell
  stripe for the canonical planets and the twelve `PlanetColorResolver` hash buckets) are the
  `--krt-planet-<p>` / `--krt-planet-<p>-stripe` tokens on `:root` in `styles.css` (2026-09-23,
  unchanged colours); `materials-overview.css` holds no hex value at all. The design system
  declares the same 48 tokens in `colors_and_type.css` (krt-profit/design-system#4, submodule at
  `06e47e5`); all 48 values were compared and are identical, case aside.

- [x] Every fallback-free `var(--token)` in the stylesheets, the templates (inline and `th:style`
  values) and the scripts names a custom property that a stylesheet, a script or a template
  declares; an undefined one silently drops its declaration (the material demand search header was
  transparent on `--color-black`, `bank.css` named `--color-text`; both fixed, none left on
  2026-10-04). `CustomPropertyExistenceTest` fails the build on one; its allow-list is empty.
- [x] **A colour token's value appears only in its own declaration** (ADR-0243, 2026-10-04): an
  opaque copy is `var(--color-x)`, an alpha variant is
  `color-mix(in srgb, var(--color-x) N%, transparent)` — the token's colour at alpha N % — and a
  fully transparent one is `transparent`. A colour that is not a token stays a literal. 120
  hand-written copies were replaced with unchanged computed values.
- [x] **Page-level stacking goes through the `--z-*` scale** on `:root` in `styles.css` (ADR-0243,
  2026-10-04), bottom to top:

  | Token | Value | Used by |
  | --- | --- | --- |
  | `--z-popover` | 50 | overflow-menu and filter-popover panels |
  | `--z-popover-raised` | 60 | the inventory allocation popover; an open row overflow menu on the bank pages |
  | `--z-dropdown` | 99 | autocomplete list, searchable-select listbox |
  | `--z-sticky-head` | 900 | the mission detail's sticky head |
  | `--z-chart-scrollbar` | 998 | the org chart's fixed scrollbar |
  | `--z-footer` | 999 | footer |
  | `--z-header` | 1000 | header |
  | `--z-header-control` | 1001 | hamburger button |
  | `--z-floating` | 1100 | multi-select options, the phone filter sheet, inventory typeahead results |
  | `--z-floating-raised` | 1200 | blueprint import results |
  | `--z-drawer-scrim` | 1999 | drawer overlay |
  | `--z-drawer` | 2000 | drawer |
  | `--z-tabbar` | 2100 | phone tab bar |
  | `--z-modal` | 3000 | dialog overlay |
  | `--z-hint` | 4000 | SCU hint bubble |
  | `--z-status-pill` | 9000 | live-sync pill |
  | `--z-toast` | 9999 | notification toast |
  | `--z-confirm` | 10000 | confirm dialog |

  A literal `z-index` is allowed only below 50, where it orders the parts of one component (sticky
  table cells, a lifted overflow menu); a new layer that competes across the page is a new token in
  its place in the scale. `ZIndexScaleTest` pins the order and fails on a literal of 50 or more.

**Enforced by:** design review, `CustomPropertyExistenceTest`, `ColourTokenCopyTest`,
`ZIndexScaleTest` (each proven against a planted fixture), plus one lint rule:
`declaration-property-value-disallowed-list` in `frontend/.stylelintrc.json` and
`.stylelintrc.templates.json` fails `:frontend:lintCss` / `:frontend:lintCssInline` on a hex fallback
inside `var()` (2026-09-22), and an override applies `color-no-hex` to `materials-overview.css`
(2026-09-23). No gate checks that a colour which is not yet a token should become one.

### REQ-UI-002 — Brand colour & logo

The primary brand colour is **`#E77E23`** (orange, the house orange of das-kartell.org). The
logo appears **only** in this orange, white, or black. Text on orange is always dark
(`--color-bg-black`); white on orange reaches only 2.8:1 and is not allowed. Orange marks *action and identity* (CTAs, badges, headings),
never plain data values.

This action-hierarchy is **surface-agnostic** — it governs generated documents (the PDF
exports: handover protocol, bank statement, three-month report) exactly as it governs
screens. In those PDFs orange is reserved for the title, the section headers, a single
accent line under each table header, the balance-chart line, the thin page top-accent bar
and the logo; page surfaces and the data-cell grid stay neutral (canvas `#0F0F0F`, panel
`#282C30`, bar `#1A1D21` fills, `#36373B` hairlines), so the orange never overwhelms the
document.

**Acceptance**

- [ ] Logo renders only in `#E77E23` / white / black.
- [ ] The single filled-orange CTA marks the one primary action per context.
- [ ] Generated PDFs use orange only as a heading/identity accent (title, section headers,
  one table-header accent line, chart line, top bar, logo); table-head fills and data-cell
  grids are neutral dark/gray, never a full orange fill or an all-orange grid.

### REQ-UI-003 — Dark-only surfaces

The surfaces are the gray ladder of das-kartell.org, measured in the browser
([ADR-0294](../adr/0294-the-app-wears-the-measured-website-palette.md), which supersedes the colour
values of ADR-0292; its radius and component rules stand). Palette values are read from the live
site with `getComputedStyle` or the DevTools eyedropper, never from screenshots (HDR capture
lightens every value):

| Token | Value | Role |
| :-- | :-- | :-- |
| `--surface-canvas` | `#0F0F0F` | page background, page head |
| `--surface-bar` | `#1A1D21` | header, footer, sidebar, tab bars, table heads |
| `--surface-panel` | `#282C30` | cards, list panels, modals, toasts, dropdowns |
| `--surface-inset` | `#0F0F0F` | a box set into a panel |
| `--control-bg` | `#373E43` | every control fill and the table row hover |
| `--table-stripe-bg` | `--color-control` at 50 % (`#30353A` on the panel) | every second visible table row |

A tile never takes the fill of the surface it sits on: a `.card`, `.kpi-total` or `.kpi-card`
placed inside a panel (`.list-panel`, another card, a `.detail-pane`, a modal) is `.card--inset` /
`.kpi-total--inset` / `.kpi-card--inset` and takes the bar fill `#1A1D21`, so the tile stands
apart from its container; a page's own tile class inside a panel takes the same fill. A framed
`.data-table` inside a padded card or tab panel lays its rows on `--surface-inset`, so the rows, the
bar-filled head and the panel stay three distinct levels. Rows alternate by visible row: a row that
is `hidden` or carries `.detail-row` (an expandable detail under its row) does not count, so the
stripes still alternate. The stripe is the translucent `--table-stripe-bg`, so it sits halfway
between the row surface and the row hover on the panel and on an inset table alike. A lighter tile is not an option — `#36373B` and `#373E43` are reserved for
hairlines, `.btn-secondary` and controls.

`--color-bg-black` `#000000` is **no page background**: it is the text on orange and the base
of shadows and scrims. The page background is **flat** — no ambient pattern or texture; a
subtle radial **top bloom** of the primary fading to transparent is permitted on the login /
entry surfaces. `color-scheme: dark`; there is **no light theme**. The PWA `theme-color` is
the bar (`#1A1D21`), the manifest `background_color` the canvas (`#0F0F0F`).

> **Contrast note.** Muted **text** uses the accessible `--color-gray-2-text` tint per
> REQ-UI-006, not the canonical `--color-gray-2`, which is a control border colour.

### REQ-UI-004 — Typography

- **One typeface: Lato.** Body / UI default weight Light 300, Bold 700 for emphasis.
- **Headlines:** Lato too — distinguished by **weight (Bold 700)** + **UPPERCASE only** +
  letter-spacing `0.05em`, **not** by a separate display face. (History: Ethnocentric →
  Audiowide → consolidated to **Lato-only** in 2026-06; the Audiowide/Ethnocentric `@font-face`
  rules and font files were removed. `--font-headline` is kept as a Lato alias so existing
  `var(--font-headline)` references keep resolving.)
- The brand ships no monospace face; "mono" contexts use Lato with tabular figures.
  **Exception, owner decision 2026-09-27:** the Exchange API docs site's code blocks and Redoc
  code samples use the generic system `monospace`; its inline code stays Lato (REQ-XCH-002).

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
> names above. No stylesheet consumes the aliases any more: the last one, `operation-detail.css`'s
> `--color-dept-marine`, went with the operation redesign of 2026-10-03, so the three aliases can go
> in the phase-4 cleanup.

**Acceptance**

- [ ] Department tags/badges use the official token names with the exact hex values.

> **Amended by epic #692 (REQ-ORG-026):** these frozen Bereichsfarben are also applied to **org-chart
> nodes**, tinting each Bereich's sub-tree with its colour. This applies the existing tokens (no new
> hues); node text must keep ≥ 4.5:1 contrast (use the accessible `--color-*-text` tints where the hue
> would become small text).

### REQ-UI-006 — Semantic status colours

Status hues reuse Bereichsfarben values by appearance: danger `#A3000A`, success
`#239E33`, warning `#FFD23F`, info `#355DDC`. Treat them as status, not as the department.

**Accessible text tints.** The canonical danger/info/success hues are dark on the gray
surfaces and fail WCAG AA as small text. When a semantic
colour is the **text itself** (inline validation messages, status labels, price up/down),
use the lightened tints — `--color-danger-text` `#FF7A70`, `--color-info-text` `#8FB2F5`,
`--color-success-text` `#4CCB5C` (all ≥ 4.5:1 on the panel `#282C30`). Keep the canonical hues for fills,
borders and the brand Bereichsfarben tags. An invalid field additionally takes a red
hairline (`.input-error`) beside its `.field-error` message.

The same rule applies to the **muted grayscale**: Grau 2 (`--color-gray-2` `#8F9399`)
is the control border (4.5:1 on the panel) and fails WCAG AA as small text. When muted grey
**is the text itself** — `.text-muted`, secondary
labels, hints, placeholders, the quiet-danger button label — use `--color-gray-2-text`
`#A2A5A9` (≥ 5.6:1 on the panel and the bar, 7.7:1 on the canvas). Keep the canonical
`--color-gray-2` for control borders, scrollbar thumbs and disabled fills. A glyph that
sits in text — even an `aria-hidden` one — takes the tint too (DS-1, REQ-UI-027).

**Acceptance**

- [x] Semantic colour used as small text uses the matching `*-text` tint, not the dark
  canonical hue; the canonical hues stay on fills/borders/tags.
- [x] Muted grey used as small text uses `--color-gray-2-text`, not the canonical
  `--color-gray-2`; the canonical Grau 2 stays on borders, scrollbars and disabled fills.

Until 2026-09-25 nothing checked this, and 49 declarations broke it: the item badge in the order
list (#355DDC, 3.29:1, the `/orders` finding of the a11y smoke that appeared only once another test
had created an item order), the price column of the materials matrix, the outline danger button,
error texts, empty-state hints and more, most of them on elements no test had data to render. All
now take the tints. Two `aria-hidden` glyphs kept Grau 2 as decorative exceptions until
2026-10-03 — the unsorted-column indicator of the material demand table and the blueprint search
icon; DS-1 (REQ-UI-027) gave them the tint as well, so there is no exception left.

**Enforced by:** `AccessibleTextTintTest` (no stylesheet sets `color` to the danger, info, success or Grau 2
hue, and no script writes one into `style.color`; its selector exemption list for decorative
glyphs is empty) · `AccessibilitySmokeE2eTest` (axe WCAG A/AA on five pages; its report now names every
failing node with its colours and ratio)

### REQ-UI-007 — Visual style: sci-fi HUD with a 4 px radius

Sci-fi / space-organisation / technical-HUD aesthetic: geometric shapes (rings,
triangles), thin technical markers framing content. **Controls and surfaces take a 4 px
radius** (`--radius-sm`): buttons, inputs, selects, segmented controls, cards, panels, list
panels, modals, alerts, chips, badges, toasts and dropdowns; checkboxes and the switch knob
take 2 px. Pills stay for squadron badges and counters, circles for radio buttons and status
dots, and table cells, accent bars and gauges stay square (`--radius-none`). A rounded
container never sets `overflow: hidden`, so popovers and autocomplete lists can leave it. Every
control has a fill (`--control-bg`) and a visible border (`--control-border`, ≥ 3:1 to its
background). Department tags are **filled** in the unchanged department colour
(`.dept-tag--<slug>`), because several department hues are unreadable as text or as a thin
border on the gray surfaces. The orange bloom glow is the only "shadow" idiom.

### REQ-UI-008 — No native browser dialogs

**Never** use `confirm()`, `alert()`, `prompt()`, or any native browser dialog. Build
KRT-styled modals/toasts instead.

**Acceptance**

- [ ] No `confirm(` / `alert(` / `prompt(` calls in frontend JS.

The shared `krtFetch` mutation layer (REQ-FE-001..005,
[`frontend-ajax-mutations.md`](frontend-ajax-mutations.md)) surfaces every success / error /
optimistic-lock outcome through the KRT toast/confirm infrastructure precisely so this rule holds
app-wide; new AJAX call sites inherit it for free.

A `.notification-toast` carries no control and is transparent to the pointer
(`pointer-events: none`) for its whole life, so it never swallows a click on the control it covers
in the bottom-right corner. Until 2026-10-05 a shown toast took pointer events: for five seconds it
blocked what lay beneath it, and because it turned hit-testable 100 ms after it was appended, it
could take the second half of a click already in progress.

**Enforced by:** code/design review only for the native-dialog ban; the toast's pointer
transparency by `MaterialsCategoryEmptyStateInPlaceE2eTest`. The native-dialog rule is grep-able, but ESLint's `no-alert` is **not**
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

> [!important] One open/close contract, and every overlay is a native `<dialog>` (2026-09-23)
> **FE-SIMP-04.** `window.krtModal` (`krt-modal.js`, loaded globally) is the only way a dialog
> opens and closes: `krtModal.open(el | id, {focus?})` and `krtModal.close(el | id)`, plus
> `isOpen`, `topmost` and `layerRoot`. Ninety dialogs had carried their own copy of that code and
> 107 call sites wrote `overlay.style.display` directly; every one now calls the contract, and so do
> the shared `open-modal-display` / `close-modal-display` triggers and the mission page's
> `krtModalOpen` / `krtModalClose` (kept as aliases). What the contract guarantees for every dialog:
> the class state below; focus moves in on open (`[autofocus]`, else the first visible field, else
> the frame — never a button, so Enter can not hit „Löschen") and returns to the opening control on
> close; **Escape closes the topmost dialog** by clicking its own close control, so whatever the page
> does on close still runs (a dialog may opt out with `data-modal-static`). **A press that started
> inside the dialog frame never dismisses it:** a text selection dragged out of a field and released
> over the scrim makes the browser fire the `click` on the `<dialog>` itself, so `krt-modal.js`
> swallows a `click` on the overlay whose preceding `pointerdown` was inside `.krt-modal`, before any
> page's backdrop-click handler sees it.
>
> **FE-SIMP-04b.** Every `.krt-modal-overlay` is a `<dialog>` element, and `krtModal.open` shows it
> with `showModal()`: it goes to the top layer and the page behind it becomes inert — the focus trap
> and background lock that before existed only on the mission page and the org chart. The class
> stays on the element (live sync's "is a dialog open?" probe, the layout guard and the E2E suite key
> on it), `styles.css` undoes the user-agent dialog box so it is the same full-viewport scrim, and a
> dialog shown by its class alone (a server-rendered open dialog, the layout guard revealing every
> dialog) is still shown — and upgraded to a modal one by the contract's observer, which also closes
> the modal state of any dialog hidden without the contract, so no code path can leave an invisible
> dialog holding the page inert. Because the page behind an open dialog is inert, a toast, the KRT
> confirm and a programmatic download link are appended to `krtModal.layerRoot()` — the open dialog —
> and a toast is handed back to the body when its dialog closes.
>
> The paragraph below about inline `display` versus classes describes the defect the contract ended;
> the contract clears any inline `display` on both open and close.
>
> **Every dialog is rendered by `kernel/fragments/modal-wrapper.html` (2026-09-23, owner decision).** The
> 94 hand-written shells (and the bank movement fragment) now call `modal-wrapper :: modal`, so the
> overlay, the frame and the head exist once. The head is the design system's: an `<h2>` title and
> the ✕ `.krt-modal-close` labelled `general.a11y.closeModal`. Until then the shells had drifted
> (`<h2>` in 76, `<h3>` in 20; an ✕ in 74, an icon button in 22). The accessible name moved onto
> the `<dialog>` itself (`aria-labelledby` the heading when a script retitles it, else
> `aria-label`), instead of a `role="dialog"` frame inside a native dialog. Dialog ids stayed as
> they were. Five hooks moved: hangar's add/edit titles and the mission unit dialogs' initial ship
> now sit on their forms, and the Materialbörse, Materialgesuch and Leitung ✕ are bound by the
> page-scoped classes `mb-modal-close` / `mg-modal-close` / `leitung-modal-close`. The move also
> fixed five order-detail dialogs (Eintragung, Notiz, Übergabe, Item-Übergabe, Produktion): their ✕
> carried `close-modal-display` without a `data-modal-id`, so it closed nothing, and Escape, which
> clicks the ✕, closed nothing either.

The KRT HUD modal — `.krt-modal-overlay` scrim > `.krt-modal` frame (orange top edge + corner
brackets) > `.krt-modal-head` (title + close-X) — is the Thymeleaf fragment
`kernel/fragments/modal-wrapper.html :: modal`, and **every** dialog is rendered by it. The fragment
declares no signature; a call names what it needs: `modalId` and `titleKey` (required), `body`
(required: by convention a `<th:block th:ref="<modalId>-body">` inside the call, holding the
`.krt-modal-body` and `.krt-modal-foot`; in a fragment file it names its template,
`~{fragments/x :: <ref>}`), and optionally `variant` (a `.krt-modal--*` or page frame class),
`titleId` (heading id for a script that retitles; the dialog is then `aria-labelledby` it), `open`
(server-rendered open state), `closeTrigger` (the ✕'s `data-trigger`, default
`close-modal-display`; `''` when a page script binds the ✕ by `closeClass`), `closeClass` and
`closeId`. A condition or iteration goes on a `<th:block>` around the call. Modals open with
`data-trigger="open-modal-display"` and **close with the single standardized trigger
`data-trigger="close-modal-display"` + `data-modal-id`** (common-handlers.js), which the wrapper puts
on every ✕ that has no page handler. The older `data-modal-dismiss` convention survives only on
footer buttons of the `mission/mission-detail.html` dialogs (handled by `krt-modal.js`); new dialogs never
use it. The overlay's hidden default comes from
the **global** `.krt-modal-overlay { display:none }` in `styles.css` (loaded on every page;
`bank.css` duplicates it as defense-in-depth), so the fragment injects no inline style. A modal is
made visible by adding the `is-open` class (`.krt-modal-overlay.is-open { display:flex }`, in the
`utilities` cascade layer, so it wins over every component and page rule — REQ-UI-024; until
2026-10-04 the pair was named `krtm-modal-open` / `krtm-hidden`) — at runtime via `open-modal-display` (which toggles `classList`, not an
inline `style.display`) or a server-rendered `th:classappend`; the global default must never be
`display:flex`, or a page whose scoped stylesheet fails to load would render every closed modal open
on load (#1003 WebKit flake). A page script that **closes** a modal after an in-place AJAX write
(e.g. the bank confirm/reject modal on success, `bank.js`) must close it the **same** class-based
way — remove `is-open` (and add `is-hidden`), never write an inline `modal.style.display =
'none'`. An inline `display` outranks the non-`!important` class rule, so an inline close leaves a
stale inline style that the class-toggling `open-modal-display` cannot beat, and the modal can never
be re-opened without a full page reload (a bank staffer confirming a second request straight after
the first got a dead button). The inverse also holds: a modal a page script OPENS with an
inline `style.display = 'flex'` must not be closed through the class-only `close-modal-display`
alone, or the inline `display:flex` outranks `is-hidden` and the modal stays on screen (the
`delete-operation-modal` Cancel button). As a defensive backstop **both** shared handlers clear any
inline `display` on the modal — `open-modal-display` before showing it, `close-modal-display` before
hiding it — so the class always wins regardless of how the other side toggled visibility.

**Acceptance (FE-SIMP-04 / 04b, 2026-09-23)**

- [x] Every `.krt-modal-overlay` in the templates is a `<dialog>`.
- [x] No script shows or hides a dialog through `style.display` or its own class toggle; all go
  through `window.krtModal`.
- [x] An opened dialog is modal (`:modal`), takes focus, makes the page behind it unfocusable, keeps
  Tab inside it, closes on Escape, returns focus to its opener and opens again.
- [x] Every dialog is rendered by `modal-wrapper :: modal`; no other template carries
  `.krt-modal-overlay`, `.krt-modal`, `.krt-modal-head` or `.krt-modal-close`, and the head is an
  `<h2>` and an ✕ (2026-09-23).
- [x] A dialog renders only where its openers and the script that drives it do. The promotion
  admin pages in the admin's all-squadrons view, and the admin blueprint page before a member is
  picked, render none of their dialogs (2026-09-23; until then they rendered dead markup).
- [x] Every dialog on every page route and on a seeded mission, operation, order, refinery order and
  bank account opens modally with a title, an accessible name and a visible ✕, takes focus, and
  closes on Escape and on its ✕. A declared dialog the walk does not reach is listed with its reason.
- [x] A page script that binds a dialog's close by `closeClass` binds **every** control carrying
  that class (`querySelectorAll`), the ✕ in the head and a footer's „Abbrechen" alike — the wrapper
  renders the ✕ first, so a `querySelector` binds only the ✕ and leaves the footer button dead
  (2026-10-19, the three create dialogs of `/admin/mission-data`).

**Enforced by:** `SingleModalShapeTest` (`everyOverlayIsANativeDialog`,
`everyDialogIsRenderedByTheWrapper`), `ModalWrapperRenderTest`, `DialogA11yE2eTest`
(`everyDialogFollowsTheContract`), `AdminMissionDataCrudE2eTest`
(`cancelButtonClosesEachCreateDialog`), `PromotionAdminDialogsRenderWithTheirScriptMvcTest`,
`AdminPersonalBlueprintsPageControllerMvcTest`, `OrgChartKeyboardA11yE2eTest`,
`TouchClassLayoutE2eTest`

**Three sanctioned widths, and no fourth.** `.krt-modal` is 440 px (confirms and single-field
prompts), `.krt-modal--wide` is 600 px (form-heavy: 3+ stacked fields) and `.krt-modal--xwide` is
800 px, reserved for dialogs whose body carries a **table** rather than a form — the Auftrag
material/item pickers and the refinery store sheet. A dialog that needs a width none of these gives
is a signal to revisit its content, not to add a per-page `max-width`.

**The head's parts are styled.** `.krt-modal-close` carries the
`.close-sidebar-btn` treatment (its already-approved sibling — same job, an ✕ that dismisses a
surface), squared and floored to 44 px in the touch block, with `flex-shrink: 0` so a long German
title cannot squeeze it narrower than its glyph and a `:focus-visible` ring because it is the first
Tab stop inside a dialog. It had **no rule at all** until #1884 found it by measurement, and the
port would have moved the legacy dialogs onto something worse than the styled `.close-modal` they
came from. The head's title rule styles the wrapper's **`h2`**. It covered `h3` as well while the
hand-written shells used `h2` and the fragment emitted `h3` (an unstyled `h3` title fell through to
the global heading rule and rendered orange at the browser's `h3` size); since 2026-09-23 every
dialog's title is the wrapper's `h2` and the `h3` selector is gone. The close button's accessible
name is **`general.a11y.closeModal`**
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
browser (closing the CSS-injection residual). Static styling goes in a design-system class or a page-stylesheet rule;
the generated one-class-per-value `krtm-*` classes of `inline-migration.css` that first replaced the
inline values are retired (2026-10-04, REQ-UI-027 phase 4, guarded by `NoMigrationClassTest`).
Data-driven values use a class toggle (`th:classappend`, e.g. the modal `is-open` / `is-hidden`
pair) or, when the value
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
toggles the visibility of an element whose hidden state is a **class** (e.g. the scu-hint fragment
hides it with `is-hidden`), it must **toggle that
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
from 1280px up. The mission edit-mode form (formerly the Verwaltung tab) is the other multi-column
user of the widget; since 2026-10-03 it lays its date/time groups out in an auto-fill grid
(`.mission-time-grid`, columns of at least 18.5rem) rather than a `.form-row`, so it wraps by
construction. The rule is stated for the widget rather than scoped to the modal, so it also covers
a future narrower row there.

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
  `h2`.
- [ ] A swap container between `.krt-modal` and its `<form>` carries `.krt-modal-flow`, so the body
  still scrolls under the `90vh` cap.
- [ ] `.krt-modal-overlay` is `display:none` by default in the global `styles.css` (not only in a
  page-scoped stylesheet); a modal is shown by adding the `is-open` class (`display:flex`),
  never an inline `style.display`.
- [ ] A modal a script closes after an in-place AJAX write toggles its class (`is-open` off,
  `is-hidden` on), not an inline `style.display = 'none'`, so the next `open-modal-display` re-opens
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
  **44px**; collapse multi-column grids to one column; wide tables scroll horizontally. A phone is
  supported held portrait; a tablet in portrait **and** landscape, where it is wider than 1024px and
  its touch ergonomics come from `(pointer: coarse)`.
- **Desktop** (1024–1440px) and **Wide** (1440px+) — exploit space (docked sidebars,
  auto-fit card/dashboard grids) but cap long-form text at `max-width: 80ch` on `<p>`. From
  1440px `main` widens to `--content-max-data` (1600px).

**Page widths follow the page type** (owner decision 2026-10-19). Every page starts at the same
left edge — `main`'s horizontal padding is `--page-gutter` (32px, 24px up to 1024px, 16px up to
768px), and the mission page's full-bleed head, tabs and panes inset by the same token — and the
page head never runs wider than the content below it:

- **Data pages** (tables, Lager, Bank, Materialbörse, mission and order lists, the materials matrix,
  the profit calculation, the mission detail) use the full `main`: `--content-max` (1200px), from
  1440px `--content-max-data` (1600px).
- **Form and text pages** (profile, admin settings, admin announcement, `orders/create`,
  `missions/new`, `inventory/input`, refinery create, impressum, privacy, terms, licences) mark
  `main` with `.page--narrow`: every direct child — page head, alerts, the form or text column —
  stops at `--content-max-form` (760px), left-aligned. A page with a fixed side column widens that
  cap through `--page-column` (refinery create: form 760px + its side panel); the profile caps its
  wrapper at the section nav plus 760px.
- **A form inside a data page** (the material-alias edit and create cards, the admin member pickers
  of personal inventory and personal blueprints, the default-blueprint search) carries `.form-width`
  and stops at 760px too.
- **Alerts** stop at `--measure` (80ch) on every page, so a notice never reads as one line across a
  1600px table.

`.form-layout` and `.form-width` are `border-box`, so the 760px is the outer edge of the card.

**Breakpoints are only the class boundaries** (DS-11, ADR-0242, amended 2026-10-03): a media query
uses `(width <= 768px)`, `(width <= 1024px)` or `(width >= 1440px)` — or a range between two of them —
and, for the pinned sidebar of REQ-UI-026 only, `(width >= 1280px)`; nothing else (owner decision 24). Stylelint's `media-feature-name-value-allowed-list` enforces it in both
configurations, for `width`, `min-width` and `max-width`. A component that has to react to its own
width uses a container query. The tokens `--bp-phone-max`, `--bp-tablet-max` and `--bp-wide-min`
document the values (a custom property cannot be used inside `@media`). The fifteen earlier values
moved to the next boundary — 460–760 → 768, 820–980 → 1024, 1600 and 1800 → 1440 — and a script's
`matchMedia` moves with the stylesheet it mirrors. Until 2026-10-03 the Desktop class ended at 1600px.

**Dense row actions are an explicit exception at 32px.** The two compact variants `.btn-xs`
and `.btn-icon` — the *repeated* per-row actions of a dense table / tree action cluster
(REQ-UI-022) — carry a **32px** minimum hit area on the mouse classes rather than the 44px floor
above; on the touch classes they take the full floor since 2026-10-19 (sixth round below). Density in those clusters is what keeps a wide
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
Materialbörse / Preis-Übersicht / Beförderung panels, the org-chart proxy
scrollbar's `bottom`, the `bottom` of the sticky form actions (`.form-actions--sticky`) and of the
operation payout table's pinned sum row, and the mission-detail padding — would otherwise reserve
space for a footer that is not there.

**Two amendments to the dense-action exception, owner-approved 2026-09-13** after the first
measured sweep of the touch classes found both:

- **`.btn-xs2` is NOT exempt.** It is a page-local 32px variant declared in the page stylesheet of
  `mission/mission-detail.html` (an inline `<style>` block until FE-PERF-02, now
  `css/pages/mission/mission-detail.css`; `operation/operation-detail.html` stopped using it on 2026-10-03), and three
  of its instances are *form* actions — „Ziel hinzufügen", „Schritt hinzufügen" and „Frequenz
  hinzufügen" in the mission's edit mode (the create form's own add buttons are plain `.btn`). The
  rule above is
  explicit that a form button keeps the floor, so the whole variant is raised to 44px on the touch
  classes and keeps its density on desktop. The override is written `.btn.btn-xs2` because the
  page's inline rule is read *after* `styles.css`: at equal specificity the page would win and the
  fix would be silently inert — the same trap this requirement already records for `.btn.btn-xs`.
- **`.master-row` IS exempt**, at 32px. The blueprint list rows on `/personal-inventory/blueprints`
  measured 33px; they are a scan-and-tap list where density is the point, and were ruled equivalent
  to a repeated row action rather than a standalone control. Since 2026-10-03 those rows declare
  `min-height: 44px` themselves (`.krt-bp-md .master-row`, website overhaul phase 3); the exemption
  stays for other master lists.

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
- **`.demand-sort-btn` and `.sort-header` are NOT exempt** and are raised to 44px. A column-header sort control is one
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
filling a gap: `.btn.btn-xs2` and `input.item-checkbox` must out-specify a page-local rule (a page
stylesheet under `css/pages/`, formerly an inline `<style>`), so since REQ-UI-024 both sit in the
page-layer block at the end of `styles.css`, where specificity against the page stylesheets decides
*(2026-09-23: `input.item-checkbox` was first left in `components`, where the page layer's 20px
square beat it at every width; the group checkbox on `/inventory/my` measured 26px on touch classes
until it moved)*, and the dismiss-button group (`.close-sidebar-btn`,
`.krt-modal-close`) declares its square and its centring. *(2026-09-22: that group used to exist
because `.close-modal` was a `<span>` no element selector reaches; the span went with the legacy
dialog shapes in #1891, and the explicit `min-height` is kept only because it is harmless.)*

Declared `--touch-target-dense` consumers today: `.btn.btn-xs`, `.btn-icon` (`styles.css`),
`input.item-checkbox` (touch block), `.master-row` and `.krt-bp-imp-suggestion`
(`personal-inventory.css`), `.matrix-flag`, `.bank-row-toggle` and `.bank-chart-range-btn`
(`bank.css`), and `.pa-sort-controls .pa-sort-btn` (`promotion-admin.css`).

The same inversion applies to **which rows wrap on the phone class**: a row that directly contains a
control or a link wraps, matched structurally with `:has()` rather than by class name. Enumeration
was hopeless there for an additional reason — many rows then carried a generated, content-hashed
class from `inline-migration.css` (retired 2026-10-04).

**A fifth round, 2026-10-17: a visual walk of every page, modal and the drawer** on the phone and
the tablet class, with the guard green throughout. Each finding was a defect a person sees at once and
a geometric check had no rule for:

- **A checkbox or radio is a 24px box, and its label carries the 44px.** The default floor reached
  `input`, so on the touch classes every checkbox grew into a 44px-tall empty slab and every radio
  into a 44px disc, and a `.krt-switch` lost its track. The control is now `1.5rem` square with no
  floor of its own, and a label that wraps it or follows it is an `inline-flex` row of at least
  `--touch-target` — the effective hit area this requirement already counts. A checkbox with no
  label is therefore measured on its own box and fails, which is the point.
- **A text-entry control renders at 16px or more on the touch classes.** iOS zooms the whole page in
  when a field below 16px takes focus and does not zoom back out. Page rules set fields as small as
  `0.78rem`, so the floor is one declaration in the touch block that wins over them
  (`max(1rem, 16px) !important`, the same kind of platform override as the reduced-motion block).
  Disabling the zoom with `maximum-scale` instead would also disable pinch-zoom on Android, which
  WCAG 1.4.4 forbids.
- **A `.krt-segmented--block` wraps on the phone class.** Its items shared one row with `min-width:
  0`, so a word wider than its share — „Abgeschlossen" in the mission status — painted over its
  neighbour. On the phone the items keep their content width and wrap, separated by a 1px hairline
  gap.
- **Floating elements sit above the tab bar.** The live-sync pill and the toast were placed from the
  viewport bottom and covered three of the five tabs; both now add `--krt-tabbar-height` (and the
  pill `--krt-footer-height`).
- **A dialog is capped at `90dvh`**, so a browser toolbar that slides in cannot push its foot below
  the visible area.
- **The tablet is fully usable in both orientations; the phone in portrait** (owner decision
  2026-10-17). A tablet held landscape is 1080–1366px wide, above the Tablet class, and so it used
  to get the mouse layout's control sizes and iOS's focus zoom. The touch ergonomics — the 44px
  default floor, the checkbox and label sizing, the 16px text entry, the dense exemptions — now apply
  under `(width <= 1024px), (pointer: coarse)`: by width for a narrow window, and by the input device
  for every touch screen whatever its width. Layout (columns, the tab bar, the drawer) still follows
  the width classes only. The sweep measures a landscape tablet at 1180×820 as its own touch class.
- **Rows that must not wrap say so.** The phone rule that wraps every row holding a control also
  wrapped the dialog head (the ✕ dropped below a long title) and `.check-row` (a long label dropped
  below its checkbox). Both declare `flex-wrap: nowrap`, and the part beside the control shrinks and
  wraps inside itself. Conversely a `.flex-1` / `.flex-2` child of a `.flex-gap-*` row takes a 12rem
  basis on the phone, so two pickers that cannot share a row each take one.
- **Headings keep their order on the phone.** An unstyled `h2` / `h3` defaulted to 24px and outgrew
  the 22.4px page title; on the phone they are 1.2rem and 1.05rem. A heading hyphenates only with five
  letters before and six after the break (`hyphenate-limit-chars: 12 5 6`), so a compound breaks at its
  joint („Benachrichtigungs-regeln") rather than one syllable from the end.
- **A name is not cut where a touch cannot reveal it.** A truncated name with a `title` tooltip is
  unreadable on a touch screen; the price matrix's terminal and material names wrap onto a second
  line instead of ending in an ellipsis.
- **A form grid inside a narrow dialog is one column.** `.krt-modal` is a size container; below
  34rem its `.form-grid` collapses to a single column, so a 440px dialog no longer splits its fields
  into two 190px columns. Field labels of a `.kv-list` hyphenate instead of breaking a word without a
  hyphen („Handelsbeauftrag / ter").
- **A separator stays with what it separates.** The `·` between the cells of a stacked table row
  sits in the gap after a cell, positioned against the cell rather than flowing as text, so it can
  never begin a line or wrap away on its own; a cell that holds a block (a progress bar, a list), an
  empty cell and a `.cell-block` cell take no separator, and neither does a cell whose next cell
  starts a new line (`stack-line-end`, measured on the page and again whenever a stacked table
  changes size, such as when a web font arrives after the rows). A page that loads its rows after the
  document (the profit calculation) marks their region `aria-busy="true"` while it loads, and
  `TouchClassLayoutE2eTest` measures a page only once nothing in `main` is busy. The status bar of a stacked row runs
  along the card's edge, not over its first cell. A list rendered inline puts its comma after
  an item, never before the next one. A count beside a name (`(1)`) never wraps on its own.
- **A tree table fits the touch classes.** Up to 1024px or on a coarse pointer a `.tree-table`
  takes no more width than its columns' minimum and a row's name wraps instead of truncating, so
  the first column and its chips never slide out of the scroll area. When a heading wraps, every
  cell of the head row stretches to the tallest one and centres its text, so the head's background
  and underline run level across all columns.
- **A chip never shrinks below its text.** In a flex row whose children may shrink, the chip
  declares `flex-shrink: 0` and the name beside it gives way.
- **A required star is drawn once.** The automatic star of a required field steps back when the label
  already carries its own `.req`.
- **Mouse-only hints stay off touch screens.** „Strg+Klick für Mehrfachauswahl" is hidden under
  `(pointer: coarse)`, where the browser shows its own multi-pick.
- **The sign-in form follows the same 16px rule** (`krt-login-v3.css`), since it is the first screen
  of the installed app.
- **A scrolling tab strip shows its current tab.** On `/purchasing/report` the active tab was the
  fourth of four and loaded off-screen; `common-handlers.js` scrolls the strip, never the page, so
  the current `.tab-nav` or `.krt-segmented` item is in view.

**A sixth round, 2026-10-19: row buttons and tab strips on touch** (owner decision, after the
design-system walk of every page on all six device classes):

- **`.btn-xs` and `.btn-icon` keep their 32px density on desktop only.** On the touch classes —
  `(width <= 1024px), (pointer: coarse)` — the touch block raises `.btn.btn-xs` and `.btn.btn-icon`
  to `--touch-target` (an icon button also to 44px wide). The walk measured confirm/reject on bank
  request cards, hide and home-location on `/admin/locations`, the edit and delete icons on
  `/admin/mission-data`, the purchasing, supply and refinery row actions, the „Alles" buttons of the
  stolen-mark and Materialbörse dialogs and the markdown toolbar at 30–34px, which is too small to
  hit reliably with a finger. The other dense consumers (`.item-checkbox`, `.matrix-flag`,
  `.bank-row-toggle`, `.master-row`, `.pa-sort-btn`, …) keep their exemption. The guard counts a
  control as dense only while its computed `min-height` is below 44px, so a dense declaration that
  the touch block overrides no longer lowers the floor.
- **A field hint („?") has a 44px hit area on touch.** `.scu-hint` stays a 17px glyph and gains a
  centred, transparent, positioned `::before` of `--touch-target`.
- **A scrolling tab strip shows that it scrolls.** A `.tab-nav` whose tabs do not fit fades the edge
  behind which tabs are hidden (`mask-image`, `--space-8` wide) and drops the fade at the scroll end.
  `kernel/krt-tab-scroll-cue.js` sets `is-clipped-start` / `is-clipped-end` from the scroll position
  and a `ResizeObserver`; a scroll-driven animation would need no script but is not in Safari 18.4
  or Firefox, both inside the browser floor. The guard fails a strip whose classes or mask disagree
  with its scroll position.

**Acceptance**

- [ ] Verified at all four breakpoints; interactive targets have an **effective hit area** (own box,
  a positioned `::before` / `::after` overlay, or the `<label>` that activates them) ≥ 44px on touch
  classes, except the dense in-row controls listed above (`.master-row`, `.item-checkbox`,
  `.matrix-flag`, `.bank-row-toggle`, `.pa-sort-btn`, …; `.btn-xs` and `.btn-icon` on desktop only), which are ≥
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
- [ ] **The page-pattern rules of REQ-UI-027 hold on every swept page** (2026-10-04): on the phone
  class no visible `.card` is wider inside than its own box (`scrollWidth` ≤ `clientWidth`; a
  `.table-responsive` scroller inside the card clips its own overflow and is allowed to scroll, the
  card is not); at every class the page head's primary action — the visible `page-head-primary`, or
  else the visible `.btn--cta` in `.page-head .page-actions` — lies inside the first viewport above
  the phone tab bar; a `.page-actions` row shows at most one visible `.btn--cta`; no `.hud-box`
  sits inside another (hidden ones included); and no visible element draws its own text in
  `rgb(100, 100, 100)`, the retired `--color-gray-2` that failed contrast as text (DS-1). A finding
  names the card's widest box outside a nested scroller, the primary's position against the
  viewport, the crowded buttons, both boxes, or the gray element.
- [ ] A tablet is fully usable held portrait and landscape: at 1180×820 with touch the 44px floor
  and the 16px text entry hold exactly as at 1024px.
- [ ] On the touch classes no text-entry control (`input` other than a toggle, button or file
  picker, `select`, `textarea`) renders its text below 16px.
- [ ] A checkbox or radio keeps its small square or disc on every class; its label, not the box,
  reaches the 44px floor.
- [ ] No floating element (toast, live-sync pill) overlaps the phone tab bar.
- [ ] **A hidden overlay takes no room.** A hidden tooltip bubble still counts toward the page's
  scrollable width, and on a phone it widens the layout viewport: the hidden `.scu-hint__bubble`
  beside a toolbar's right edge widened `/personal-inventory/blueprints` to 487 px at 375 px and
  scrolled it 104 px sideways at 810 px. The hidden bubble is scaled to nothing
  (`transform: … scale(0)`, restored at once on hover or focus and only after the fade-out).

**Enforced by:** [ADR-0172](../adr/0172-the-phone-class-gets-its-own-layout-contract.md) · `TouchClassLayoutE2eTest` (six device classes — 375×812, 810×1080, 1024×768 and the landscape tablet 1180×820 with touch, 1280×800 and 1600×900 without — over the page routes of the shared `FrontendPageRoutes.PAGES` catalogue, plus a real detail view per
list and every modal on the page — 96 `.krt-modal-overlay` roots, the one shape `MODAL_SHAPES` holds
since ADR-0177: page-level overflow, cut-off elements, unscrollable tables, control floors, footer
behaviour, chrome share, and the five page-pattern checks of REQ-UI-027 — cards scrolling sideways
on the phone, the head's primary action outside the first viewport, more than one `.btn--cta` in
`.page-actions`, nested `.hud-box`es, text in `rgb(100, 100, 100)` — with a full-page screenshot per
page and class and a per-page `patterns:` line in the report) ·
`PageRouteCatalogueTest` (the route list is no longer hand-maintained on trust: it asks the
dispatcher for every mapping it knows and fails when a variable-free `GET` route is in neither
`PAGES` nor `NOT_PAGES`, so a page added next month is swept on the day it is added — and it runs
in `check`, not in the stack-bound `e2e` suite) + code/design
review for the rest · **Code:** `static/css/kernel/styles.css` (`.btn`, `.btn.btn-xs`, `.btn.btn-icon`,
`--touch-target-dense`, the `width <= 1024px` touch block and the `width <= 768px` block),
`static/css/bank/bank.css` (`.matrix-flag`, `.bank-row-toggle`), `static/css/personalinventory/personal-inventory.css`
(`.master-row`), `static/css/promotion/promotion-admin.css` (`.pa-sort-btn`), `static/js/kernel/sidebar.js`
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

The recurring CRUD actions use one fixed glyph from the in-house sprite (`kernel/fragments/icons.html`
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

**Enforced by:** code/design review · **Code:** `kernel/fragments/icons.html`, `static/css/kernel/styles.css`
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

**Enforced by:** code/design review · **Code:** `static/js/kernel/krt-searchable-select.js`,
`static/js/inventory/inventory-admin.js`, `static/js/inventory/inventory-my.js`,
`static/css/kernel/styles.css` (`.krt-combobox__listbox`, `.krt-combobox__listbox--above`, `.assoc-pop`,
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
  queues whose server default is a **subset** (orders: OPEN+IN_PROGRESS) store the
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
bank request-queue/dashboard/org-layout modules and the grouping toggles): Materialbörse (view +
scope, and per view min quality, min amount, sort and „Ohne gestohlene"; key
`materialboerse_filters`), Mein Lager + Globales Lager (all multi-selects, min quality,
„Gestohlen", the „Alle · Persönlich · Gemeinsam" segment stored as the two personal flags, per
view), Raffinerie (the segment „Läuft · Abholbereit · Abgeschlossen · Alle" + only-mine, key
`refinery_orders_filter`; a stored status list of the former checkboxes maps onto a segment),
Profitberechnung (ship + systems), Missionen/Operationen (the period segment „Kommend · Vergangen ·
Alle"; a stored `showPast` migrates), Aufträge scope „Meine · Zu bearbeiten · Alle" (`orders_scope_filter`,
REQ-ORDERS-040), Benachrichtigungen „Ungelesen · Alle" (`notifications_filter`, REQ-NOTIF-022),
Meine Bewertungen
(only-open), Persönliche Blueprints (refinery + craftable toggles), Beförderung verwalten
(filters/sort/collapse, migrated sessionStorage → localStorage), Bank-Freigaben (view + account /
employee selection), Bank-Kontodetail chart range (both detail pages) and the Kontodetail tab (both
detail pages: `bank_account_tab_<user>`, `org_unit_bank_account_tab_<user>`), the bank request
queue's status segment (one stored segment since 2026-10-03; a stored checkbox list migrates onto
it, REQ-BANK-023),
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

The app is a Star Citizen fan project and uses Fan Kit assets, so **three CIG documents bind it, and
they apply cumulatively**:

|             Document             |                                         Requires                                         |
|----------------------------------|------------------------------------------------------------------------------------------|
| Fan Kit **Guidelines** §2/§2b    | the "Made By The Community" logo and the short **trademark notice** (`fankit.trademark`) |
| Fankit **Agreement** clause 2(g) | a separate, longer **non-affiliation notice** (`fankit.disclaimer`)                      |
| Fankit and Fandom **FAQ**, "Fan Sites" | the **fan site notice** (`fankit.fansite`), openly visible, and a **link to the official site** |

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
> amended first if it is ever taken. The discontinued Android app recorded the same finding in its
> `REQ-APP-SET-007` (`docs/specs/settings.md` in the `basetool-android` repository).

Section 2b accepts three placements on a website — the home page, an always-visible navigation
area, or both. The Basetool uses the **home page**: the band renders at the end of `dashboard/index.html`'s
`<main>`.

It deliberately no longer sits in the global footer. As a full-width first row it cost every page a
3.25 rem band of a `position: fixed` footer that overlays content, which is worst on the phone/small
device classes of REQ-UI-009. Scrolling with the home page costs no viewport height at all.

Binding details:

- **All three elements ship as one fragment** (`shell/fragments/fankit.html`) and are included together.
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
- **The FAQ fan site notice is on every page** ([ADR-0296](../adr/0296-every-page-carries-the-fan-site-notice-of-the-cig-faq.md)).
  `fankit.fansite` quotes the FAQ verbatim, English in every bundle, and renders with a link to
  `https://robertsspaceindustries.com/` in the footer's full-width `.krt-footer-fansite` row and, on
  the phone where the tab bar hides the footer, in the drawer's `.nav-legal` group. The public home
  page shows it in the greeting as well. It is never smaller than the other notices (`--fs-sm`).
  Checked against the FAQ revision of 2026-09-02, which writes "fan site" as two words and has no
  trademark sentence.
- **The band links the official site** (`.krt-fankit-official`) as its fourth element.
- **No CIG artwork outside the Fan Kit ships.** Manufacturer logos, the Star Citizen logo or any
  other CIG artwork appear only as unmodified Fan Kit files and only where a page uses them;
  redrawn or unused copies are deleted. `FanKitComplianceMvcTest` fails on an SVG under
  `META-INF/resources/images/` other than the Basetool's own `pattern*.svg`.
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
- [x] An anonymous `GET /` renders the FAQ fan site notice and a link to robertsspaceindustries.com;
  the footer of every layout page carries both.
- [x] `fankit.fansite` is byte-identical in the DE, EN and default bundles.
- [x] No manufacturer logo or other non-Fan-Kit CIG artwork ships under `META-INF/resources/images/`.

**Enforced by:** `FanKitComplianceMvcTest` · design review against the Fan Kit Guidelines, the
Fankit Agreement and the Fankit and Fandom FAQ ·
**Code:** `shell/fragments/fankit.html`, `dashboard/index.html`, `dashboard/landing.html`,
`kernel/fragments/footer.html`, `kernel/fragments/sidebar.html`, `.krt-fankit-*`,
`.krt-footer-fansite` and `.nav-legal-fansite` in `styles.css`, `fankit.*` in the three message
bundles · **Related:** REQ-UI-009.

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
| Browser tab                                                                   | `logos/basetool-favicon.svg` + `-32.png` + `-16.png`; `/favicon.ico`     |
| iOS home screen / pinned site                                                 | `logos/basetool-appicon-180.png`                                         |
| Keycloak login theme                                                          | `img/basetool-logo.svg`                                                  |
| Exchange API docs site (GitHub Pages, REQ-XCH-002) — header + browser tab     | `assets/logos/basetool-*`, copied from the frontend at build time        |
| Generated PDF exports (handover protocol, bank statement, three-month report) | `krt.png` / `krt.svg` — **org mark** in `#E77E23`                        |
| Fan Kit compliance band                                                       | `images/made-by-the-community.png` — CIG artwork, untouched (REQ-UI-018) |

Binding details:

- **The favicon is a reduced glyph, not a scaled logo.** Below ~32 px the ring and the star stop
  carrying, so the favicon drops both and keeps wedge + bars. The PNG rasters ship at their exact
  pixel sizes; a browser resampling one large raster into a 16 px tab slot turns the wedge to mush.
  Order in `<head>`: SVG first (what every current engine picks), PNG fallbacks after.
- **`apple-touch-icon` uses the opaque app icon at 180 px**, scaled from the 512 px tile, not the
  favicon glyph — iOS composites its own rounded mask and ignores transparency, so a flat glyph
  would land on a white plate. `/favicon.ico` (16/32/48/64 px) is built from the 64 px favicon
  raster for clients that ask for it by convention.
- **The header mark is decorative** (`alt=""`). The `.logo-text` wordmark sits inside the same link
  and already names the app at every breakpoint; giving the image an alt as well makes a screen
  reader announce the name twice. The same holds for the Keycloak login, where the `<h1>` does it.
- **`height="50"` on the header mark is the whole box, padding included.** The SVG's 240×240
  viewBox carries roughly 30 % breathing room by design — that is the mark's specified presentation,
  not slack to be cropped. Do not fork a tightened copy of the asset to "fill" the box.
- **The mark renders only in `#E77E23`, white or black** (REQ-UI-002). `basetool-logo-white.svg` is
  the white variant for print/overlay; no other recolour exists.
- The sibling app draws from the same family — the SC Extractor's desktop icon from
  `basetool-extractor-*`. Its integration is governed in its own repo; only the shared source of
  truth (the design skill) is common.

**Acceptance**

- [ ] Every rendered page carries `logos/basetool-logo.svg` in the header brand link.
- [ ] `<head>` links the SVG favicon, both PNG rasters and the touch icon.
- [ ] No app page references `logos/krt.webp` or `logos/krt-favicon.webp`.
- [ ] Every referenced brand asset actually ships under `META-INF/resources/logos/`.
- [ ] `META-INF/resources/logos/` ships the `basetool-*` family and nothing else. FE-PERF-04
  (2026-09-22) deleted seven unreferenced public files there — `krt.webp`, `krt-favicon.webp`,
  `sc.png` (515 KB), `gplv3.svg`, `java.svg`, `postgres.svg`, `spring.svg`, about 570 KB in all;
  the org-branded PDF exports read the backend's own `krt.png`, not these.

**Enforced by:** `BrandMarkRenderMvcTest` · **Code:** `kernel/fragments/head.html`, the `.brand` link in
every page template, `META-INF/resources/logos/basetool-*`,
`keycloak-theme/krt-theme/login/login.ftl` · **Related:** REQ-UI-002, REQ-UI-018.

### REQ-UI-020 — The web app is installable, and installs without a service worker

The tool is installable to a phone or tablet home screen as a standalone app. There is no native
client, so a home-screen web app is the whole mobile story: the Android app is discontinued
(ADR-0268), and iPhone and iPad never had one — the app's channel (a signed artifact from GitHub
Releases) has no Apple equivalent a fan project can reach. The analysis behind the Apple half is
`docs/APPLE_PLATFORM_FEASIBILITY.md` in the `basetool-android` repository; the decision is
[ADR-0164](../adr/0164-an-installable-web-app-without-a-service-worker.md).

Binding surface:

|            Piece             |                                                    What it is                                                    |
|------------------------------|------------------------------------------------------------------------------------------------------------------|
| `/manifest.webmanifest`      | Rendered by `WebAppManifestController`, media type `application/manifest+json`, **no `produces` on the mapping** |
| `<link rel="manifest">`      | In `kernel/fragments/head.html`, **with `?locale=` and no `crossorigin`** — the fetch must stay anonymous               |
| `theme-color`                | `#1a1d21` — the header bar fill, not the canvas page background                                                  |
| `mobile-web-app-capable`     | Standard spelling, plus the `apple-` prefixed legacy one; **both** ship                                          |
| `apple-mobile-web-app-title` | From `pwa.short_name` — short, and the **same in both locales**, because the product name is a proper noun       |
| `apple-touch-icon`           | Shipped by REQ-UI-019 (180 px); the manifest lists the 192 px and 512 px tiles at their **content-hashed** URLs  |

Binding details:

- **No service worker, by decision.** A worker caching navigations would copy member data — balances,
  rosters, stock — into a second store outside every path that clears the first, while the backend
  marks those reads `no-store` (REQ-SEC-031) precisely so they are not copied. iOS needs no worker
  for „Zum Home-Bildschirm". **The cost is accepted and stated:** Chromium requires a fetch-handling
  worker before it offers its own install prompt, so on Android and desktop the app installs only
  through the browser menu. Adding a worker later is an ADR, not a refactor.
- **The manifest is a controller, not a file under `static/`.** Four of its properties belong to the
  response rather than to a file: the localised `description`, the content-hashed icon URL, the
  media type Spring's resource handler does not know, and `200`-never-`302`. The last needs the path
  in the `SecurityConfig` allow-list and in `PublicPaths`, the trap the Android App Links descriptor
  fell into (REQ-SEC-038, superseded).
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
- **The viewport is `viewport-fit=cover`, and the chrome keeps clear of the safe areas.** Without it
  iOS reports every `env(safe-area-inset-*)` as `0` in the installed app, so the phone tab bar's
  labels sat under the home indicator and a landscape phone drew content under the notch. The body
  pads left and right by the side insets; the header adds the top inset, and the tab bar, the pinned
  footer and the drawer pad by the insets of the edges they touch.

**Acceptance**

- [ ] `GET /manifest.webmanifest` answers `200` as `application/manifest+json` to an **anonymous**
  request, with no redirect, **for every `Accept` header**.
- [ ] Its `name`, `short_name` and `description` come from the bundles and follow `?locale=`; an
  unsupported or malformed value renders German with `"lang": "de"`, never a mismatched pair.
- [ ] `display` is `standalone`; `start_url`, `scope` and `id` are the application root.
- [ ] `icons` holds **exactly two** entries: the opaque 192 px and 512 px tiles at their
  **content-hashed** URLs, `purpose` `any`, never `maskable`.
- [ ] `Cache-Control: max-age=3600, public` and **no** `Vary`, because the body depends only on the
  URL.
- [ ] The rendered page carries the manifest link **with `?locale=` and without any `crossorigin`
  attribute**, the `theme-color` meta matching both the controller constant **and**
  `--color-surface-input` (the bar) in `styles.css`, the manifest `background_color` matching
  `--color-canvas`, and both standalone hints.
- [ ] The viewport meta carries `viewport-fit=cover`, and no fixed chrome element lies inside a
  safe-area inset.
- [ ] No service worker is registered anywhere in the frontend.
- [ ] The path is probed from outside by `blackbox-public-surface` with `follow_redirects: false`,
  and `EdgePublicSurfaceNot200` alerts when it stops answering `200` (REQ-OBS-012).

**Enforced by:** `WebAppManifestControllerTest` (including the no-service-worker sweep and the
stylesheet colour pin), `SecurityConfigStaticAssetPermitAllTest` (the no-redirect contract),
`AnonymousSurfaceSweepMvcTest` (the REQ-SEC-052 registry) · **Code:** `WebAppManifestController`,
`kernel/fragments/head.html`, `SecurityConfig`, `RequestLoggingFilter`, `pwa.*` in the three message
bundles, `monitoring/blackbox/blackbox.yml` + `monitoring/prometheus/prometheus.yml` +
`monitoring/prometheus/alerts/infrastructure.yml` ·
**Related:** REQ-UI-019 (the icon family), REQ-SEC-031 (`no-store`), REQ-SEC-052 (the public-path
table), REQ-OBS-012 (the edge posture probes).

### REQ-UI-021 — The shipped third-party components are listed on a public „Open-Source-Lizenzen“ page

The Basetool is GPL-3.0-only and its images are distributed, so every third-party component inside
them is owed a notice, and every library among them must be GPL-3.0-compatible. Both halves are
decided in [ADR-0197](../adr/0197-shipped-dependencies-pass-a-gpl-compatible-licence-gate-and-are-listed-on-a-public-page.md);
this requirement is the user-facing one. The discontinued Android app had the same screen
(`REQ-APP-SET-006` in `basetool-android`), and the web page followed it.

- **Where.** `/licenses`, titled „Open-Source-Lizenzen" / "Open-source licences". Linked from the
  footer directly after „Nutzungsbedingungen", from the legal links at the end of the phone menu
  sheet (REQ-UI-026) and from the landing page's legal links. **Public** (`REQ-SEC-052`) and a legal page for both session gates.
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
  identifier its text actually is (`OssLicensePolicy.urlAliases` in `build-logic`), the same table the
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

### REQ-UI-023 — A page ships no developer text and no inline page CSS

A rendered page carries what the browser needs and nothing a developer wrote for another developer.

- **Templates emit no plain HTML comments** (ADR-0214). A plain `<!-- … -->` is sent with every
  response. A short technical comment is a parser-level `<!--/* … */-->` block (the engine drops it
  at parse time); the star-slash pair may not appear inside it — it closes the block early and
  renders the rest.
- **Page CSS lives in `static/css/pages/<domain>/<page>.css`**, linked by a `<link rel="stylesheet">` in the
  place the page's `<style>` block used to stand. A template carries no `<style>` element. Each file
  belongs to exactly one template. Its rules sit in the `page` cascade layer (REQ-UI-024), so where
  the link stands no longer decides what it beats. Until 2026-09-23 it did: the link kept the page's
  load order.
- **The icon sprite stays inline, by measurement.** After the two rules above it is 16.2 KB raw and
  2.4 KB gzipped per page; a separate file would save those 2.4 KB per navigation at the cost of a
  second request before the first icon paints, a content-hashed URL in every `<use href>` of the
  templates and of eight scripts, and a re-measurement of every icon. Revisit if the sprite grows.

Measured on 2026-09-23 with the MockMvc render of five pages (admin, empty backend), before → after:

| Page | Raw | gzip |
| --- | --- | --- |
| `/hangar` | 91.7 KB → 58.3 KB | 23.0 KB → 10.8 KB |
| `/missions` | 68.2 KB → 41.2 KB | 18.6 KB → 8.0 KB |
| `/orders` | 75.1 KB → 44.0 KB | 19.7 KB → 8.9 KB |
| `/inventory/my` | 122.2 KB → 81.3 KB | 30.2 KB → 15.1 KB |
| `/refinery-orders` | 71.6 KB → 41.2 KB | 18.5 KB → 8.0 KB |

The comments were 24–32 KB of every page; the page CSS (up to 22 KB on the Einsatz detail) is now a
content-hashed, `immutable` asset fetched once.

**Acceptance**

- [x] No template contains a plain HTML comment outside `script` / `style` / `textarea`, and no
  parser-level comment contains an inner star-slash pair.
- [x] No template contains a `<style>` element; every `/css/pages/` link names an existing file and
  every file there is linked by exactly one template.
- [x] Every moved stylesheet is equivalent to the block it replaced (whitespace, quotes and comments
  aside — checked once at the move); the render tests that pinned a page selector read the linked
  stylesheet instead of the response.
- [x] The page stylesheets are linted with the standard configuration: `:frontend:lintCss` reads
  `static/css/pages/**` like every other stylesheet, and Prettier formats them (2026-10-04,
  ADR-0243; until then `.stylelintrc.json` ignored the directory and `:frontend:lintCssInline` read
  it with the small template rule set, which let 66 findings of the standard set accumulate).
  `:frontend:lintCssInline` keeps only the `<style>` blocks a template might bring back.

**Enforced by:** `TemplateCommentHygieneTest` (all four rules), `PageStylesheets` in the render
tests, `SingleModalShapeTest` (which now also reads the page stylesheets — and found three dead
legacy `.modal` rules the inline blocks had hidden from it), `:frontend:lintCss`,
`:frontend:lintCssInline`, `:frontend:prettierCheck` · **Related:** REQ-UI-009, REQ-UI-013, REQ-SEC-031 (`no-store` pages),
ADR-0093 (nonce-gated style blocks), ADR-0168 (asset trees)

### REQ-UI-024 — Every stylesheet sits in a cascade layer; the layer decides, not the load order

Precedence between two stylesheets is decided by the **cascade layer** each rule sits in, declared
in this order at the top of every file:

```css
@layer base, components, page, utilities;
```

*Amended 2026-10-04 (REQ-UI-027 phase 4): the `migration` layer is gone with `inline-migration.css`,
and the two state classes, renamed `is-hidden` and `is-open`, moved into a `utilities` block at the
end of `styles.css`. The statements below about migrated classes describe 2026-09-23 to 2026-10-04.*

| Layer | Holds |
| --- | --- |
| `base` | `@font-face` and the `:root` design tokens (`styles.css`) |
| `components` | the rest of `styles.css`: the design system's components and helpers |
| `page` | every page / area stylesheet (`bank.css`, …, `css/pages/*.css`), and the few design-system declarations that must keep beating one (the block at the end of `styles.css`) |
| `utilities` | the two runtime state classes, `.is-hidden` and `.krt-modal-overlay.is-open` (the last block of `styles.css`) |

Between layers the order decides, before specificity. Inside a layer, specificity and source order
decide as before. So:

- **A page stylesheet beats the design system with an ordinary rule.** No specificity bump
  (`main .form-group select`, `div.page-wrapper`, `.btn.btn-xs2`) and no `!important`: 27 page
  `!important`s existed only to win against `styles.css` and were removed.
- **A design-system declaration that has to keep beating page CSS goes into the page-layer block at
  the end of `styles.css`**, and into the table below with what it beats (amended 2026-09-25,
  ADR-0214: this used to be a comment beside the rule). Inside that layer, specificity
  against the page stylesheets decides exactly as it did under load order. It does not go into
  `utilities`: that would also let it beat the page rules that deliberately out-specify it
  (`.krt-personal-inventory .form-group select`).
- **A migrated inline style wins against page and component CSS**, as the inline `style=""` it
  replaced did (ADR-0093). Under load order it lost to any rule with higher specificity. That changed
  what a few elements look like, and the owner accepted each change (listed below).
- **The state classes win outright.** `is-hidden` hides whatever display a component or page rule
  sets. No stylesheet re-asserts it next to another class.
- **`head.html`'s link order is no longer part of the contract.**

**The page-layer block at the end of `styles.css`.** Before the layers these won by specificity
alone; in `components` they would lose to every page rule. `styles.css` loads first, so a page rule
of equal specificity still wins the tie. A computed-style diff of every page route at 375 and
1280 px showed no page-versus-component change after the move (2026-09-23).

| Rule | Declaration | Beats |
| --- | --- | --- |
| `main .form-group select` (0,1,2) | chevron room in the padding | a page's `.form-group select` (0,1,1); stays below `.krt-personal-inventory .form-group select` (0,2,1) |
| `.krt-combobox .krt-combobox__input` | right padding for the dropdown chevron | a page's padding on the combobox textbox |
| `.form-row > .form-group.datetime-split-group`, `.form-row:has(> …datetime-split-group)` | minimum width, `row-gap: 0` | the mission pages' generic `.form-row` rules |
| `.master-row.is-active` | fill and orange left edge | a page's reset of `.master-row` |
| `div.filters-container > .form-group` (≤ 768 px) | full-row filter groups on phones | page filter layouts |
| `div.page-wrapper` (≤ 768 px) | no bottom room reserved for the scrolling footer (REQ-UI-009) | a page's bottom padding |
| `.btn.btn-xs2` (≤ 1024 px) | 44 px touch floor (REQ-UI-009) | the page-local 32 px `.btn-xs2` |
| `input.item-checkbox` (≤ 1024 px) | 32 px dense floor, one per inventory row (REQ-UI-009) | `inventory-my.css`'s 20 px square in the page layer (26 px measured in `components`, 2026-09-23) |

**Accepted visible corrections (owner decision 2026-09-23).** In each case a migrated inline style
now wins over a page rule that had out-specified it:

| Where | Element | Before → after |
| --- | --- | --- |
| Bank, Bank-Anträge, Berechtigungen, Kontenverwaltung, Staffelbank, Admin-Bank, Bank- und Audit-Log | sub-line under the page greeting | 17.6 px → 12.8 px, top margin 0.25 rem |
| Spezialkommandos (admin), Organigramm | sub-line under the page greeting | grey 1 → grey 2 (text), top margin 0.5 rem |
| Missionsdaten (admin), Lager-Eingabe, Auftrag anlegen | checkbox labels | block → flex row, gap 0.5 rem, weight 400 |
| Raffinerieauftrag anlegen | read-only inputs | surface and border of the migrated read-only style |
| Persönliches Inventar | one field group | margin-bottom of its migrated class |

**Measured, not assumed.** Every page route in `FrontendPageRoutes.PAGES`, logged in as admin, at
375 and 1280 px, with the stylesheets as they were and as they are, in the same stack at the same
moment: computed style of every element and a full-page pixel diff, with the winning rule of each
changed property attributed through the DevTools protocol. Result: 26 of 140 measurements differ in
pixels, on 13 routes, and two more routes (Missionsdaten, Persönliches Inventar) differ only in
computed values, with no pixel difference (fields in a closed dialog, and sub-pixel widths). Every difference is an accepted correction above or a
knock-on of one; no declaration changed hands between the design system and a page stylesheet.

**Acceptance**

- [x] Every stylesheet under `static/css` starts with the layer order and puts all its rules in one
  of the four layers.
- [x] No page stylesheet uses `!important`.
- [x] No change a user can see beyond the accepted corrections above.

**Enforced by:** `CascadeLayerOrderTest` (the order line, the layer of every file, the state
classes only in `utilities`) ·
`SingleModalShapeTest` and `TouchClassLayoutE2eTest` read rules inside `@layer` blocks ·
**Related:** ADR-0212, ADR-0093, ADR-0176, REQ-UI-023

### REQ-UI-025 — Members find helpful Star Citizen websites on a „Star-Citizen-Links" page

The org's members use a set of community websites alongside the Basetool — trade data, loadouts,
ship databases, crafting, the org's own sites and the game's official ones. The Basetool lists
them in one place so nobody has to collect the addresses from Discord.

- **Where.** `/sc-links`, titled „Star-Citizen-Links" / "Star Citizen links". Reached from a link
  in the footer (REQ-UI-026) and from the quick access. **Every signed-in account** may open it (class-level `@PreAuthorize("isAuthenticated()")`);
  since every account is at least a member, that is „every member". Not on the public surface: an
  anonymous caller is sent to the login like on every other page.
- **What.** Forty links in six sections, in this order:
  - **Organisation** — das-kartell.org, krt-opsec.de
  - **Offizielles** / Official — robertsspaceindustries.com, its Spectrum community
    (`/spectrum/community/SC`), the roadmap, issue-council, status and public-telemetry
    pages, the ship matrix, the loaner ship matrix (support article), the ARK starmap, the
    comm-link news, the Galactapedia and the RSI knowledge base
  - **Handel & Fracht** — uexcorp.space, sc-cargo.space, sc-hauling.tools, hauler.thespacecoder.space,
    sc-trading.kamille.ovh
  - **Schiffe & Ausrüstung** — erkul.games/calculator, spviewer.eu, fleetyards.net,
    starcitizen-community.de/holoviewer, ccugame.app, maps.adi.sc
  - **Datenbanken & Crafting** — cstone.space, scmdb.net, sc-craft.tools, versekit.xharig.com,
    lootmaster.org
  - **Universum & Community** — verseguide.com, ueexi.com, star-citizen-characters.com,
    daymarrally.com, systemseven.gg, star-citizen.wiki, starcitizen.tools, sc-deutsch-launcher.de,
    sc-links.org, verseintel.org
- **Sections fold.** Each section heading is a toggle button (`aria-expanded`, `aria-controls` the
  section's card grid) with a chevron and the section's link count. Every section is open by
  default; a member's choice is kept in the browser's `localStorage` (`sc_links_collapsed_categories`,
  the keys of the closed sections), so a new section opens by default and an unavailable or
  corrupt storage leaves all sections open. Nothing is sent to the server.
- **How a link reads.** A `.card` per site: the site's own logo (48 px), its name, the host it leads
  to, and a one- or two-sentence description in the UI language. The whole card is the link; it opens
  in a new tab with `rel="noopener noreferrer"`, and says so to screen readers. A closing line states
  that the sites are run by third parties and their names and logos belong to them.
- **Logos are local copies.** Each site's favicon or touch icon is stored under
  `static/images/sc-links/` — PNGs normalised to 96 × 96 on a transparent square, five sites as
  SVG; sites of one operator may share a logo file. The CSP's `img-src 'self' data:` forbids hotlinking, and a hotlinked logo would
  also send every member's address to forty third parties on each visit. A shipped SVG carries no
  script, event handler, `foreignObject` or external reference.
- **The catalogue is code.** `ScLink` (key, address, logo, section) and `ScLinkCategory` are enums;
  names and descriptions are i18n keys `scLinks.link.<key>.name` / `.description`. Changing the list
  is a code change with its copy in all three bundles.
- **No data, no backend call, nothing mutates** — so no live-update wiring (`REQ-FE-001…010` do not
  apply), no audit event and no business metric.

**Enforced by:** `ScLinkCatalogTest` (unique HTTPS addresses, every logo shipped and none unused,
SVG hygiene, the copy in every bundle, every section non-empty) · `ScLinksPageControllerMvcTest`
(renders every card with its logo and address in DE and EN, the footer link) ·
`AnonymousSurfaceSweepMvcTest` (anonymous navigation is sent to the login) ·
**Related:** REQ-UI-018, REQ-SEC-052

### REQ-UI-026 — Navigation: a structured drawer, a quick access and a phone tab bar

> **Status: partly shipped** (ADR-0297, map #2800). Admin mode and the member default of the
> amendment are implemented and replace the *List* zone, the group list and the quick access's page
> list of the text below, and so are the three desktop modes (drawer, rail, pinned sidebar) with the
> „Darstellung" switch; „Mehr" and „Anpassen" in the rail wait for the hiding and editing of
> REQ-UI-032. The personalisation and the phone tab bar of the amendment stay planned, and the
> current text stays authoritative for them.

The navigation has grown to about 65 links for a member with many roles, several viewport heights
of one flat drawer. It is one model on every device
([ADR-0240](../adr/0240-navigation-is-a-structured-drawer-a-quick-access-and-a-phone-tab-bar.md)),
built pixel-exact from the design hand-off „Navigation neu" (Turn 2: 2a desktop, 2b phone).

- **One header.** Every app page renders `kernel/fragments/header.html :: header`, through the layout
  fragment of REQ-FE-033 — hamburger, brand with
  `appTitle`, the quick-access trigger („Seite oder Aktion suchen …", `Strg K` / `⌘ K`) and the
  notification bell, which sits in the header's flex row instead of `position: fixed`. The admin
  chip shows on `/admin/**` pages as before. The seven pages without the app shell (error pages,
  pending approval, terms acceptance) keep their own header.
- **The drawer (≥ 769 px)** is a 320 px overlay in three zones; only the middle one scrolls:
  - *Head* — logo, `appTitle`, close.
  - *Context* — the active-org-unit switcher (only with more than one org unit) and the menu filter.
    Up to 1024 px the switcher keeps the 44 px touch floor of REQ-UI-009; the hand-off's 40 px
    applies above it.
  - *List* — „Startseite" and „Benachrichtigungen" (with the unread count) on top, then the groups
    Einsatzplanung · Flotte & Logistik · Handel · Organisation · Beförderung (only with the
    promotion feature) · Kartellbank · Ressourcen, each with its icon. Group headings are
    upper-case; the links are normal case (Lato 400, 0.9 rem), a deliberate, product-owner-approved
    exception to „navigation is upper-case". Every group is open by default; collapsing one is
    remembered per browser (`localStorage` `krt.sidebar.<key>`); the group holding the current page
    is always open, and the current page is marked (`aria-current="page"`, inset primary bar).
  - *Foot* — „Administration" (administrators only), the personal panel, and the user row: name,
    `<org unit> · <role>`, the language menu and logout.
- **Information architecture.** „Rechtliches" is not in the menu — the legal links stay in the
  fixed footer. „Persönlich" (Mein Inventar, Meine Blueprints, Profil, Verbundene Anwendungen) is
  the panel the user row opens. **Administration is a mode:** the entry replaces the groups with the
  four admin groups (Benutzer & Inhalte, Stammdaten, Kartellbank, System & Daten) and a „Hauptmenü"
  control; on `/admin/**` pages and on any page whose link lives in an admin group the drawer opens
  in that mode.
- **The menu filter** searches every group the member has, the personal links and — for
  administrators — the admin groups. A link matches when its label, its search terms or its group's
  label contains the text; only matching groups show, all open, and the top entries hide. With
  no match it says „Kein Menüeintrag gefunden."; with any text it offers „Im Schnellzugriff suchen",
  which closes the drawer and opens the quick access with the text.
- **The quick access** (`Strg`/`⌘` + `K` from anywhere, inputs included; the header trigger; the
  phone's search button) is a dialog of the one dialog shape (REQ-UI-013, `#nav-palette`). Its pages
  are the menu's links, the legal links, and sub-pages the menu reaches only as tabs: „Mein Lager",
  „Globales Lager", „Hangar der Org-Einheit", for administrators the two sync-report tabs, and —
  loaded on the first opening — „Eingehende Spenden" and „Spenden-Statistik" (REQ-SUP) and
  „Ankauf-Partner", „Handelsbeauftragte" and „Ankauf-Bericht" (REQ-PUR) for exactly the members
  whose tab bar shows them. Empty, it offers up to four recently visited pages (`localStorage`
  `krt.recent`, recorded on every page it offers by its exact path, otherwise by the current menu
  link, and when a page is opened from it) and the actions: „Neuen Auftrag anlegen", „Neue Mission
  anlegen", „Neuen Raffinerieauftrag anlegen", „In mein Lager einbuchen", „Ins globale Lager
  einbuchen" (logisticians only), „Org-Einheit wechseln" (only with a switcher), „Administration
  öffnen" (administrators only; opens the drawer in administration mode), „Handbuch öffnen" (the
  wiki, in a new tab), the switch to the other language, and „Abmelden". With text it lists up to
  eight pages, label hits before breadcrumb and search-term hits, then the matching actions, with
  the hit marked; „Keine Treffer. Signal verloren." when nothing matches. ↑/↓ move the selection,
  Enter opens, Escape and the scrim close.
- **Tolerant matching.** The menu filter and the quick access compare folded text: case is
  ignored, diacritics are dropped, `ß` matches `ss`, and `ä`/`ö`/`ü` match `ae`/`oe`/`ue` in either
  direction. Menu links, the quick access's own pages and its actions carry localized search terms
  (`data-nav-keywords`, `palette.keywords.*`) that match like the label.
- **Rights stay on the server.** The quick access builds its index from links the server rendered
  for this member — the drawer's (`data-nav-group` / `data-nav-icon` on each group) and its own
  hidden source lists, each under the same condition as the button or tab it mirrors. Sub-pages
  whose condition needs a backend read come from `GET /quick-access/entries`, which asks each
  domain's `QuickAccessContributor` and fails closed (ADR-0282). A recently visited page that is no
  longer offered is not shown.
- **Phones (≤ 768 px).** The header is 56 px: logo, title, search. A fixed tab bar (64 px plus the
  safe area) holds five slots: three the member chooses (REQ-UI-032; Missionen · Lager · Aufträge by
  default), „Meldungen" with the unread count and „Menü"; the current area is marked. „Menü" opens the same drawer as a sheet above the tab bar — its groups an
  accordion with the current page's group open, the legal links, the handbook and the version at the
  end of the list, the same foot with 44–48 px rows. The fixed footer is hidden while the tab bar
  shows; `main` reserves the tab bar, `--krt-footer-height` stays `0px`, and bars pinned to the
  bottom of the viewport sit above the tab bar (`--krt-tabbar-height`). Pointer drags that scroll at
  the viewport's bottom edge measure that edge from the top of the tab bar, and a drop released over
  the tab bar is cancelled (the crew board, REQ-MISSION-005). The quick access opens full
  screen with „Abbrechen". A signed-out visitor of a legal page has no tab bar and keeps the
  hamburger and the footer.
- **Accessibility.** The closed drawer is `inert`; opening it focuses the filter (desktop) and
  closing it returns focus to the opener. Escape closes the drawer. Group heads are buttons with
  `aria-expanded`; the quick access is a combobox over a listbox with `aria-activedescendant`.
  Motion is limited to colour transitions and the 0.4 s drawer slide, and `prefers-reduced-motion`
  removes both.
- **No data, nothing mutates.** Every action navigates or opens the drawer; the org-unit switch
  and logout keep their existing forms, so no live-update wiring, audit event or metric changes.
- **One entry registry.** The drawer (top links, groups, personal panel, admin groups, legal
  links), the phone tab bar and the quick access's own pages and actions are rendered from one
  frontend registry, never from links written into the fragments. Each entry carries a stable
  **entry key**, its path, label, search terms and its gate (roles, of which one is needed, read
  through the role hierarchy, plus the capability and feature flags it requires or excludes);
  every rendered link names its key in `data-entry-key`. A tab-bar slot names candidate entries
  and opens the first one the member may see. **Key format:** two or more dot-separated segments,
  each a lower-case letter followed by lower-case letters or digits, at most 64 characters
  (e.g. `missions.list`, `storage.my`, `admin.syncreports.uex`); a key never encodes the path.

**Enforced by:** `NavigationRenderMvcTest` (header, drawer zones and groups, admin mode only for
administrators, the quick-access metadata, search terms on menu links, the quick access's own pages
and actions per role, no „Rechtliches" group, nothing of it for a signed-out visitor) ·
`NavigationEntryKeyRenderMvcTest` (every rendered link names a registered entry key) ·
`NavigationMemberRenderMvcTest` (the four task groups, the footer link, officer-only tabs by flag) ·
`NavigationLegacyRedirectMvcTest` (the former Lager list URLs redirect to the Lager tab with the option and
their filter query; in-place requests and sub-paths are not redirected) ·
`NavigationRegistryTest` (key format, unique entry and group keys, tab candidates registered) ·
`NavigationMenuTest` (gates per role and flag, tab stand-ins, quick-access-only pages) ·
`QuickAccessControllerTest`, `SupplyQuickAccessContributorTest`,
`PurchasingQuickAccessContributorTest` (the on-demand sub-pages follow the tabs and fail closed) ·
`:frontend:testSearchJs` (folding and hit ranges) · `NavigationE2eTest` (filter and hand-over,
`Ctrl+K`, tolerant and search-term matching, the create-mission and administration actions, admin
mode, the phone tab bar and sheet) · `DialogA11yE2eTest` (the quick access follows the dialog contract) ·
`TouchClassLayoutE2eTest` (`main` reserves the tab bar; header, footer and tab bar within the
chrome budget) · **Code:** `kernel/fragments/header.html`, `kernel/fragments/sidebar.html`,
`kernel/fragments/palette.html`, `kernel/fragments/mobile-nav.html`, `static/js/kernel/sidebar.js`,
`static/js/kernel/krt-palette.js`, `static/js/kernel/krt-search.js`, `shell/web/QuickAccessController.java`,
`kernel/layout/QuickAccessContributor.java`, `kernel/fragments/nav-entries.html`,
`navigation/web/NavigationRegistry.java`, `navigation/web/NavigationAdvice.java` · **Related:** REQ-UI-009, REQ-UI-013, REQ-UI-019,
ADR-0282

#### Planned amendment (ADR-0297)

**What it replaces.** The *List* zone and the admin groups of „The drawer" and „Information
architecture", the 769 px drawer threshold and the „No data,
nothing mutates" bullet (preferences are written, REQ-UI-032). The header, the context and foot
zones, the menu filter, tolerant matching, „Rights stay on the server" and accessibility stay; the
quick access is extended by REQ-UI-033.

**Counting rule.** A default is measured in clickable links with every group open; group headings
do not count. Groups are cut by task, at most four for members.

**Member default: 10 links in 4 task groups** (+1 group for bank staff). Each link opens one page;
its former sibling pages become tabs of it.

| Group | Icon | Links (tabs) |
|---|---|---|
| Einsatz & Flotte | wedge | Missionen (Missionen · Operationen), Hangar (Hangar · Schiffskatalog) |
| Logistik | crate | Lager (Lager · Mindestbestände), Raffinerie, Aufträge (Aufträge · Materialbedarf · Blueprint-Verfügbarkeit) |
| Handel | swap arrows | Material (Liste · Übersicht · Profitberechnung), Materialbörse, Beschaffung (Bedarf · Meine Spenden · Ankauf) |
| Staffel | users | Organisation (Organigramm · Staffel-Bank · *Leitung*), Beförderung (Übersicht · Meine Bewertungen · *Verwaltung*) |
| Kartellbank (bank staff only) | bank | Kartellbank (Dashboard · Verwaltung · Berechtigungen · Anträge) |

- *Shipped:* the groups, links and tabs are the entries of the registry (`NavigationRegistry`),
  every tab bar is `NavigationPageTabs` rendered by `pageHead(tabsKey=…)`, and a page left with fewer
  than two permitted tabs shows no bar. A tab can cover pages that are not tabs themselves (Beförderung's
  Themenbereiche and Rangvoraussetzungen); they show the bar with the
  covering tab current. Beschaffung keeps its supply and purchasing tab fragments, which add
  „Ankauf" and „Bedarf" · „Meine Spenden" across the two surfaces. Every former URL still opens
  its page or tab (the Lager lists by redirect); an entry gated on a flag or role the caller lacks is neither a link nor a tab, and
  officer-only tabs follow `canOpenLeitung` and `canManagePromotions`.
- *Italic* tabs are officer-only (officer reach per org unit, ADR-0300); Beförderung's
  „Verwaltung" holds Bewertungen, Themenbereiche and Rangvoraussetzungen. Officers therefore also
  see 10 links, bank staff 11.
- The tab names are i18n labels; „Lager · Mindestbestände" is „Storage · Minimum levels" in English
  (REQ-INV-068).
- **The storage page has exactly these two tabs.** „Mein Lager" and „Globales Lager" are neither
  tabs nor pages of their own: they become a switch inside the „Lager" tab (a `.krt-segmented`
  control, REQ-UI-028), each option shown under the same role condition as today's page. Their
  quick-access entries open the „Lager" tab with that option selected and keep their own stable
  entry keys (REQ-UI-032), so they can still be favourites, tab-bar slots and start pages; the old
  URLs `/inventory/my` and `/inventory/all` redirect to the tab with the option.
  *Shipped:* the options are `/inventory?storage=my` and `/inventory?storage=all`, rendered by the
  Lager tab with the „Lager" tab bar and the switch (`aria-current` on the selected option); the
  old page URLs redirect with their filter query kept, while their in-place requests and sub-paths
  (stack entries, entry ids) keep their endpoints.
- The ship catalogue, readable by every signed-in user, is a Hangar tab; editing it stays in
  admin mode.
- A user without profit access who is an assignee of at least one job order sees „Aufträge" with
  only the „Ich bearbeite" segment (REQ-ORDERS-047).
- **Out of the link list:** „Startseite" — the logo leads to the home dashboard (REQ-UI-034);
  „Benachrichtigungen" — the bell with the unread count sits right of the logo in the header;
  „Star-Citizen-Links" (REQ-UI-025) — a footer link next to Handbuch and Impressum.
- **Personal pages stay in the user menu:** Mein Inventar (Inventar · Blueprints), Profil,
  Verbundene Anwendungen.

**Admin mode: the admin dashboard plus 13 links in 3 groups, collapsed by default.** The admin
dashboard (REQ-UI-034) carries the hub of tiles; the groups replace the four current admin groups.
*Shipped:* `/admin` is the hub until the dashboard widgets exist, the groups start collapsed (the
member's own choice and the group holding the current page win), and every merged page shows a tab
bar of its sibling pages (`NavigationPageTabs` in the registry). The old four admin groups are gone;
the former admin pages that became tabs keep their URLs, and the URLs that merged redirect
(`NavigationLegacyRedirectMvcTest`).

| Group | Icon | Links (tabs) |
|---|---|---|
| Mitglieder & Organisation | users | Mitgliederverwaltung (Mitglieder · Discord-Registrierungen · Zustimmung Nutzungsbedingungen), Organisationsstruktur (Struktur · Staffeln · Spezialkommandos), Datenschutz-Anfragen (Personensuche · Löschanträge) |
| Stammdaten | database | Missionsdaten (Aufgaben, Frequenzen), Orte (Orte · UEX-Standorte & Terminals), Schiffsdaten, Materialien (Materialien · Aliase · Qualitätsstufen), Blueprints (Blueprints · Standard), Datenimporte (P4K-Import · Sync-Berichte) |
| Betrieb | gear | Audit-Logs, Systemeinstellungen (Allgemein · Bedarf & Spenden · Ankauf · Gefahrenzone), Verbundene Anwendungen, Benachrichtigungsregeln |

Page merges this needs:

- All four org-unit kinds are managed on Organisationsstruktur: the tabs Struktur · Staffeln ·
  Spezialkommandos. Staffel CRUD left Missionsdaten (`/admin/mission-data?kind=squadrons` redirects
  to `/admin/org-structure/squadrons`), and the per-unit switches „Beförderung aktiv" and
  „Auftragsbearbeitung" left Systemeinstellungen for the unit's row — on the Staffeln tab for
  squadrons and on the Spezialkommandos tab for the „Auftragsbearbeitung" of a special command. The
  switches save in place and need no audit event of their own beyond the existing ones.
- The person detail (`/members/{id}/edit`) has the tabs Stammdaten · Hangar · Inventar ·
  Blueprints · Export. Inventar and Blueprints are the former admin personal-inventory and
  personal-blueprints pages, now keyed by the member (`/admin/personal-inventory?userSub=…`,
  `/admin/personal-blueprints?userSub=…`); the member picker is gone and the pages without a member
  lead to the member list.
- „Gefahrenzone" (`/admin/settings/danger-zone`) holds the destructive one-off actions: the bank
  wipe reset and deleting every user's blueprints. `/admin/bank` redirects to it.
- „Informationen" (the home-page announcement) is edited inline in its dashboard widget; it has no
  admin link. Until the widget exists (REQ-UI-034), `/admin/announcement` stays reachable from the
  admin hub and the quick access.

**One default for every role; visibility follows the active context.**

- Roles and the active org-unit context decide **whether** an entry shows, never **where** it
  sits: no per-role order, no per-role open groups, no pre-seeded favourites.
- *Context-bound:* the per-unit switches, the Logistician and Mission-Manager grants and officer
  reach. *User-wide:* Bank and Admin. The frontend reads them as capabilities of the active
  context.
- With no unit pinned („Alle meine Einheiten") the **union** applies: an entry shows if it is
  permitted or enabled in at least one of the user's units.
- A link unavailable in the active context is not rendered, and the quick access drops it the same
  way. A newly permitted entry appears at its default position, after its default predecessor,
  with no badge and no toast.
- A tab whose feature is off or whose role is missing is not rendered; a page left with no tab
  loses its link.

**Old URLs redirect** to their new page and tab, so bookmarks and notification links keep working.

**Three desktop modes**, chosen with a three-state switch in the sidebar head (next to „Anpassen")
and under „Darstellung" in the profile; one mode for member and admin mode, stored server-side
(REQ-UI-032).

| Mode | Width | Behaviour |
|---|---|---|
| Drawer (default) | ≥ 769 px | The current 320 px overlay. |
| Icon rail | ≥ 1025 px | Icons only, one entry per group. |
| Pinned sidebar | ≥ 1280 px | The labelled sidebar fixed beside `main`, with the drawer's zones. Between 1025 and 1279 px it renders as the rail. |

Below 1025 px the drawer (769–1024 px) and the phone layout (≤ 768 px) apply automatically; the
stored mode is kept.

- **Rail entries:** Favoriten (star; only with at least one favourite), the task groups,
  Kartellbank for bank staff — in admin mode Favoriten and the three admin groups — and „Mehr"
  (dots) below them only while a page is hidden. The foot holds the admin-mode icon (shield; only
  with the role) and the avatar, whose flyout holds the personal panel, the user row and the
  org-unit switcher. The rail has no menu filter; `Strg K` covers it.
- **Flyouts** are disclosures (`<button aria-expanded aria-controls>`), neither dialogs nor ARIA
  menus. They open on hover after about 200 ms and on click, Enter or Space; a hover-opened flyout
  stays while hovered, closes about 300 ms after the pointer leaves, and closes on Escape with focus
  back on the icon (WCAG 1.4.13). A click pins it until an outside click, Escape or a followed link.
  The flyout header is the group name; the icon's `aria-label` is the group name, with no extra
  tooltip. A flyout lists its group's links; tabs appear only in the Favoriten flyout. The icon of
  the group holding the current page is highlighted.
- Rail entries are `<button>`s with the touch floor of ADR-0176. Rail and pinned sidebar set
  `scroll-padding-inline-start` to their width (WCAG 2.4.11).
- „Anpassen" in rail mode opens the labelled sidebar as an overlay; „Fertig" returns to the rail.
- **Icons** are 24 × 24 with stroke 2 in the sprite's style; missing ones go into the design
  system's sprite.
- *Shipped:* the mode is the `data-nav-mode` of `body`, rendered from the stored „Darstellung"
  section, so the first paint is right and a failed read renders the drawer with the switch off.
  The style sheet applies the width rules; the rail (`nav-rail.html`, one `.nav-rail-item` per
  group with its flyout) is a sibling of the sidebar, which stays the labelled overlay in the rail
  mode and is docked in the pinned mode. The switch is a `.krt-segmented` radio group in the
  sidebar head and in the profile card „Darstellung", and writes in place through `krtFetch`
  (`nav-appearance.js`). The rail is `nav-rail.js`; `krtChrome.nav.open()` opens the sidebar as an
  overlay in the rail mode, which is what „Anpassen" will call.

**Phone tab bar: five slots** (shipped). Slots 1–3 are configurable (REQ-UI-032), slot 4 is the
fixed „Meldungen" with the live unread count, slot 5 the fixed „Menü", the only way into the
navigation. One bar per mode: the member bar shows in member mode, the admin bar in admin mode.
Every slot is at least 44 px wide and tall and shows the icon of its entry.

| Mode | Default slots 1–3 |
|---|---|
| Member | Missionen · Lager · Aufträge |
| Admin | Admin-Dashboard · Mitgliederverwaltung · Audit-Logs |

**Enforced by (admin mode and phone tab bar shipped):** `AdminNavigationRenderMvcTest` (hub, three
collapsed groups, 13 links, tab bar) · `NavigationTabBarMvcTest`, `NavigationTabBarResolverTest`
(the default slots per mode, the fill from the favourites, the stand-in) ·
`NavigationTabBarE2eTest` (the phone bar at 375 px) · `NavigationLegacyRedirectMvcTest` (every merged admin URL reaches its page and
tab, in-place requests are not redirected) · `NavigationMenuTest`, `NavigationRegistryTest` (tab
bars and counts) · `NavigationE2eTest` (collapsed groups behind the hub link, the tab bar).

**Enforced by (desktop modes shipped):** `NavigationDesktopModesMvcTest` (the mode on the body, the
switch, the rail's groups, flyouts, favourites, foot and avatar flyout, the appearance relay) ·
`NavigationE2eTest` (the three modes and their width fallbacks at 1024, 1100, 1280 and 1440 px,
`scroll-padding-inline-start`, flyouts by hover, click and keyboard with Escape returning focus,
the admin toggle) · `NavigationAppearanceServiceTest`, `NavigationPreferencesControllerTest`
(the section store).

**Enforced by (to be added):** `NavigationRenderMvcTest` (the default groups, links and tabs per
role and context, the union without a pin, admin mode's three collapsed groups, no „Startseite" or
„Benachrichtigungen" link) · `NavigationLegacyRedirectMvcTest` (every old URL reaches its page and
tab) · **Related:** REQ-UI-032, REQ-UI-033,
REQ-UI-034, REQ-UI-035, REQ-ORDERS-047, REQ-INV-068, ADR-0297, ADR-0300

### REQ-UI-027 — Work pages follow three page patterns built from shared fragments

The website audit of 2026-10-03 found the same faults on almost every template — a banner that
repeats the title, nested `.hud-box`es, up to twelve filled orange buttons, tables that scroll
sideways on a phone, raw status values, `colspan` empty rows, collapsed filter panels with a
„Filtern" button, forms as one long column. The design system answers with twelve changes, DS-1 to
DS-12 ([ADR-0242](../adr/0242-page-patterns-a-to-c-and-the-design-system-update-2026-10.md),
krt-profit/design-system#6), and every work page follows one of three patterns.

**Type and surfaces (DS-1, DS-2, DS-3, DS-10)** — global, migrated page or not:

- Body text is Lato **400** (`--fw-regular`); Lato 300 only as `.lead` (1.1 rem) for lead text.
- Only `h1` carries the house orange. `h2` and `h3` are white, `h4`–`h6` Grau 1; upper case and
  tracking stay. Orange is for `h1`, the one primary action, the active state and identity.
- Muted text is `--color-gray-2-text`, never `--color-gray-2` (REQ-UI-006).
- At most **one** `.hud-box` per view, never nested; a nested legacy `.hud-box` loses its corner
  brackets. Content never sits with white text directly on `#000`: lists and tables in
  `.card.card--flush`, notices as `.alert`.

**Global rules of a work page:**

1. **One primary action per context** — at most one `.btn--cta` in the page head and one per panel
   or dialog. Row actions are `.btn-ghost.btn-xs`, `.btn-success.btn-xs` or
   `.btn-quiet-danger.btn-xs`, never `.btn--cta`.
2. **The page head is `.page-head`** (DS-4): an eyebrow naming the navigation area — or, on a form
   or detail page, a back link „← Aufträge" —, the `h1` with an optional count chip, and
   `.page-actions`. `.greeting` stays only on the home page, the landing page and the error pages.
   A `*.description` subtitle is dropped unless it says something the title does not; the keys stay
   for a later clean-up.
3. **Rare and destructive actions go into the overflow menu** („⋯", `.overflow-menu`). Deleting
   always goes through `showKrtConfirm` or a `.krt-modal--danger`.
4. **Status is always translated** (`#{…status.<value>}`), never `${x.status}` raw.
5. **An empty list is an `.empty-state`** with a title, one sentence and optionally an action —
   never a `<td colspan>` row.
6. **Tables** are `.card.card--flush > table.data-table.data-table--stack` (DS-7, DS-8): rows of at
   least 56 px, the first column an `a.row-link` with `.cell-title` and `.cell-sub` (the whole row is
   clickable), numbers `.num`, status `.cell-status`, actions `.cell-actions`, a `.row-chevron` at
   the end — no column holding only an „Öffnen" button. Below 768 px a row stacks: title and status
   on the first line, the other cells as one meta line (a `data-label` prefixes its cell), a lone
   chevron centred on the right; nothing scrolls sideways.
7. **Filters** sit in a `.toolbar` (DS-5): the search always visible, a `.krt-segmented` control for two
   to four exclusive options (DS-6, REQ-UI-028), further filters behind „Filter" with a square `.filter-count`, active
   filters as `.filter-chips` with „Alle zurücksetzen". Filters apply live through `krtFetch`, never
   through a „Filtern" button; per-browser persistence (REQ-UI-017) stays.
8. **Forms** use `.form-layout` (at most 760 px, left-aligned under the page head; a form page marks
   `main` with `.page--narrow`, REQ-UI-009) with numbered `.form-section`s, a `.form-grid` that
   is two columns from tablet up, computed values as `.field-computed`, two to four exclusive options
   as `.krt-segmented--block` instead of radios, and the actions in `.form-actions--sticky` with a live
   `.form-actions__summary` (DS-9).
9. **Layout** uses `.stack`, `.cluster`, `.split` and `.grid-auto` (DS-12). No `krtm-*` class
   (the last ones were replaced in phase 4).
10. **Touch targets** stay at 44 px on phones and tablets (REQ-UI-009), including the overflow-menu
    entries, the filter chips and the toolbar search.

**The three patterns:**

- **A · List** — page head, toolbar, filter chips, the list table with the pagination below it.
- **B · Overview** (the home page) — `.greeting` without a description, information first in a 2 : 1
  grid that is one column from 1024 px down.
- **C · Form** — page head with the back link, `.form-layout.card`, sticky actions whose primary
  button names verb and object („Auftrag anlegen"); field errors under the field (`.field-error`),
  page errors as `.alert.alert-danger` above the first section.

**The fragments** — pages call these instead of repeating the markup:

| Fragment | Renders |
| --- | --- |
| `fragments/page-head :: pageHead` | `.page-head`; parameters `eyebrowKey`, `eyebrowHref` (makes the eyebrow a back link), `titleText`, `count`, `countId`, and the slot `actions=~{::ref}` |
| `fragments/components :: emptyState` | `.empty-state`; `titleKey`, `textKey`, `filterHintKey`, `actionHref`, `actionKey`, `emptyId` — `emptyStateBody` stays for existing callers |
| `fragments/components :: toolbarSearch(id, name, value, placeholderKey)` | the search field, excluded from the chips |
| `fragments/components :: segmented` | a `role="radiogroup"` of radios inside labels; `name`, `options` (each with `value`, `labelKey`, optional `count`; a `null` value is the „all" option), `selected`, `labelKey`, `variant`, `form` |
| `fragments/components :: overflowMenu` | the „⋯" toggle (`aria-haspopup`, `aria-expanded`, `aria-controls`) and a hidden `role="menu"` panel; `id` and the slot `items` |
| `fragments/components :: filterChips(formId)` | the chip bar bound to a filter form |
| `fragments/components :: filterPopover` | the „Filter" button with a square `.filter-count` badge (and `.is-filtered` while a filter is active) and a popover panel holding the further filters (`panelId`, `panelName`, `testid`, slot `body`); a bottom sheet above the tab bar on phones. It keeps the `.filter-toggle`/`.filter-panel` contract of REQ-FE-021, so `krt-filter-panel.js` counts its active controls; a `data-filter-transient` panel always starts closed, stores no preference, focuses its first field, closes on Escape or an outside click, and flips to the left edge when it would leave the viewport |
| `fragments/components :: listFoot(pageResponse, baseUrl)` | „1–7 von 24" and the pagination below a table; its `data-list-total` keeps the page head's count chip (`countFor`) current after every live swap (`krt-list-count.js`) |

Two global scripts drive them: `krt-overflow-menu.js` opens and closes the menus (toggle, Escape,
outside click, Tab, focus back to the toggle, arrow keys, Home/End), closes a menu when one of its
entries is clicked, and opens a panel upwards (`.overflow-menu__panel--up`) when it does not fit below
its toggle — inside the viewport under the sticky header, the body of an enclosing table and every
clipping ancestor — and there is more room above; `common-handlers.js` publishes the sticky header's
height as `--krt-header-height` for sticky content below it; `krt-filter-chips.js` renders a chip per active control of the bound form
(skipping search fields, segmented controls and `data-filter-chip-ignore`), and removing a chip
clears that control and fires its `input` and `change` events, so the page's own live filter runs.
Pages that swap their filter form call `window.krtFilterChips.refresh()`.

New `data-testid`s: `page-head`, `page-eyebrow`, `page-head-count`, `page-head-primary` (set by
the page on its primary action), `overflow-menu-toggle`, `toolbar-search`, `segment-<name>-<value>`,
`filter-chips`, `filter-chip-<name>`, `filter-chips-reset`, `empty-state`, `empty-state-action`,
`row-link` (on the `a.row-link` that makes a table row clickable). Every existing `data-testid`
stays: a primary action or row link that carried a page-specific id before the patterns keeps it
(`missions-create-link`, `orders-create-link`, `bank-movement-open`, `bank-holder-history-link`, …),
because an element has one `data-testid` and the smoke tests address the old one. A hand-built
pattern carries the same ids as its fragment — the bank's account filter is the page's
`toolbar-search`.

*Amended 2026-10-04 (phase 5): `row-link` added; the ids pinned per pattern by
`PagePatternTestIdsRenderTest`.*

**Rollout.** Phase 0 (this requirement's foundation) ships the design-system update, the fragments,
the scripts and the breakpoint gate; the list pages, the home page and the forms, the areas of the
audit (hangar, blueprints, operations, Materialbörse, refinery, Leitung, bank, trade) and the removal
of `inline-migration.css` follow as their own PRs, each moving its pages onto these patterns.

**On pattern A (phase 1):** Einsätze, Operationen, Benachrichtigungen, Mitglieder, Beförderung
verwalten, Auftragsverwaltung, Materialbedarf, Lager-Übersicht, Item- und Materialsammelübersicht,
Material-Liste, and the admin lists Audit-Logs, Blueprints, Standard-Blueprints, Löschanträge,
Discord-Registrierungen (tabs „Offen · Abgelehnt"), Verbundene Anwendungen, Orte, Material-Aliase,
Materialien, Benachrichtigungsregeln, Organisationsstruktur, Qualitätsstufen, P4K-Import,
Personensuche, Persönliche Blueprints/Inventare, Spezialkommandos, Sync-Berichte,
Nutzungsbedingungen (segment „Noch offen · Zugestimmt · Alle"), Einsatzdaten and UEX-Daten. A list
row's relative day („in 5 Tagen", „heute", „vor 4 Tagen") comes from the `@relativeDays` template
bean (Europe/Berlin), its date from `.utc-time[data-format=short]`.

**On patterns B and C (phase 2):** the home page (pattern B: the greeting without a description, eyebrow
„<Org-Einheit> · <Wochentag, Datum>", a 2 : 1 grid with „Nächste 7 Tage" as row links — date block,
„TS · Join · Treffpunkt · Einheit", translated status, „Meine Einheit" — and, beside it, the
information card with a three-line clamp and „Gelesen", and the three newest notifications read
from the bell's `/notifications/recent`; no call to action per mission). Pattern C: Neuer Auftrag
(kind as a segment, numbered sections, material rows as a grid with a remove icon, the scmdb import
as a dialog, a comment counter, a live summary in the sticky bar), Lagereintrag einbuchen, Mitglied
bearbeiten (tabs „Stammdaten · Mitgliedschaften · Datenauskunft", `?tab=` deeplink),
Systemeinstellungen, Information bearbeiten, the notification-rule editor, the quality-tier dialog,
and the profile as one settings page (section navigation, one save bar that appears on a change and
saves the changed sections one after another, REQ-FE-001). The hangar's insurance field is a
segment „Keine · Monate · LTI" with a month field; the stored value stays `0` / `LTI` / `n`. The
hand-off's „Angemeldet" chip marks a home row the member is signed up for, read from the mission
list's per-caller `signedUp` flag (REQ-MISSION-012).

Amended 2026-10-04: the „Angemeldet" chip, left out of phase 2 for want of a read, is built.

**On the areas (phase 3):** Hangar: one page with the tabs „Meine Schiffe" · „Org-Einheit" (the
former Org-Einheitsübersicht, ship types as tree rows with a readiness bar); „Meine Schiffe" filters
by „Alle · Bereit · Nicht bereit" beside the search and counts „n von m einsatzbereit", and import,
home location and „Hangar leeren …" sit in the page head (REQ-HANGAR-001/002). Mein Inventar →
Blueprints: „Blueprints hinzufügen" opens a dialog, the extractor link and „Alle löschen …" sit in
the „⋯" menu, one search, the segment „Alle · Craftbar" and the refinery switch; each row shows a
status dot and „3×", the detail a key figure and the ingredients with need, stock and quality; the
availability overview shows the first owners as chips with „+ n weitere" (REQ-INV-008/012/013/048).
Materialbörse: tabs „Angebote · Gesuche" with the scope segment „Alle · Meine" (the former four
tabs, `?view=&scope=`, old `mode`/`tab` honoured), one create menu per tab („Angebot erstellen ▾" /
„Gesuch erstellen ▾"), the filter popover with chips and a compact sort menu, three detail figures.
Raffinerie: the list with the run-state segment „Läuft · Abholbereit · Abgeschlossen · Alle" and its
counters, a server-side search over owner, location, method and materials across all pages,
server-side paging, progress bars and a row action „Einlagern" on ready rows; create and detail as
one numbered form (Raffinerie · Zeit · Materialien · Finanzen · Besitzer) with a side card holding
the status steps „Läuft → Abholbereit → Eingelagert", the profit with its calculation and the
primary action „Ausbeute einlagern", and saving as an outline button in the sticky bar
(REQ-REFINERY-019). Mein Lager and Globales Lager: page head with „Einbuchen" (and, for an admin on
the shared Lager, „Globales Lager leeren" in the overflow menu), the filters in the transient
popover with chips, the segment „Alle · Persönlich · Gemeinsam" on Mein Lager, and the bulk actions
in a sticky selection bar that appears only while something is marked; the tree stays. The material
and item drill-downs are data tables with a count chip in the head. Handel: the price matrix as a
wide data view with the „UEX · vor n min" chip, dropdown filters that summarise their selection („6
von 42"), „+ Filter" chips, the best sale/purchase rings, the spread and the legend; the material
detail with category/flag chips, four figures and the terminal table sorted by sale price (segment
„Verkauf · Einkauf"); the profit calculation with one input card, the Hull C chip and results ranked
by max. profit, sortable by their column headers (REQ-UI-030), with a two-line route „Kauf <Terminal> (<Ort>) · <Preis>" / „Verkauf <Terminal> (<Ort>)
· <Preis>", the terminal's location (planet or star system, then city, station or outpost) in
brackets after its name; the lines wrap rather than cut off.
Auftragsdetail: page head with kind and status badges and the admin overflow „Auftrag löschen", the
progress tile „Geliefert / benötigt" leading the KPI grid, tabs filtered per kind (Material: Bedarf
· Übergaben · Bearbeiter · Verknüpft; Item: Items · Materialien · Hersteller · Übergaben ·
Bearbeiter · Verknüpft) with empty tabs hidden, and „Übergabe erfassen" as the one action of the
handover tab (REQ-ORDERS-026). Einsatz and Operation detail: both heads are page heads with the back
link, the title and a translated `.status-badge`. The Einsatz page keeps three tabs (Übersicht ·
Teilnehmer & Einheiten · Finanzen & Auszahlung); its „Verwaltung" tab became an edit mode behind
„Bearbeiten" (tabs show views, not forms), „Anmelden" is the head's one CTA and deleting sits in the
„⋯" menu. The Operation page has a KPI bar and four tabs (Übersicht · Einsätze · Auszahlung ·
Finanzen), and is edited in a dialog (REQ-MISSION-004, REQ-MISSION-011). Leitung and
Spezialkommandos: „Leitung" is a master-detail — a unit tree grouped OL · Bereiche · Staffeln ·
Spezialkommandos with Bereich colour, member count and a search over units and people, and the
selected unit with „Mitglied hinzufügen" and the tabs „Mitglieder · Kommandogruppen" (`?unit=`,
`?tab=groups`); rank and group selects save on change, rare and destructive actions sit in the row's
„⋯" menu. The SK member page renders the same roster fragment (name · Rollen as `.matrix-flag`
toggles · Leiter) (REQ-ROLE-004). Beförderung and Einsatzdaten: „Themenbereiche verwalten" and the
admin page „Einsatzdaten verwalten" are master-detail pages (topics | categories with their level
texts; data kinds | list), each selection a deep link (`?topic=`, `?kind=`) and the list driven by
the arrow keys; „Rangvoraussetzungen verwalten" leads with a Rangsprung × Themenbereich matrix;
„Beförderungssystem" shows the rank path as `.ablauf` steps; „Meine Bewertungen" opens with a
progress card to the next rank (REQ-PROMO-002, REQ-PROMO-003). Kartellbank: both account-detail
pages lead with four KPI tiles (Saldo, Ziel, ± 30 Tage, Buchungen) over the tabs „Buchungen" and
„Konto-Info" (bank) or „Ziel & Sichtbarkeit" (org unit); the history has the period segment „30 Tage
· 90 Tage · Zeitraum …" and a „Saldo nach Buchung" column, and no panel collapses any more. The
org-unit bank names its tab „Anträge an unsere Konten", „Laufende Anträge" shows each pending
request's approval path, the staff request queue filters by the status segment „Ausstehend ·
Bestätigt · Abgelehnt · Alle" with „n warten auf dich", and the KRT approval thresholds are drawn as
a tier bar (REQ-BANK-023, REQ-BANK-047).

*Amended 2026-10-04: the terminal's location stands in the route line in brackets (it was a tooltip,
invisible on touch screens; owner decision of the same day).*

*Amended 2026-10-04: the profit calculation's route names its terminals.
`GET /api/v1/materials/profit-calculation` answers each row with `buyTerminalName`,
`buyTerminalLocation`, `sellTerminalName` and `sellTerminalLocation` — the terminals of the chosen
lowest purchase and highest sale price, the first by name on a price tie, a location `null` when the
terminal names no place.*

**Clean-up (phase 4, 2026-10-04):** `inline-migration.css` and its `<link>` in `kernel/fragments/head.html`
are deleted. The last six one-off classes went to the design system or a page rule (the admin bank
page onto the page head with `.alert` notices, the bank account table's number as `.cell-sub`, the
account card's type chip as a `bank.css` rule); the two state classes are `is-hidden` and the
overlay's `is-open`, declared in `styles.css`'s `utilities` layer. Dead rules the redesign left
behind are gone (the bank panel, collapse, facts and filter-bar rules, the 2200 px widening of the
bank account detail — it now stops at `--content-max-data` like every page — `.hangar-filter*`,
the unused `promotion-admin.css` toolbar rules, `.oss-unavailable`), and so are the
`components :: filterToggle` fragment, the stored open/closed state of `krt-filter-panel.js`, the
bank panel-collapse and old request status-filter scripts, and 205 message keys no template, script
or class read any more (the `*.description` subtitles stay). No template or script may name a
`krtm-*` class again (`NoMigrationClassTest`); `data-krtm-width` and `inline-style-apply.js` stay as
the CSP-safe width mechanism.

**Enforced by:** `PagePatternFragmentsRenderTest` (the fragments' markup and ARIA) ·
`PagePatternTestIdsRenderTest` (the smoke-test ids on a representative page per pattern) ·
`NoMigrationClassTest` (no `krtm-*` class, no `inline-migration.css`) · Stylelint
`media-feature-name-value-allowed-list` (breakpoints) · `TouchClassLayoutE2eTest` (touch floors, and
on every swept page: no `.card` scrolling sideways on the phone, the head's primary action in the
first viewport, one `.btn--cta` per `.page-actions`, no nested `.hud-box`, no text in the retired
gray — see REQ-UI-009) ·
**Code:** `static/css/kernel/styles.css` (`components` layer), `kernel/fragments/page-head.html`,
`kernel/fragments/components.html`, `static/js/kernel/krt-overflow-menu.js`, `static/js/kernel/krt-filter-chips.js`,
`frontend/.stylelintrc.json`, `frontend/.stylelintrc.templates.json` · **Related:** REQ-UI-004,
REQ-UI-006, REQ-UI-009, REQ-UI-017, REQ-UI-022, REQ-FE-021

### REQ-UI-028 — Controls carry a fill and a 3:1 border, toggles are `.krt-segmented`, list pages sit on one panel

Segment toggles, the filter button, the title count and row icon buttons stood as black boxes with a
`#282828` hairline on black: 1.42:1, where WCAG 1.4.11 asks 3:1. The 2026-10 design system
(krt-profit/design-system#7, `proposals/list-readability-claude-code-auftrag.md`) fixes it for the
whole app.

1. **Tokens.** `:root` mirrors `--surface-canvas/-panel/-inset/-raised` and `--control-bg`,
   `--control-bg-hover`, `--control-border` (Grau 2), `--control-fg`, `--control-selected-bg/-border/-fg`;
   `--action-neutral` is Grau 2. The app mirrors `tokens.css` rather than loading it (cascade layers,
   REQ-UI-001's single copy of a colour value).
2. **Controls.** `.btn-ghost`, `.btn-quiet-danger`, `.btn-secondary`, text inputs, selects, textareas,
   checkbox/radio, `.pagination .page-btn` and `.chip-select` have the `--control-bg` fill and a 1px
   `--control-border`. A card that is itself a link (the material tiles of `/materials`) carries the
   `--control-border` too and turns orange on hover. The hairline stays for decorative dividers only.
3. **Toggles.** Every period, status or view toggle is `.krt-segmented` with `.krt-segmented__item`
   (and `.krt-segmented__count`): the `segmented` fragment's radios, or links with `aria-current`.
   Selected = tint, orange border and a 3px underline, never a filled orange block.
   Each segment is as wide as its label plus the segment padding; the toggle keeps no gap between
   segments and does not grow in a flex row, also when it is a `<nav>` in the page head.
   On/off options are `label.krt-switch` with `role="switch"`.
4. **List pages.** The head's count is `.count-badge`; toolbar, filter chips, table and pagination sit
   on one `section.list-panel`. An active filter puts `.is-filtered` on the „Filter" button and shows
   the square `.filter-count`.
5. **Muted text** is `--color-gray-2-text`; quality and other data values are `--data-fg`, orange is
   for identity and actions.
6. **Fields keep their distance.** A control never overlaps or touches its neighbour: fields in one
   row keep at least `--space-2` between them, and a row too narrow for its fields wraps or stacks
   by the width of its form (a container query), not only by the viewport. A number input's
   up/down buttons sit `--space-2` away from the value.

**Enforced by:** `PagePatternFragmentsRenderTest`, the per-page pattern render tests,
`SegmentedControlLayoutTest`, `AccessibleTextTintTest`, `ColourTokenCopyTest`, `CustomPropertyExistenceTest`, Stylelint ·
**Code:** `static/css/kernel/styles.css`, `kernel/fragments/components.html`,
`kernel/fragments/page-head.html`, `static/js/kernel/krt-filter-panel.js` ·
**Related:** REQ-UI-001, REQ-UI-006, REQ-UI-027

### REQ-UI-029 — The test system says so in every footer

The test system looks exactly like production, so real work can land there by mistake, or test work
on production.

- **Switch.** `app.instance.test-system` (`APP_INSTANCE_TEST_SYSTEM`, `InstanceProperties`),
  default `false`. Set by configuration only, never guessed from the hostname. The testing host sets
  it to `true`; production leaves it unset.
- **What shows.** With the switch on, the shared footer (`kernel/fragments/footer.html`) opens with
  `.krt-footer-instance`: a filled warning-yellow label with black text, the `⚠` glyph and
  „Testsystem" / "Test system" in uppercase, with a tooltip explaining that this is not the live
  system. The footer's top border turns warning-yellow (`.krt-footer--test-system`). Every page that
  renders the footer shows it, for every role, the public legal pages included.
- **Production shows nothing.** With the switch off the footer markup is unchanged.
- The header and the browser tab title carry no marker.

**Enforced by:** `TestSystemFooterMarkerMvcTest` (both states, DE and EN),
`InstanceMarkerAdviceTest` ·
**Code:** `kernel/layout/InstanceProperties.java`, `kernel/layout/InstanceMarkerAdvice.java`,
`kernel/fragments/footer.html`, `static/css/kernel/styles.css` ·
**Related:** REQ-UI-001

### REQ-UI-030 — A table sorts by its column headers through one shared helper

A results or price table whose order the user may choose sorts by clicking its column headers, and
every such table uses the one kernel helper `krtTableSort` (`static/js/kernel/krt-table-sort.js`)
instead of a page's own copy.

1. **Markup.** A sortable column is a `th[data-sort-col]` carrying `aria-sort`, holding a real
   `<button type="button" class="sort-header" data-sort-key>` with the label and a
   `.sort-header__mark`. The mark is drawn by CSS from the header's `aria-sort` — ↕ in grey for an
   inactive column, ▲ / ▼ in primary orange for the active one — so the script writes no marker
   text. A number column's button aligns right. A column without a meaningful order (a route, a
   free-text note) stays a plain `<th>`.
2. **Cycle.** A click on an inactive column sorts it in its first direction — ascending, or
   descending when the button carries `data-sort-first="desc"`, which a column where "more is better"
   uses; a click on the active column reverses it. Exactly one column is active, and the page's
   default order is that column's state at load.
3. **Values.** Rows sort by raw values from `data-*` attributes, never the localised cell text; text
   compares by locale ignoring case, and a row without a value sorts last in both directions. Ties
   fall back to an order the page names (its default ranking), so a re-sort is stable.
4. **In place.** Sorting moves the existing rows without a reload; a page that renders new rows
   (a recalculation) re-applies the chosen order to them. A page that persists its filters per
   browser (REQ-UI-017) stores the order with them, and a stored order that names no sortable
   column falls back to the default.
5. **Phones.** Below 768px a `.data-table--stack` hides its header row; a stacked table with
   sortable headers keeps them instead, as a wrapping strip of sort buttons above the rows (the
   unsortable headers stay hidden), so sorting works on a phone too. The buttons have the 44px
   touch target of REQ-UI-009.

The profit calculation sorts by Material, je SCU, Marge, Kapital and Max. Profit (default Max.
Profit descending; Kapital starts ascending, Material A–Z, the others descending); its rank number
`#` stays the profit rank of each material whatever the order, so a re-sort never renumbers it. The
material detail price table sorts by Terminal, Ort, Verkauf and Einkauf (default Verkauf
descending; Verkauf starts descending, the others ascending; ties by terminal name, REQ-UI-015). The
material-demand page keeps its own three-step cycle (REQ-ORDERS-034)
until it moves onto the helper.

**Enforced by:** `:frontend:testTableSort` (`scripts/krt-table-sort.test.mjs`),
`MaterialsProfitCalculationRendersE2eTest`, `MaterialDetailPriceSortE2eTest` ·
**Code:** `static/js/kernel/krt-table-sort.js`, `static/css/kernel/styles.css` (`.sort-header`),
`templates/catalogue/materials-profit-calculation.html`,
`static/js/catalogue/materials-profit-calculation.js`, `templates/catalogue/material-detail.html`,
`static/js/catalogue/material-detail.js` ·
**Related:** REQ-UI-009, REQ-UI-017 · **Issues:** #2635, #2634

### REQ-UI-031 — A list the user reorders never needs a drag

A list whose order the user changes (WCAG 2.2 SC 2.5.7, Dragging Movements) offers the move
without a drag, and every such list uses the one kernel helper `krtReorder`
(`static/js/kernel/krt-reorder.js`) with the `components :: reorderControls` and
`components :: reorderLive` fragments instead of a page's own copy.

1. **Controls.** Every movable item carries a visible up and down button
   (`.krt-reorder__btn`, chevron icons, `data-reorder-move="up|down"`) whose label and title name
   the item (`reorder.moveUp` / `reorder.moveDown`, „<Name> nach oben verschieben"). The first
   item's up and the last item's down are `aria-disabled="true"`, not `disabled`, so the focus can
   stay on them. The buttons have the 44px touch target of REQ-UI-009.
2. **Keyboard.** Alt+ArrowUp / Alt+ArrowDown move the item that holds the focus one place
   (`aria-keyshortcuts` on the buttons); the arrow keys without Alt, and with Ctrl, Shift or Meta,
   keep their usual meaning.
3. **Focus.** After the move, and again after the list is re-rendered from the server, the focus
   is on the same control of the moved item.
4. **Announcement.** A polite live region outside the re-rendered fragment (`[data-reorder-live]`)
   announces the item with its old and new position and the length of the list
   (`reorder.announce`) once the server accepted the move; a refused move is not announced.
5. **Persistence.** The page saves a move through its own `krtFetch` write and re-renders the
   list in place (REQ-FE-001); a second move is ignored while one is saving.
6. **Drag stays an extra.** A page may keep drag and drop beside the controls; on a coarse pointer
   (phones, tablets) the helper cancels a drag start, so a swipe scrolls instead of grabbing a row.

The job-order priority queue (REQ-ORDERS-020) and the frequency types of the mission-data admin
page use it; positions there count the reorderable rows of the rendered page.

**Enforced by:** `:frontend:testReorderJs` (`scripts/krt-reorder.test.mjs`),
`OrdersQueueReorderWithoutDragE2eTest`, `AdminFrequencyTypeReorderWithoutDragE2eTest` ·
**Code:** `static/js/kernel/krt-reorder.js`, `templates/kernel/fragments/components.html`
(`reorderControls`, `reorderLive`), `static/css/kernel/styles.css` (`.krt-reorder`),
`static/js/joborder/orders-index-reorder.js`, `templates/joborder/orders-index.html`,
`static/js/catalogue/mission-data.js`, `templates/catalogue/admin/mission-data.html` ·
**Related:** REQ-UI-009, REQ-FE-001, REQ-ORDERS-020 · **Issues:** #2800, #2802

### REQ-UI-032 — Navigation personalisation: favourites, „Mehr", order and the tab bar

> **Status: partially shipped** (ADR-0297, ADR-0298, map #2800). Shipped: the store, the
> Favoriten section (the group, the header star, the cap of 12, the greyed favourite, the deletion
> on load, the 409 hint), hiding with „Mehr", „Anpassen" with the per-link star and hide controls
> and the resets „Alle wieder einblenden" and „Alle Favoriten entfernen", the order of groups and
> links with the up/down controls and „Standardreihenfolge", „Darstellung" with the desktop mode
> and `shortcutsEnabled` (own version, the profile card and the sidebar switch), the phone tab
> bar with its „Tab-Leiste" section in „Anpassen" and the reset „Tab-Leiste zurücksetzen", the
> privacy flows and the metrics. Planned: the start pages of „Darstellung" and the full reset;
> until they ship, the current code stays
> authoritative for them.

A user may adapt the default of REQ-UI-026 on top of it; nothing here changes the default.

**Entry keys.** Every page and every tab of a tabbed page — the personal pages included — has a
stable entry key (e.g. `missions.list`, `orders.demand`) from the frontend entry registry and in
its key format (REQ-UI-026, „One entry registry"), never a URL path. So do the two storage views „Mein Lager" and „Globales Lager": entries that open
the „Lager" tab with that switch selected (REQ-UI-026), keyed and handled like a tab. The backend
checks the key's format only. A renamed page keeps its key or gets an explicit key remap; an
unknown key from a new release (entry or group) is placed after its default predecessor, with no
server-side migration.

**Favourites.**

- A „Favoriten" group at the top of the navigation, rendered only with at least one favourite.
  A favourite is a copy: the entry stays in its task group.
- Pages and tabs can be favourites; entities cannot (REQ-UI-033 finds them).
- **At most 12**, enforced by the backend; at the cap the star is disabled with a hint.
- One list for both modes: admin entries render only in admin mode, member entries only in member
  mode; the cap is shared.
- A favourite unavailable in the active context stays stored, keeps its place and counts towards
  the cap; it renders greyed with „In <Einheit> nicht verfügbar".

**Hiding.**

- Pages only, and no personal page (Profil, Mein Inventar, Meine Blueprints, Verbundene
  Anwendungen). A hidden page moves into a collapsed „Mehr" area at the end of the navigation and
  is shown again from there.
- A hidden page and its tabs leave the quick access; its entity hits and actions stay findable.
- A group whose links are all hidden is not rendered; it reappears in edit mode.
- **Favourite and hidden exclude each other per entry key:** favouriting a hidden entry un-hides
  it, hiding a favourite removes the favourite — one request that writes both sections and checks
  both versions. A favourited tab of a hidden page stays (it has its own key).

**Order.** Groups among themselves and links within their group; a link never changes group.
Stored per mode, only for the keys the user moved. The controls are those of REQ-UI-031 (buttons,
Alt+↑/↓, focus kept, live announcement); drag is only an extra.

**Phone tab bar (slots 1–3 of REQ-UI-026).** A slot holds a page or tab that can be a favourite,
never an entity or an action; one bar per mode.

| State | Filling |
|---|---|
| Automatic (no stored rows; initial) | The favourites of the mode in their order, skipping greyed ones, then the mode's default (REQ-UI-026), no duplicates. No favourites = the default. |
| Explicit (stored rows) | The first edit of any slot stores the shown assignment of all three slots; favourites no longer affect the bar. |

- Choosing an entry that sits in another slot swaps the two slots.
- **Stand-in:** a slot whose entry is unavailable in the active context, or was deleted on loss of
  permission, shows the next favourite not yet shown, else the next free default entry. A
  context-only gap keeps the stored choice, which returns on a context switch; a deleted slot stays
  automatic until the user assigns it again.
- Edited in the „Tab-Leiste" section of „Anpassen": one picker per slot with the favourites first,
  and left/right buttons that swap neighbours. A long-press on a tab opens its picker as an extra
  path only (WCAG 2.5.7).

**Editing.** „Anpassen" at the top of the drawer, the pinned sidebar (the rail opens its overlay,
REQ-UI-026) and the phone menu shows per link star, hide and up/down, and per group up/down.
Outside edit mode every page and tab header carries a star and „Als Startseite festlegen"
(REQ-UI-034). Admin mode works the same way. The edit controls are at least 44 × 44 px up to
1024 px and 32 px above (REQ-UI-009).

**Resets.**

| Control | Clears |
|---|---|
| „Alle wieder einblenden" (in „Mehr") | hidden entries |
| „Alle Favoriten entfernen" | favourites |
| „Standardreihenfolge" | the order of the current mode |
| „Tab-Leiste zurücksetzen" | the tab bar of the current mode (back to automatic) |
| Full reset in the profile, after a confirmation dialog (REQ-UI-013) | all five sections in both modes, both dashboards and both start pages (REQ-UI-034); the desktop mode returns to the drawer |

„Darstellung" has no reset of its own.

**Store.** Server-side in the `navigation` module (ADR-0298), five sections, each with its own
optimistic-lock version, so an edit in one never 409s another:

| Section | Holds | Kept |
|---|---|---|
| Favoriten | up to 12 entry keys in order | once per user |
| Ausgeblendet | hidden page keys | once per user |
| Reihenfolge | moved group and link keys | per mode, own version each |
| Tab-Leiste | slots 1–3 (none = automatic) | per mode, own version each |
| Darstellung | desktop mode, `shortcutsEnabled` (REQ-UI-035), start page per mode | once per user |

- Relational tables, Flyway-owned: one root table `navigation_preferences` per user, which
  `REFERENCES app_user (id) ON DELETE CASCADE` and carries the version counter of every section,
  and every further preference table references that root with `ON DELETE CASCADE`, so account
  erasure removes them (REQ-DATA-021).
- **Data export and account merge:** the preferences are part of the Art. 15 / Art. 20 export in
  their own section `navigationPreferences` (REQ-SEC-058) and of the admin account merge
  (REQ-SEC-046), through the `platform.api` participants `PersonalDataExportSection` and
  `UserReassignmentParticipant`. In a merge the target account's sections win: favourites are
  unioned (the target's first, then the other account's, deduplicated, cut at 12); hidden entries
  are unioned minus every entry that is a favourite; order, tab bar and „Darstellung" (start pages
  included) are the target's.
- **Owner-only:** only the user reads, edits and resets their own preferences; no admin view and
  no admin reset. The admin data export (REQ-SEC-058) and the admin account merge (REQ-SEC-046)
  are the privacy flows, not a preference view.
- **Not audited:** personal UI preferences lie outside every audited area of REQ-AUDIT-001; the
  absence of audit events is deliberate.
- **Deletion on next load:** an entry the user may no longer see user-wide, and a stored key the
  entry registry no longer knows, is detected by the frontend while it builds the navigation and
  deleted from every section through the backend, which holds no key-to-permission knowledge.
  Unavailability in the active context deletes nothing. Regaining a role restores nothing.

**Browser-side state stays in the browser** (REQ-UI-017, ADR-0120): group collapse
(`krt.sidebar.*`), the recent pages (`krt.recent`) and the „Häufig" ranking (REQ-UI-033).

**Sync and conflicts.**

- Every write saves through `krtFetch` and updates the page in place (REQ-FE-001).
- **Preferences are not pushed live.** They are personal, not a shared surface, so the peer
  live-update rule (REQ-FE-015) does not apply: another tab or device shows the change on its
  next page load, an open dashboard on its 10-minute poll (REQ-UI-034).
- A write that meets a version conflict (409) is dropped, only the affected section reloads, and
  the hint „In einem anderen Fenster geändert – bitte erneut versuchen" shows; the other sections
  stay as they are.

**Monitoring:** `basetool_navigation_preference_writes_total{section,outcome}` and
`basetool_navigation_personalised_accounts{section}` (REQ-OBS-011); the endpoints count in
`http_server_requests` by route template, and their 5xx rate alerts above 5 %
(`NavigationEndpointsErroring`).

**Enforced by:** `NavigationPreferencesServiceTest` (order, the version per section,
duplicate keys, owner isolation, deletion of entries), `NavigationPreferencesControllerTest` (the
cap of 12, the key format, the owner from the token, 409), `NavigationFavouritesMvcTest` (Favoriten,
the greyed favourite, the star, the deletion on load, failing closed, the relay),
`NavigationFavouritesReaderTest`, `NavigationCurrentEntryTest`, `NavigationBackendClientTest`,
`NavigationHiddenMvcTest` (Mehr only while a page is hidden, the hidden page and its tabs out of
the quick access, no hide control on personal pages, the relays), `NavigationRegistryTest` (which
entries are pages, which tabs leave with a page), `NavigationMenuTest`,
`NavigationPersonalisationE2eTest` (the star in place, the cap, the 409 hint, hiding with focus and
announcement, favourite and hidden excluding each other, the resets, 44 px controls up to 1024 px
and 32 px above), `NavigationOrderTest` (the scopes, the version per mode, independence from the
other sections, the reset, the deletion of entries, the metric), `NavigationOrderingTest` and
`NavigationMenuOrderTest` (the stored order and the placement of keys the store does not know),
`NavigationOrderMvcTest` (the rendered order, the move controls, the relays),
`NavigationOrderE2eTest` (moving by button and by Alt+Arrow without a drag, focus kept, the live
announcement, reload, „Standardreihenfolge", the 409 hint, 44 px controls up to 1024 px and 32 px
above), `NavigationTabBarResolverTest` (the automatic fill from the favourites, the frozen bar,
the stand-in, the cleared slot, the deletion of lost keys), `NavigationTabBarMvcTest` (the bar
per mode, the pickers, the relay), `NavigationTabBarE2eTest` (the freeze, the swap on a taken
entry and by the neighbour buttons, the long press, the reset, the touch floor, the admin bar),
the export and merge tests of REQ-SEC-058 and REQ-SEC-046 · **to be added:** the
full reset's confirmation and the tests of the sections that follow · **Related:** REQ-UI-026, REQ-UI-031, REQ-UI-033, REQ-UI-034, REQ-UI-035,
REQ-UI-017, REQ-OBS-011, REQ-SEC-046, REQ-SEC-058, REQ-DATA-021, ADR-0297, ADR-0298

### REQ-UI-033 — The quick access finds entities, takes prefixes and ranks by use

Extends the quick access of REQ-UI-026; its dialog, matching, keyboard and rights rules stay.

**Entity search.** `GET /quick-access/search?q=` asks one `QuickAccessSearchContributor` per type
(`results(query, limit)`, the sibling seam of `QuickAccessContributor`, ADR-0282) in parallel;
each type is scoped by its domain's rules and fails closed on its own. Each contributor reads the
backend read that backs the page its hits open (missions and operations over every period,
materials from the visible catalogue, blueprints from the availability overview, ship types from
the unit hangar overview, job orders from the scoped number lookup of REQ-ORDERS-048, org units and
members from the org chart), so a hit is exactly an entity that page would show the caller. A type that fails or takes longer than 2 s yields no hits; a lost session still sends the
caller to the login. An optional repeatable `kind` parameter (the type keys `mission`,
`operation`, `material`, `blueprint`, `ship`, `org_unit`, `job_order`, `member`) narrows the
search; an unknown key asks no type. Each hit is `{kind, href, label, crumb, icon, status, date}`,
with `crumb` the type's name and `status` localized.

| Type | Matched on | Opens |
|---|---|---|
| Mission | name | mission detail |
| Operation | name | operation detail |
| Material | name | material detail |
| Blueprint | name | blueprint detail |
| Ship | ship type | hangar filtered to that type |
| Org unit | name | org chart with the unit highlighted |
| Job order | number (`displayId`, exact or prefix), through a scoped number lookup | job order detail |
| Member | name | org chart with the person highlighted; in admin mode the member edit page |

- A hit carries a slim projection (id, name, status, date). A member finds exactly the people the
  org chart shows them, by the name it shows (display name, the username only when none is set),
  with the rank they hold there as status; no search reveals more than the page it opens. A job
  order hit shows the number only (`#12`) with its status and creation date.
- **Limits:** debounced (250 ms), at least 2 characters (1 digit for a job-order number), at most
  100 characters, 5 hits per type, aborted when the user types on. Each type carries its own
  minimum (`QuickAccessSearchKind.minTermLength`); a type is asked only when the term reaches it.
- **Admin mode:** a member hit with an account carries `adminHref` (`/members/{id}`); the browser
  opens it instead of the org chart while the drawer is in administration mode
  (`krtSearchHitHref`). A holder without an account always opens the chart.
- **Highlight:** the org chart marks and scrolls to the unit or person named by its `highlight`
  query parameter (an org unit id, a user id, or the seat id of a holder without an account).
  Blueprint and ship hits open the overview or unit hangar filtered by the name (`search`).
- **The term stays a query parameter;** it never reaches a path, an MDC field or a metric label.

**Prefixes.** Symbol and word are equivalent; both languages' words are accepted in either UI
language, in any case and with or without a space after the colon; `?` alone shows the prefix
help, one row per prefix, which puts that prefix into the input. A page or action prefix asks the
backend nothing; an entity prefix asks only that type.

| Kind | Symbol | Words (DE / EN) |
|---|---|---|
| Actions | `>` | `aktion:` / `action:` |
| Pages and tabs | `/` | `seite:` / `page:` |
| Job orders | `#` | `auftrag:` / `order:` |
| Members | `@` | `mitglied:` / `member:` |
| Missions | — | `mission:` / `mission:` |
| Operations | — | `operation:` / `operation:` |
| Materials | — | `material:` / `material:` |
| Blueprints | — | `blueprint:` / `blueprint:` |
| Ships | — | `schiff:` / `ship:` |
| Org units | — | `einheit:` / `unit:` |

**Ranking.** One mixed list („Treffer"), scored by match quality plus the „Häufig" weight (pages
and tabs only): a label that starts with the term, then one that contains it, then a breadcrumb
or search-term hit, then an entity hit the backend matched otherwise; on a tie pages come first.
The actions follow in their own group. Entity hits arrive later and the list re-sorts, but the
highlighted hit stays highlighted and Enter opens exactly it. Each entity row shows the type, the
status and the date as its breadcrumb.

**„Häufig".** A per-device ranking in `localStorage` (`krt.frequent`): every page or tab visit
counts, however it was reached; entry keys only; each visit decays over about 30 days. It ranks
typed results and fills the empty state; the navigation never reorders itself by use. A visit
weighs 1 and falls linearly to 0 over 30 days; an entry is „häufig" from a summed weight of 2.
In the ranking the weighted sum lifts a page by up to 1.5 match steps (full lift from a sum of 10), so a
frequently used weaker match can pass a slightly better unused one and a match two steps better
still wins. The store keeps at most 30 visits per entry and 200 entries, and every storage access
is wrapped in a try/catch.

**Empty state.** Favoriten, then Häufig (at most 5), then Zuletzt (`krt.recent`, at most 4),
without duplicates: an entry shown in a higher section is left out of the lower ones. Entries are
identified by their stable entry key, a page without an entry by its path. Entity hits never
enter Zuletzt or Häufig, so no entity or member name reaches `localStorage`.

**Filtering.** A hidden page and its tabs, and every entry unavailable in the active context
(REQ-UI-026), are left out; entity hits and actions of a hidden page stay.

**Actions** (in addition to those of REQ-UI-026):

- Navigation: favourite / unfavourite this page, hide this page, open „Anpassen" (REQ-UI-032).
  Each is offered only where the drawer's own control exists (the star of the page, its hide
  control, the „Anpassen" toggle), and the favourite action is left out at the cap of twelve.
  A favourite toggle is the favourites write of REQ-UI-032: in place, with its 409 handling.
- Create actions for the tabbed pages, each gated like the form it opens.
- **Page context actions:** a page may contribute actions that only navigate or open the dialog or
  form it already has; they never mutate, so confirmation, permission, audit and live update stay
  on the existing path. A page marks the element with `data-palette-action="<name>"` (and an
  optional `data-palette-icon`); choosing the action clicks it.

**Keys in the dialog.** Ctrl+Enter (Cmd+Enter on macOS) opens the highlighted hit in a new browser
tab; Tab accepts a suggested prefix row; Ctrl+D favourites or unfavourites the highlighted page or
tab (intercepted only while the dialog is open). Ctrl+D leaves the dialog open, marks the row with
a star and says the result in a polite status line.

**Monitoring:** `basetool_quick_access_search_total{kind,outcome}` (REQ-OBS-011); the p95 of
`/quick-access/search` alerts above 1 s for 10 minutes (`QuickAccessSearchSlow`).

**Enforced by:** `QuickAccessSearchControllerTest` (parallel contributors, fail closed per type,
limits, term not in the path, the MDC or a label) · `QuickAccessSearchMetricsTest` ·
`MissionQuickAccessSearchContributorTest`, `OperationQuickAccessSearchContributorTest`,
`MaterialQuickAccessSearchContributorTest`, `BlueprintQuickAccessSearchContributorTest`,
`ShipQuickAccessSearchContributorTest`, `OrgUnitQuickAccessSearchContributorTest`,
`MemberQuickAccessSearchContributorTest` (exactly the chart's people),
`JobOrderQuickAccessSearchContributorTest` (the read the page uses) · `QuickAccessFoldTest` · `:frontend:testSearchJs`
(prefix parsing, ranking, the admin-mode target, the decay and the empty-state de-duplication) ·
`NavigationCurrentEntryTest`, `NavigationRenderMvcTest` (which links can be favourites) ·
`NavigationE2eTest` (Ctrl+Enter, Tab, Ctrl+D, the empty state without duplicates, hidden pages
filtered while their hits and actions stay, a page-contributed action) · `QuickAccessSearchE2eTest` (every type found, a job order by number, org unit and member highlight on the chart, entity names kept out of
`krt.recent`, late hits keep the highlight, `?` help, prefixes narrow) ·
`monitoring/prometheus/tests/quick_access_search_slow_test.yml` · **Code:** `shell/web/QuickAccessSearchController.java`,
`shell/web/QuickAccessSearchMetrics.java`, `kernel/layout/QuickAccessSearchContributor.java`,
`kernel/layout/QuickAccessSearchKind.java`, `static/js/kernel/krt-search.js`,
`static/js/kernel/krt-frequent.js`, `static/js/kernel/krt-palette-personalise.js`,
`static/js/kernel/krt-palette.js` · **Related:** REQ-UI-026, REQ-UI-032, REQ-SEC-007, REQ-OBS-011,
ADR-0282, ADR-0297

### REQ-UI-034 — The home page is a personalisable widget dashboard

> **Status: partially shipped** (ADR-0299, map #2800). Shipped: the member dashboard with its
> default, the framework (two columns, up/down, column switch, remove, catalogue and options
> dialogs, „Standard wiederherstellen", the cap of 12, the layout version per mode and the option
> version per instance, the deletion on role loss, live update or the ten-minute poll, the
> one-time hint) and the widgets Nächste Tage, Nächster Einsatz, Hangar-Bereitschaft, Information
> (with the inline editor for administrators), Favoriten and Benachrichtigungen, with the options
> of the matrix below. Planned: the other catalogue widgets and their options, the admin dashboard
> and the start page; until they ship, the current code stays authoritative for them.

The home page is a dashboard of widgets the user composes, in member and admin mode, each with its
own layout. The logo always leads to the dashboard of the current mode. The Fan Kit band of
REQ-UI-018 stays on it.

**Layout.** Two columns, 2 : 1 (pattern B, REQ-UI-027); each instance sits in one column. On the
phone one column, the wide column first, then the narrow one; one stored order for every device.
An empty dashboard shows an empty state with „Widget hinzufügen".

**Defaults.**

| Mode | Wide column | Narrow column |
|---|---|---|
| Member | Nächste Tage (7 days), Information | Favoriten, Benachrichtigungen |
| Admin | Hub | Offene Löschanträge, Discord-Registrierungen, Sync- & Importstatus, Offene Nutzungsbedingungen |

Favoriten without favourites explains how to set one. The Hub holds the 13 admin links of
REQ-UI-026 as tiles and is an ordinary, removable widget.

**Instances.** A widget may be added more than once; at most **12 instances per mode**, enforced
by the backend. Each instance renders only for a user allowed to read its data.

**Catalogue and options.** Default in bold. *Rows* is 3 / 5 / 10, default **5** unless stated.
*Unit* defaults to **follows the active context** and can be pinned to one unit; following a
context with no unit pinned („Alle meine Einheiten") it shows the union, each row labelled with its
unit.

| Mode | Widget | Shows | Options |
|---|---|---|---|
| Member | Nächste Tage | the missions of the coming days | unit; 3 / **7** / 14 days; rows |
| Member | Nächster Einsatz | the user's next mission | — |
| Member | Hangar-Bereitschaft | readiness of the user's hangar | — |
| Member | Aufträge zu bearbeiten | orders the user's unit processes | unit; status **Offen + In Bearbeitung** / Offen / In Bearbeitung; rows |
| Member | Meine Aufträge | orders the user requested | status **Laufend** / Abgeschlossen / Alle; rows |
| Member | Ich bearbeite | orders the user is an assignee of, by priority (REQ-ORDERS-047) | status as „Aufträge zu bearbeiten"; rows |
| Member | Materialbedarf | open material demand | unit; rows |
| Member | Raffinerie: fertig | finished refinery jobs | rows |
| Member | Bestand niedrig | low minimum stock levels, by fill ratio, empty first (REQ-INV-068) | unit; rows |
| Member | Materialbörse | open market entries | status **Angebote + Gesuche** / Angebote / Gesuche; rows |
| Member | Bedarf & Spenden | open supply needs | unit; status **Alle offenen** / Ungedeckt / Teilweise gedeckt; rows |
| Member | Meine Bankanträge | the user's bank requests | status **Offen** / Entschieden / Alle; rows, default **3** |
| Member | Bank | balances of the viewable org-unit bank accounts in the backend's order — KRT first, then the active context's, then by account number; page 0 with the row count as size (REQ-BANK-064) | rows, default **3** |
| Member | Information | the announcement; administrators edit it inline | — |
| Member | Benachrichtigungen | the user's notifications | status **Ungelesen** / Alle; rows |
| Member | Favoriten | the user's favourites | — |
| Admin | Hub | the admin links as tiles | — |
| Admin | Offene Löschanträge | pending deletion requests | rows |
| Admin | Discord-Registrierungen | pending registrations | rows |
| Admin | Offene Nutzungsbedingungen | members without accepted terms | rows |
| Admin | Sync- & Importstatus | the latest sync and import runs | range 24 h / **7 Tage** / 30 Tage |

A widget with a list links „Alle anzeigen" to its page or tab („Ich bearbeite" to that segment,
„Bestand niedrig" to „Mindestbestände", „Bank" to `/org-unit-bank`). Widgets carry no action
buttons. „Bank" has no unit option — its set ignores the context, only its order uses it — and is
not part of the default. A new option is a change to this table.

**Availability.**

- An instance pinned to a unit unavailable in the active context renders greyed with a hint, like
  an unavailable favourite.
- Losing user-wide the role a widget needs deletes its instances on the next load (REQ-UI-032).

**Data and freshness.** Widgets read the existing endpoints (a page total serves as a count) and
new summary reads where a list would otherwise be loaded whole (hangar readiness, next mission)
or data is new (REQ-ORDERS-040 `assignedToMe`, REQ-INV-068, REQ-BANK-064). Each widget loads and
fails on its own, never blocking the page. A widget whose area has a live-sync topic updates live
(REQ-FE-015); the others poll every 10 minutes, only while the tab is visible, staggered, and
reload at once when the tab becomes visible again.

**Store.** In the `navigation` module, owner-only and not audited, like REQ-UI-032: the layout
(instances, column, order) is one section per mode with its own version, and the options of each
instance carry their own version, so an option change never collides with a reorder. Conflicts and
the absence of a live push follow REQ-UI-032. The dashboards are part of the data export's
`navigationPreferences` section (REQ-SEC-058); in an account merge (REQ-SEC-046) both modes'
dashboards — layout and instance options — are the target account's.

**Editing.** „Anpassen" in the page head: per instance up/down (REQ-UI-031), column switch, remove
and an options dialog; a catalogue dialog adds; „Standard wiederherstellen" restores the mode's
default. Dialogs follow REQ-UI-013; edit controls follow the size rule of REQ-UI-032.

**Start page.**

- One per mode: any page or tab that can be a favourite, personal pages included. Chosen under
  „Darstellung" in the profile and with „Als Startseite festlegen" next to the header star; stored
  in „Darstellung" (REQ-UI-032).
- It opens after a plain login, on launching the installed app (REQ-UI-020) and, for the admin
  start page, on switching into admin mode. A deep link that triggered the login wins.
- A start page no longer permitted or unavailable in the active context falls back to the
  dashboard.

**Hint.** A one-time, dismissible hint on the dashboard points to „Anpassen" (navigation and
dashboard). Its dismissal is kept in the browser; it also goes once the user sets a favourite or
changes the dashboard.

**Monitoring:** the widget and layout endpoints count in `http_server_requests` by route template
and alert on a 5xx rate above 5 %; layout and option writes count in
`basetool_navigation_preference_writes_total` (REQ-OBS-011).

**Enforced by:** `DashboardServiceTest` (the default, the cap of 12 per mode, layout and option
versions, owner isolation, deletion by widget type, the write counters),
`NavigationDashboardControllerTest` (authentication, the admin mode's gate, 409, 404),
`DashboardReaderTest` and `DashboardRenderMvcTest` (the board per stored layout, greyed pinned
instances, the empty and error states, deletion on role loss, the hint, the catalogue),
`DashboardUpcomingDaysWidgetMvcTest`, `DashboardSummaryWidgetsMvcTest` (Nächster Einsatz and
Hangar-Bereitschaft: the row, the counts, the empty states, the member's hangar room),
`DashboardLayoutControllerTest`, `DashboardLayoutBackendClientTest`, `DashboardBackendClientTest`
(the two summary reads), `DashboardE2eTest` (edit mode, catalogue and options, restore, the summary
widgets, an option change after a reorder, phone single column, the 10-minute poll on visibility,
the hint, deletion on role loss), the export
and merge tests of REQ-SEC-058 and REQ-SEC-046 · **to be added:** `StartPageRedirectMvcTest` (start
page per mode, deep-link precedence, fallback) and the tests of the widgets that follow ·
**Related:** REQ-UI-018,
REQ-UI-026, REQ-UI-027, REQ-UI-032, REQ-ORDERS-047, REQ-INV-068, REQ-BANK-064, REQ-HANGAR-016,
REQ-MISSION-031, REQ-SEC-046, REQ-SEC-058, ADR-0299

### REQ-UI-035 — Favourites and the dashboard have keyboard shortcuts

> **Status: shipped** (ADR-0297, map #2800).

| Keys | Opens |
|---|---|
| Alt+1 … Alt+9 | favourite 1–9 of the Favoriten group (pages and tabs); favourites 10–12 get no key |
| Alt+0 | the home dashboard (REQ-UI-034) |

- Only the top digit row counts (`KeyboardEvent.code` `Digit0` … `Digit9`), never the numpad
  (Alt+numpad is Windows Alt-code entry); matching on `code` makes Option+digit work on macOS.
- Numbering follows the position in the Favoriten group. If favourite *n* is greyed in the active
  context or does not exist, nothing navigates and a short toast says so.
- Inactive while a dialog is open (the topmost-dialog check of `Strg K`); active inside input
  fields.
- **Member mode only:** in admin mode Alt+0 … Alt+9 do nothing. No phone counterpart.
- **Switch:** `shortcutsEnabled` in „Darstellung" (REQ-UI-032), one value per user, default on.
  It is a switch in the profile card „Darstellung", written in place with the section's version
  (`nav-appearance.js`), and stays off while the stored section could not be read.
- **Discoverability:** tooltip and `aria-keyshortcuts` on the favourite links and the dashboard
  link only where the key currently works. A „Tastenkürzel" dialog (REQ-UI-013), opened from the
  quick access and the profile menu, always lists `Strg K` and the dialog keys of REQ-UI-033; for
  Alt+0 … Alt+9 it shows the keys, or „ausgeschaltet" with a link to „Darstellung", or „nur im
  Mitgliedermodus" in admin mode.
- Not shortcuts: the desktop mode, „Anpassen" (both stay buttons and quick-access actions), and
  „back" (the browser's Alt+Left). No single-character shortcut, so WCAG 2.1.4 does not apply.

*Shipped:* `krt-shortcuts.js` holds the key matching and the decision per slot, `nav-shortcuts.js`
reads the favourites of the member group from the navigation, sets the tooltip and
`aria-keyshortcuts` of the first nine favourites and of the home link where the keys work, and
opens the dialog `nav-shortcuts` (`kernel/fragments/nav-shortcuts.html`) from the profile menu,
the rail's avatar flyout and the quick-access action „Tastenkürzel anzeigen".

**Enforced by:** `:frontend:testShortcutsJs` (digit-row `code` only, dialog gate, switch, admin
mode) · `NavigationDesktopModesMvcTest` (the switch on the state, the dialog, its openers, the
relay) · `NavigationAppearanceServiceTest`, `NavigationPreferencesControllerTest` (the stored
switch) · `NavigationShortcutsE2eTest` (Alt+1 opens favourite 1, Alt+0 the dashboard, toast on a
greyed or empty slot, rest while a dialog is open and in admin mode, the profile switch, the
„Tastenkürzel" dialog) · `DialogA11yE2eTest` (the dialog contract) · **Related:** REQ-UI-026,
REQ-UI-032, REQ-UI-033, REQ-UI-034, ADR-0297

### REQ-UI-037 — Number fields use the separators of the page language while typing

Every field that takes a number shows it with the thousands and decimal separators of the page
language — `1.999.999,0` in German, `1,999,999.0` in English — and inserts them **while the user
types**, without breaking the input.

- **Which fields.** Every number input is an `<input type="text" data-number-input>`; no template or
  script renders `type="number"`. That covers aUEC amounts (bank, org-unit bank, purchasing,
  refinery costs and ore sales, mission finance, supply need price, Lager sale amount), material
  quantities in SCU and pieces (REQ-INV-042/-043, which keep `data-scu-decimal` for their
  positivity rule), refinery input and output units, qualities, counts, days, percentages, sort
  orders, ranks, durations, mission frequencies and the rows that page scripts build.
- **Decimals and sign come from the field.** A whole `step` (or none) takes whole numbers;
  `step="0.01"` two decimals, `step="0.001"` three, `step="any"` three, so an SCU amount can always
  carry three decimals. A minus sign is accepted only when `min` is absent or negative. A field
  whose `step`, `min` or `max` a script changes is re-read at once.
- **Separators follow the language.** `kernel/krt-number-input.js` reads them from
  `<html lang>` through `Intl.NumberFormat`. Only the language's decimal separator starts the
  fraction; its group separator is dropped wherever it is typed, so `1,000` typed into an English
  decimal field stays one thousand. A pasted number written with the other convention (`1234.56`
  pasted into a German field) is read by its decimal point when one or two digits follow the only
  separator.
- **Typing is never interrupted.** The field reformats on every input event and puts the caret
  back after the same digit; Backspace or Delete on a separator removes the digit beside it; an IME
  composition is formatted once it ends; other characters are dropped; at most 15 integer digits.
  Typing stops at the field's decimals; a pasted or assigned number with more is rounded half up
  (REQ-INV-042).
- **Readers see the canonical value.** The field's `value` and `valueAsNumber` return the
  canonical number (`1999999`, `-3`, `1234.5`), assigning `value` formats it, and every `FormData`
  built from the form — a native submit included — carries the canonical value, so no page script,
  proxy or controller parses a grouped or localized string.
- **Validation.** `required` stays native; a whole `step`, `min` and `max` are checked on the
  canonical value with localized messages (`number.validation.*`), the bound shown in the page
  language; an SCU or piece field checks positivity instead (REQ-INV-042/-043).
- **Dynamic markup.** Fields in fragments swapped in later or built by a script are enhanced as they
  appear; a script that builds one and sets its value calls `krtNumberInput.enhance` first; a form
  reset re-formats them.

**Acceptance**

- [x] Typing `1999999,0` on a German page shows `1.999.999,0`, `1999999.0` on an English page shows
  `1,999,999.0`; both send `1999999.0`.
- [x] An SCU field takes `12.345,678` (German) or `12,345.678` (English) and sends `12345.678`.
- [x] Inserting a digit in the middle keeps the caret after it; Backspace after a separator removes
  the digit before it.
- [x] A sort-order field without `min` takes `-1.234`; a field with `min="0"` drops the minus.
- [x] `value`, `FormData` and a native GET submit carry the canonical value.
- [x] `0` in a field with `min="1"`, `1500,5` in a whole field and `0` in an SCU field are invalid
  with the localized message.

**Enforced by:** `:frontend:testNumberFormatJs` (separators per language, sign, decimals, rounding,
caret, separator deletes) · **Code:** `static/js/kernel/krt-number-format.js`,
`static/js/kernel/krt-number-input.js`, `kernel/fragments/head.html` · **Related:** REQ-BANK-065,
REQ-INV-042, REQ-INV-043, REQ-NOTIF-031, REQ-FE-018, ADR-0305

### REQ-UI-038 — Numbers on the pages use the separators of the page language

Every number a page prints — amounts, balances, prices, quantities, qualities, percentages,
frequencies, multipliers — uses the thousands and decimal separators of the page language, the same
as the number fields of REQ-UI-037: `1.999.999,0` in German, `1,999,999.0` in English.

- **Templates** format through `#numbers.formatInteger` / `formatDecimal` with `'DEFAULT'`, which
  follows the request's language; no template passes `'POINT'` or `'COMMA'`. Amounts and quantities
  group thousands; only mission frequencies keep `'NONE'`, since a channel has at most three integer
  digits.
- **Scripts** format through `formatNumber` of `kernel/krt-number-format.js` or
  `toLocaleString(document.documentElement.lang)`; none hard-codes `de-DE` or uses `toFixed` for a
  number it shows.
- **Server-side text** a page prints (`MaterialTerminalPrices`, notification amounts of
  REQ-NOTIF-031) uses the request's or member's locale.
- **Out of scope:** the PDF statements and exports, which render German labels by design (bank
  spec, out of scope).

**Acceptance**

- [x] On an English page a pending 600 000 aUEC deposit shows `600,000 aUEC` and
  `With fee: 603,000 aUEC`; on a German page `600.000 aUEC` and `Mit Gebühr: 603.000 aUEC`.
- [x] A required material amount of 1000 SCU renders `1.000,000` on a German page.
- [x] A mission frequency renders `121,50` on a German page.

**Enforced by:** `BankRequestsPatternRenderTest` (German and English), `JobOrderMaterialDemandRenderTest`,
`MissionPageControllerMvcTest`, `NotificationRenderAmountsTest` · **Code:** the templates under
`frontend/src/main/resources/templates`, `static/js/kernel/krt-number-format.js#formatNumber`,
`catalogue/web/MaterialTerminalPrices` · **Related:** REQ-UI-037, REQ-NOTIF-031, REQ-BANK-065,
ADR-0305

## Out of scope

The brand assets themselves — their artwork, variants and rasters — are authored and versioned in
the design skill `assets/`; this spec governs only *where the app applies them* (REQ-UI-019). The
desktop SC Extractor's GUI design lives in
[`docs/archive/DESIGN_SC_EXTRACTOR.md`](../archive/DESIGN_SC_EXTRACTOR.md).

**Material-amount input fields** (SCU/PIECE precision, rounding, positivity) are
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
