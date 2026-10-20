# Accessible reorder and rail navigation patterns

> **Doc type:** Research note — a snapshot of findings, not a spec and not a decision. Collected
> 2026-10-20 for wayfinder ticket #2802 (map #2800). Nothing here is binding; the navigation
> concept that builds on it gets its own `REQ-UI-*` amendment and ADR.
>
> **Question:** what are the accessible, primary-source-backed patterns for (a) reordering list
> items by keyboard, touch and pointer without drag-only interaction, and (b) a collapsible icon
> rail with flyouts (focus handling, labels, touch targets)? What do ADR-0176, ADR-0177, ADR-0240
> and REQ-UI-026 already demand?

## 1. Sources

| Source | Kind | Used for |
|---|---|---|
| [WCAG 2.2 Understanding 2.5.7 Dragging Movements][u257] | W3C, normative SC + informative text | Reorder: what a non-drag alternative must be |
| [WCAG 2.2 Understanding 2.5.8 Target Size (Minimum)][u258] / [2.5.5 (Enhanced)][u255] | W3C | Target sizes |
| [WCAG 2.2 Understanding 1.4.13 Content on Hover or Focus][u1413] | W3C | Rail tooltips and flyouts |
| [WCAG 2.2 Understanding 2.4.11 Focus Not Obscured (Minimum)][u2411] | W3C | Fixed rail, tab bar, flyouts |
| [WCAG 2.2 Understanding 2.5.3 Label in Name][u253] | W3C | Icon-only rail entries |
| [WCAG 2.2 SC 4.1.3 Status Messages][sc413] | W3C | Announcing a completed move |
| [APG: Example Listboxes with Rearrangeable Options][apg-rl] | W3C WAI-ARIA Authoring Practices | Reorder keyboard model |
| [APG: Example Disclosure Navigation Menu][apg-dn] | W3C APG | Rail/flyout semantics |
| [WAI tutorial: Fly-out Menus][wai-fly] | W3C WAI tutorial | Flyout timing and toggles |
| [WAI-ARIA 1.1 `aria-grabbed`][aria-grabbed] | W3C spec | Deprecated drag ARIA |
| [GOV.UK Publishing Components: Reorderable list][gov-rl] | UK government component library | Reorder reference implementation |
| [DfE design history: Manage related tasks][dfe] | UK government design history | Field use of up/down links |
| [Atlassian Pragmatic drag and drop: accessibility guidelines][atl-a11y] / [design guidelines][atl-dg] | Design system | Menu-based move actions, announcements, focus |
| [Carbon UI shell left panel: accessibility][carbon] | Design system | Side nav / rail keyboard model |
| [Material Components Android: Navigation rail][m3-rail] | Design system (implementation docs) | Rail destination count, labels, collapsed/expanded |
| [NN/g: Icon Usability][nng-icons] | Published usability research | Icon-only labels |

The Material Design 3 guideline site (m3.material.io) renders client-side and could not be read;
the implementation docs above were used instead. The Salesforce UX article "4 Major Patterns for
Accessible Drag and Drop" (2017) returned HTTP 403 and is **not** relied on.

## 2. Reordering without drag-only interaction

### 2.1 What WCAG 2.2 requires

- **SC 2.5.7 Dragging Movements (AA):** every function that uses dragging must be achievable
  "with a single pointer without dragging", unless dragging is essential. Reordering a list is not
  essential dragging. [u257]
- **Keyboard support alone does not satisfy 2.5.7.** The Understanding text is explicit that
  keyboard equivalence "does not automatically meet this success criterion"; 2.1.1 (Keyboard) and
  2.5.7 are evaluated independently. The alternative must be clickable/tappable. [u257]
- **Sanctioned alternatives** named in the Understanding text: tap/click an item to reveal adjacent
  up/down controls (the sortable-list example); select an item, then tap a target (kanban example);
  a pop-up menu to move an item; a text input for the value. A swipe or other path-based gesture
  does not qualify as the alternative. [u257]
- **SC 4.1.3 Status Messages (AA):** the result of a move ("moved to position 3 of 7") must be
  announced without moving focus — a live region. [sc413]
- **Drag ARIA is dead:** `aria-grabbed` and `aria-dropeffect` were deprecated in WAI-ARIA 1.1;
  no current pattern uses them. [aria-grabbed]

### 2.2 The established patterns

**A. Move buttons (up/down, optionally top/bottom) — the baseline.**

