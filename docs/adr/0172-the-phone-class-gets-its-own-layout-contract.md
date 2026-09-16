# ADR-0172 — The phone class gets its own layout contract, and a guard that measures it

- **Status:** Accepted — implemented (owner-requested 2026-09-13)
- **Date:** 2026-09-13
- **Deciders:** @greluc (four decisions, recorded below), Claude (measurement and implementation)
- **Related:** [ADR-0164](0164-an-installable-web-app-without-a-service-worker.md) (the PWA that made
  this load-bearing) · specs [`ui-design-system.md`](../specs/ui-design-system.md) `REQ-UI-009`
  (amended), `REQ-UI-019`, `REQ-UI-020` · `REQ-ORG-010` (the OrgUnit context in the title)

## Context

REQ-UI-009 has required a Smartphone (≤768px) and a Tablet (768–1024px) class since it was written.
**Nothing measured either of them.** Its own line read *"Enforced by: code/design review"*, the only
geometric guard in the repository (`MissionDatetimeSplitLayoutE2eTest`) sweeps 1280–1800px, and the
smoke suite loads pages at the default desktop viewport and asserts that the sidebar rendered. "Works
on a phone" was a review claim about a UI nobody opened on a phone.

ADR-0164 turned that from a latent gap into a live one: the web app is now installable to a home
screen and **is** the mobile client for iPhone and iPad, which have no native app and cannot get one.

The first measured sweep — five widths across every page route the controllers expose — found what
review had not:

|                       Finding                        |           Where           |                                                     Cause                                                     |
|------------------------------------------------------|---------------------------|---------------------------------------------------------------------------------------------------------------|
| Page scrolled sideways by 10–159px                   | 5 pages, phone only       | `.greeting.hud-box.flex-between`, the page header of **28 templates**, is `space-between` with no `flex-wrap` |
| Headings ran 88–151px past the edge                  | legal + audit pages       | German compounds („NUTZUNGSBEDINGUNGEN") are wider than a phone and have nowhere to break                     |
| Four-button rows sliced in half                      | 13 action rows in 6 files | `display: flex` with no `flex-wrap`                                                                           |
| Three chrome controls under the 44px floor           | every page                | The touch rule enumerates classes; these are bare `button`s (language switcher **17px**)                      |
| `.krt-modal-close` had **no rule in any stylesheet** | 20 modals                 | A class the markup uses and no CSS defines — a browser-default button in a dark HUD                           |
| The bell covered the wordmark                        | every page, phone         | `.notification-bell` is `position: fixed`, so the header cannot reserve its space                             |
| Drawer unreachable at the bottom                     | phone                     | `height: 100vh` is the viewport with the address bar *hidden*                                                 |
| A `<select>` 427px wide on a 375px screen            | audit pages               | Flex items carry `min-width: auto`; a select never shrinks below its longest option                           |

Three of these had been hit before and patched **per page**: three templates already carried a
generated `flex-wrap` utility on the exact element the other 28 lacked it on.

## Decision

**The Smartphone class gets its own layout contract, and the touch classes get a guard that
measures it.** Four owner decisions, taken 2026-09-13:

1. **The footer scrolls with the page on ≤768px** instead of being pinned. Pinned it cost the class
   twice — a two-row stacked footer plus an equal `padding-bottom` reservation, roughly a fifth of an
   812px screen for three legal links. The mechanism is `position: static`, and the load-bearing
   consequence is that **`--krt-footer-height` means "how much of the viewport bottom is covered"**:
   `sidebar.js` publishes `0` whenever the footer is not `fixed`, so all eight of its consumers stop
   reserving in one step rather than each needing its own media query.
2. **The header stays sticky and becomes compact.** It measured 76px, and the 60px of content came
   from the wordmark wrapping to two lines — it carries the OrgUnit context (REQ-ORG-010), which is
   longer than a phone is wide. One line with an ellipsis plus a 32px mark gives ~48px, and menu,
   mark and bell stay reachable without scrolling. Chosen over letting the header scroll away.
3. **`.btn-xs2` is not a dense row action** and takes the 44px floor on touch classes. It is a
   page-local 32px variant, and two of its instances are *form* actions, which REQ-UI-009 explicitly
   holds to the floor.
4. **`.master-row` is one**, and keeps 32px. The blueprint rows are a scan-and-tap list where density
   is the point.

## Alternatives rejected

- **Header scrolls away like the footer.** Frees all 76px while scrolling, and costs the menu, the
  mark and the bell until the member scrolls back up. Rejected: navigation is not a thing to hunt for
  on the class with the least screen.
- **Hide-on-scroll-down, show-on-scroll-up.** The best space-to-reachability trade, and it needs new
  JavaScript and a behaviour that exists nowhere else in the tool. Not for this change.
- **Dropping the wordmark on the phone.** Would remove the only place the active OrgUnit surfaces.
- **Patching the page-header overflow per template**, as had already happened three times. Fixed at
  the utility instead; 28 templates were waiting for the same patch.
- **Leaving the modal close button unstyled** because "nobody complained". It is a browser-default
  button in a dark HUD on 20 surfaces; it was styled to match `.close-sidebar-btn`, its approved
  sibling, rather than to a new invention.

## Consequences

- **The two touch classes are gate-enforced for the first time.** `TouchClassLayoutE2eTest` sweeps
  375×812, 810×1080, 1024×768, 1280×800 and 1600×900 over every page route, follows a real detail
  link from each list, shows and measures **every modal on the page** without needing its trigger
  (true of one shape out of three until 2026-09-13 — the legacy `.modal` and `.modal-overlay`
  families, 54 of the 96 roots, were not selected; see the correction note under REQ-UI-009),
  and writes a full-page screenshot per page and class plus one per modal on the touch classes. It
  collects every finding before failing, so an audit names all offenders rather than the first.
- **The guard is only as good as its own correctness, and it was wrong four times first** — each is
  recorded as a comment where it happened. It excused elements inside a scroll container without
  asking whether that container fits; it flagged 60 harmless overlaps with the fixed footer; it
  applied the touch floor to desktop and produced 490 non-defects; and it measured six pages in a
  viewport a previous full-page screenshot had left widened, because Playwright does not treat that
  resize as its own. The last one matters most: **under mobile emulation an oversized page widens the
  layout viewport instead of scrolling**, so a check that compares against `innerWidth` compares
  against the broken page and finds nothing. It now compares against the device width.
- **`dvh` enters the codebase** for the drawer and the dimming overlay, with `vh` kept as the
  fallback. Inside the installed PWA there is no browser chrome and the two are identical — this only
  ever bit in Safari's tab mode.
- **Nothing changes above 768px** except the three touch-target floors (which apply to ≤1024px) and
  the modal close button's styling, which was missing on every class.
