# ADR-0240 — Navigation is a structured drawer, a quick access and a phone tab bar

- **Status:** Accepted — owner decision 2026-10-03 (design hand-off „Navigation neu", Turn 2).
- **Date:** 2026-10-03
- **Deciders:** @greluc
- **Related:** spec [`ui-design-system.md`](../specs/ui-design-system.md) (`REQ-UI-026`, `REQ-UI-009`,
  `REQ-UI-013`) · [ADR-0177](0177-the-app-has-exactly-one-dialog-shape.md) (the one dialog shape) ·
  [ADR-0176](0176-layout-floors-are-defaults-that-controls-opt-out-of.md) (the touch floor)

## Context

The sidebar (`fragments/sidebar.html`) was one flat list of collapsible groups behind the hamburger.
For a member with many roles it had grown to about 65 links — several viewport heights — and it
mixed three kinds of destination: the work areas, the member's own pages, and the administration,
plus the legal pages that the fixed footer already carries. Finding a page meant scrolling and
reading, on a phone with the same drawer squeezed to 300 px.

The header was copied into 83 templates, so any change to it was an 83-file change.

## Decision

1. **The header is one fragment**, `fragments/header.html :: header`, included by every page that
   has the app shell. It carries the hamburger, the brand, the quick-access trigger and the
   notification bell; the bell moves from `position: fixed` into the header's flex row.
2. **The drawer is structured into three zones** — head, context (org-unit switcher, menu filter),
   a scrolling list of iconed groups — and a fixed foot with the administration entry, the personal
   panel and the user row (name, org unit and role, language, logout). Links read in normal case;
   group headings stay upper-case.
3. **The information architecture changes:** „Rechtliches" leaves the menu (the footer has it), the
   personal pages move into the user row's panel, and **administration becomes a mode** that swaps
   the groups for the four admin groups instead of a 25-link group at the end of the list.
4. **A quick access** (`Ctrl`/`⌘` + `K`) searches every page the member may open, plus a few
   actions and the recently visited pages. Its index is **built in the browser from the links the
   server rendered into the drawer**, so the server stays the only place that decides what a member
   may see, and no endpoint is added.
5. **Phones get a tab bar** of the four most used areas plus „Menü", which opens the same drawer as a
   sheet. The fixed footer gives way to the tab bar on phones; its links move to the end of the sheet.

## Consequences

- One drawer element serves both device classes; the phone sheet is the same markup restyled, so a
  link added to the menu reaches the drawer, the sheet and the quick access at once.
- The closed drawer is `inert`, so keyboard users no longer tab through 65 invisible links. Other
  scripts that make the page inert behind a modal must restore only what they set (`org-chart.js`
  does).
- Anything pinned to the bottom of the viewport on phones sits above the tab bar through
  `--krt-tabbar-height`; `--krt-footer-height` keeps its meaning (how much the footer covers, `0px`
  on phones).
- The quick access is a dialog of the one dialog shape (ADR-0177): its title is visually hidden and
  its close control reads „ESC" on desktop; on phones it is full screen with „Abbrechen".
- „Recently visited" lives in the browser (`localStorage` `krt.recent`) and holds only paths and
  labels the member was shown anyway; an entry that is no longer in the menu is not offered.

## Alternatives considered

- **A fixed, always-visible sidebar or an icon rail** (hand-off variants 1b): costs horizontal space
  on every page and still needs a drawer on tablets and phones. Rejected.
- **A top bar with mega menus** (1c): five top-level areas do not hold the administration, and the
  menus are hover-driven, which phones cannot use. Rejected.
- **A quick-access index served by the backend**: would duplicate the permission rules the templates
  already apply with `sec:authorize` / `th:if`, and could drift from what the menu shows. Rejected
  in favour of reading the rendered menu.
- **Keeping „Rechtliches" in the menu**: a second copy of the footer's links in an already long list.
  Rejected; on phones, where the footer is hidden, the links sit at the end of the sheet.