- *APG rearrangeable listbox:* visible "Up"/"Down" buttons grouped in a toolbar (one tab stop),
  plus `Alt+↑` / `Alt+↓` shortcuts advertised with `aria-keyshortcuts`. After a move with the
  shortcut, **focus stays on the moved option**, so the user can move it again and a screen reader
  user hears its new position. A live region ("Last change: …") confirms each action. [apg-rl]
- *GOV.UK reorderable list:* drag **and** up/down buttons. Acceptance criteria for the buttons: be
  keyboard focusable, "inform the user about which item they operate on", "preserve focus after
  interacting with them". Drag is **disabled on small viewports** "to prevent being triggered when
  scrolling on touch devices". Without JavaScript, a numeric position input per item is shown.
  [gov-rl]
- *DfE (GOV.UK services):* used move up/down links as "the most user-friendly option"; the only
  alternative considered was a numbered list. No user-research data published. [dfe]

**B. Move actions in a menu — for rich items or many destinations.**

- *Atlassian:* every draggable item gets a non-drag route to the same outcome. Put move actions in
  the item's existing "more" (…) menu; if there is none, turn the drag handle itself into a menu
  button. Use inline menu items for simple outcomes ("Move to top"), a dialog with a form when the
  outcome needs input. Names include the item ("Move task 'clean dishes' to top of backlog").
  Announce with a live region including "the name of the item being moved, as well as its old and
  new position". Return focus to the original trigger; if it no longer exists, pick a sensible
  target. [atl-a11y]
- *Atlassian advises against arrow-key "grab and move" modes*: hard to use without seeing the
  interface, they require JAWS users to switch modes, and they cost significant code. [atl-a11y]
- Visible drag handles are preferred; a handle shown on hover must also show on `:focus-within`.
  [atl-dg]

**C. Edit mode with explicit positions** — the GOV.UK no-JS fallback (a position field per item)
and the 2.5.7 "text input" alternative. Robust, but the heaviest interaction; best as fallback.
[gov-rl] [u257]

### 2.3 Consensus

1. Drag is an **enhancement**, never the only path. Buttons or a menu carry the function.
2. Each move control names its item (accessible name includes the item label).
3. **Focus follows the moved item** (or stays on the control that moved it); never jumps to the
   top of the list.
4. Every completed move is **announced** via a polite live region with item, old and new position.
5. On touch, drag-to-reorder fights scrolling; GOV.UK simply turns it off on small screens.
6. Avoid ARIA drag attributes and custom grab modes; prefer `Alt+↑/↓` shortcuts on a focused item
   plus visible buttons.

## 3. Collapsible icon rail with flyouts

### 3.1 Semantics: navigation is not an ARIA menu

- APG's site-navigation example uses **disclosure buttons**, not `role="menu"`/`menubar`, because
  site navigation "does not need all the keyboard interactions specified by the menu and menubar
  pattern". Toggles are `<button aria-expanded aria-controls>`; Tab/Shift+Tab move through buttons
  and open lists; Escape closes the open list and **returns focus to its button**; arrow keys are
  optional extras, never a replacement for Tab; the current link carries `aria-current="page"`.
  Everything sits in a `<nav>` landmark with nested lists. [apg-dn]
- Carbon's side nav matches this: `<nav aria-label="Side navigation">`, nested `<ul>`, sub-menus
  as `<button aria-expanded>`, active link `aria-current="page"`. [carbon]

### 3.2 Flyouts

- **Do not open on Tab focus.** "Submenus should not open when using the tab key" — otherwise a
  keyboard user steps through every submenu. Open on click/Enter/Space of a toggle. [wai-fly]
- If the parent item also navigates, give it a **separate toggle button** whose name includes the
  parent ("show submenu Flotte & Logistik"). [wai-fly]
- Hover-opened flyouts need a **close delay** (the tutorial uses one second) for imprecise
  pointers. [wai-fly]
- Any hover/focus-revealed content (tooltip or flyout) must meet **SC 1.4.13**: *dismissible*
  without moving pointer or focus (Escape), *hoverable* (the pointer can move onto it), and
  *persistent* until dismissed or no longer relevant. Native `title` tooltips are outside the SC
  but are also not reachable by keyboard or touch. [u1413]
- People with reduced dexterity struggle with flyouts; the tutorial recommends repeating submenu
  items on the parent's own page as a second route. [wai-fly]

### 3.3 Labels on an icon rail

- **Visible text labels.** NN/g: "a text label must be present alongside an icon"; labels revealed
  only on hover render icons "completely meaningless", and hover does not exist on touch. Few
  icons (home, search, print) are near-universal. [nng-icons]
- Material's rail pairs every destination with "an icon and a text label"; the collapsed rail
  holds "three to no more than seven" top-level destinations, sub-menus show only when expanded,
  and the expanded rail is a non-modal replacement for the drawer. [m3-rail]
- An icon-only control still needs an accessible name (4.1.2); where text is visible, the name must
  contain it (2.5.3, Level A) — so a label beside or under the icon is the name. [u253]

### 3.4 Focus handling for rail, drawer and flyouts

- **Carbon rail:** expands when reached by keyboard or hovered; "when users tab out of the left
  panel, the side rail slides out of view". Activating a link moves focus to the top of the page.
  The overlay (non-persistent) panel opens from a hamburger and must be activated to close. [carbon]
- **SC 2.4.11 Focus Not Obscured (AA):** a fixed rail, header or tab bar must not entirely cover
  the focused element — use `scroll-padding` (technique C43). User-opened persistent content such
  as side navigation passes if it pushes content aside, takes and constrains focus until dismissed,
  expands into empty space, or hides when focus leaves. A non-modal overlay that keeps covering
  focusable content is at risk (F110). [u2411]

### 3.5 Target size

- **SC 2.5.8 (AA): 24 × 24 CSS px**, or 24 px spacing circles that do not intersect; exceptions for
  inline links, UA-default controls, essential presentation, and an equivalent control on the page.
  [u258]
- **SC 2.5.5 (AAA): 44 × 44 CSS px.** [u255]
- The Basetool's own floor (44 px on touch, 32 px dense row actions on mouse classes) already
  exceeds 2.5.8 everywhere; 2.5.8 adds nothing new.

## 4. What the Basetool already demands

| Rule | Demand relevant here |
|---|---|
| **REQ-UI-009** | 44 px minimum target on phone and tablet; `.btn` keeps 44 px on every class; only `.btn-xs` / `.btn-icon` row actions drop to 32 px on mouse classes (full floor on touch since 2026-10-19). |
| **ADR-0176** | The 44 px floor is a **zero-specificity default** on `button, [role=button], summary, a.btn, input, select, textarea` at `width <= 1024px`; a control opts out only by declaring `--touch-target-dense`. Any new rail icon, move button or flyout toggle is born compliant if it is one of those elements — **a bare `<a>` rail link is not in the selector** and needs its own size. `TouchClassLayoutE2eTest` measures it. |
| **ADR-0177 / REQ-UI-013** | Exactly one dialog shape: native `<dialog>` via `krtModal`, `showModal()`, Escape clicks the dialog's own ✕, focus lands on a field (never a button), accessible name on the `<dialog>`. A "move to …" or "edit navigation" dialog must use it; a flyout is **not** a dialog and must not be built as one (it would make the page inert). The live-sync busy probe sees open dialogs only. |
| **ADR-0240** | Rejected "a fixed, always-visible sidebar or an icon rail" because it costs horizontal space on every page and still needs a drawer on tablet and phone. A rail would have to supersede that part. The closed drawer is `inert`; scripts that set `inert` restore only what they set. |
| **REQ-UI-026** | Group heads are `<button aria-expanded>`; current page `aria-current="page"`; opening the drawer focuses the filter, closing returns focus to the opener; Escape closes; quick access is a combobox over a listbox with `aria-activedescendant`; `prefers-reduced-motion` removes motion; phone tab bar rows 44–48 px; bars pinned to the bottom sit above `--krt-tabbar-height`; a drop released over the tab bar is cancelled. |
| **REQ-MISSION-005** | Precedent: the crew board offers drag **and** a click fallback (select, then click target) **and** keyboard — exactly the 2.5.7 kanban pattern. |

**Existing gaps found while reading** (not in this ticket's scope, but the same pattern fixes
them): the order-priority reorder on `/orders` (`orders-index-reorder.js`) and the admin
mission-data reorder (`mission-data.js`) are HTML5 drag-only with no button, menu or keyboard
path — the order detail shows the priority read-only. Both fail SC 2.5.7 today.

## 5. Implications for the Basetool

1. **Favourite reorder = move buttons first, drag second.** Each favourite row gets a visible
   „Nach oben" / „Nach unten" pair (names include the entry: „Lager nach oben"), `Alt+↑/↓` on the
   focused row with `aria-keyshortcuts`, focus staying on the moved row, and a polite live region
   „Lager: Position 2 von 5". Drag is optional polish and off at `width <= 768px`, as GOV.UK does.
   This satisfies 2.5.7, 2.1.1 and 4.1.3 with the fewest moving parts and no grab mode.
2. **Pin/unpin and "move to tab bar" belong in an item menu or a toggle**, not a drag target: a
   star toggle (`aria-pressed`) to favourite, and a short menu for „In Tab-Leiste" when the phone
   bar becomes configurable (map: not yet specified).
3. **Build the reorder once as a shared component** (ADR-0176/0177 spirit: one shape, a default
   not a list) and retrofit `/orders` priority and admin mission-data onto it, closing their 2.5.7
   gap.
4. **If an editing surface needs a dialog, it is the one dialog shape** (REQ-UI-013); focus lands
   on the first field, the move buttons are inside the dialog's layer, and live sync sees it.
5. **A rail is only defensible with visible labels.** NN/g and Material both argue for icon +
   label; icon-only with hover tooltips fails touch users and puts every tooltip under SC 1.4.13.
   A labelled rail is close to today's 320 px drawer in width, which weakens the case ADR-0240
   already rejected. If a rail is still wanted, cap it at ~5–7 top-level entries (Material's
   collapsed limit), which fits the map's „~10 entries" target only together with favourites.
6. **Flyouts are disclosures, not menus.** `<button aria-expanded aria-controls>` per group, open
   on click/Enter/Space (never on Tab focus), Escape closes and returns focus to the toggle,
   `aria-current="page"` on the current link, no `role="menu"`. This is REQ-UI-026's existing group
   model, moved sideways — no new keyboard model to learn or test.
7. **Fixed chrome and focus.** Any always-visible rail must pass 2.4.11: add
   `scroll-padding-inline-start` equal to its width (as `main` already reserves the tab bar), and a flyout that stays open
   after focus leaves must close or be dismissible by Escape.
8. **Targets:** rail entries and flyout toggles must be `<button>` or carry a size rule for `<a>`
   (ADR-0176's selector does not include bare links); 44 px on touch, ≥ 32 px only where
   REQ-UI-009 grants the dense exemption. WCAG 2.5.8's 24 px is already exceeded.
9. **Tests to extend:** `TouchClassLayoutE2eTest` (new controls in the measured set),
   `DialogA11yE2eTest` (any edit dialog), `NavigationE2eTest` (reorder by buttons and `Alt+↑/↓`,
   announcement text, focus after move, Escape on flyouts).

[u257]: https://www.w3.org/WAI/WCAG22/Understanding/dragging-movements.html
[u258]: https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum.html
[u255]: https://www.w3.org/WAI/WCAG22/Understanding/target-size-enhanced.html
[u1413]: https://www.w3.org/WAI/WCAG22/Understanding/content-on-hover-or-focus.html
[u2411]: https://www.w3.org/WAI/WCAG22/Understanding/focus-not-obscured-minimum.html
[u253]: https://www.w3.org/WAI/WCAG22/Understanding/label-in-name.html
[sc413]: https://www.w3.org/TR/WCAG22/#status-messages
[apg-rl]: https://www.w3.org/WAI/ARIA/apg/patterns/listbox/examples/listbox-rearrangeable/
[apg-dn]: https://www.w3.org/WAI/ARIA/apg/patterns/disclosure/examples/disclosure-navigation/
[wai-fly]: https://www.w3.org/WAI/tutorials/menus/flyout/
[aria-grabbed]: https://www.w3.org/TR/wai-aria-1.1/#aria-grabbed
[gov-rl]: https://components.publishing.service.gov.uk/component-guide/reorderable_list
[dfe]: https://design-histories.education.gov.uk/tsi/manage-related-tasks
[atl-a11y]: https://atlassian.design/components/pragmatic-drag-and-drop/accessibility-guidelines/
[atl-dg]: https://atlassian.design/components/pragmatic-drag-and-drop/design-guidelines/
[carbon]: https://carbondesignsystem.com/components/UI-shell-left-panel/accessibility/
[m3-rail]: https://github.com/material-components/material-components-android/blob/master/docs/components/NavigationRail.md
[nng-icons]: https://www.nngroup.com/articles/icon-usability/
