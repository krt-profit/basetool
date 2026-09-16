# ADR-0177 — The app has exactly one dialog shape

- **Status:** Proposed
- **Date:** 2026-09-14
- **Deciders:** @greluc (pending)
- **Related:** specs [`REQ-UI-013`](../specs/ui-design-system.md) (amended here) ·
  [`ui-design-system.md`](../specs/ui-design-system.md) ·
  [ADR-0012](0012-server-rendered-thymeleaf-with-progressive-enhancement.md) ·
  [ADR-0093](0093-inline-styles-are-migrated-to-utility-classes.md) (the `krtm-*` utilities this
  removes 33 of) · issue #1891

## Context

The app rendered dialogs in **three** shapes, only one of which was the design system's:

|   Shape   |             Root / box              | Dialogs |             Open/close contract             |
|-----------|-------------------------------------|---------|---------------------------------------------|
| canonical | `.krt-modal-overlay` / `.krt-modal` | 42      | `krtm-modal-open` / `krtm-hidden`           |
| legacy A  | `.modal` / `.modal-content`         | 47      | inline `style.display`, default **visible** |
| legacy B  | `.modal-overlay` / `.modal-box`     | 7       | an `active` class                           |

They differed in more than class names. The canonical shape caps itself at `90vh`, scrolls its body
and keeps head and foot pinned; the legacy ones did none of that. Legacy A's root defaulted to
`display: flex` — *visible* — and relied on a `krtm-display-none-*` class to stay shut, the inverse
of the canonical default. Legacy B could only be opened by a class nothing else in the app used.

**The cost was that every dialog defect had to be fixed once per shape**, and nothing detected a
fourth. Two concrete instances from the same evening: the 44 px touch floor was made a
zero-specificity default and four `.seg button` instances still had to be fixed separately; and
`.krt-modal` was taught to scroll rather than become two columns, after which the identical fix had
to be written a second time for `.modal-content`, because `#storeModal` on `/refinery-orders/{id}`
came out 881 px tall in an 800 px viewport.

The multiplier reached past CSS. `krt-live-sync.js`'s "is any dialog open?" probe — the guard that
stops a peer's change from swapping the DOM out from under an open form and 409-ing the next submit
— queried `.krt-modal-overlay` only. **All 54 legacy dialogs were invisible to it**, and
org-chart had to carry its own `busyTest` to compensate. That is the same defect as the duplicated
CSS fix, wearing different clothes: one behaviour, written once per shape, silently absent from the
shapes nobody remembered.

## Decision

**There is exactly one dialog shape: `.krt-modal-overlay` > `.krt-modal` > `.krt-modal-head` /
`.krt-modal-body` / `.krt-modal-foot`.** All 54 legacy dialogs are ported onto it and both legacy
shapes are deleted — the markup, the CSS rule sets and their open/close contracts, not merely their
call sites.

- **One open/close contract.** Visibility is the `krtm-modal-open` / `krtm-hidden` pair, or an
  inline `display` that the shared `open-modal-display` / `close-modal-display` handlers clear on
  both edges. The `active` toggle is gone, and with it the now-unreferenced `open-modal` /
  `close-modal` handlers in `common-handlers.js`.
- **One hidden default.** The overlay is `display: none` in the global `styles.css`. A dialog is
  never visible because a stylesheet failed to load.
- **Three sanctioned widths and no fourth.** `.krt-modal` at 440 px, `.krt-modal--wide` at 600 px,
  and `.krt-modal--xwide` at 800 px added here for the dialogs that carry a *table* rather than a
  form (the Auftrag material/item pickers, the refinery store sheet). The per-dialog
  `krtm-max-width-*` utilities are deleted, not re-pointed.

**A shape is a default, not a list to be added to.** This is the same principle ADR-0176 states for
the touch floor, applied to the thing that kept forcing the list to exist.

## Consequences

- A dialog defect is fixed once. A design-system change to dialogs reaches every dialog.
- **Every dialog now caps at `90vh` and scrolls its body**, which is a user-visible improvement on
  the 54 ported ones and the reason this carries a CHANGELOG entry despite being a refactor.
- **Every dialog is now seen by the live-sync busy probe**, so a peer's change can no longer swap
  the DOM out from under an open form. Nothing had to be written for this; it follows from there
  being one shape.
- Confirm dialogs lose their centred text and gain a right-aligned footer row, because that is what
  the canonical shape does. Deliberate.
- A wrapper that sits between `.krt-modal` and a `<form>` — the refinery store dialog's live-sync
  swap container — must carry `.krt-modal-flow` so the frame's column layout passes through it.
  Without it the body cannot scroll, since the wrapper would absorb the flex context.
- 34 `krtm-*` utility rules are deleted with their only call sites. Two others were already dead
  before this change and are deliberately left alone as out of scope.
- **`MODAL_SHAPES` in `TouchClassLayoutE2eTest` shrinks from three entries to one.** That list is
  the change proving itself: the sweep knows one shape because there is one. Its four derived call
  sites follow automatically, and the `openClass` reveal machinery — which existed because
  `.modal-overlay` took its centring from `.active` alone — is now unused, kept only as the
  parameter a future class-opened shape would need.
- The touch block's close-button rule drops `.close-modal` and `.btn-close` from its selector list;
  both classes are gone. Its comment had called the markup behind one of them "the real defect
  there … but that is a separate change" — a `<span>` dismiss control in `admin/mission-data.html`
  that no keyboard could reach. **The port was that change**: every dismiss control in the app is
  now a `<button>`.

### The canonical shape had to be fixed first

The port would otherwise have been a regression, because the shape everything moved onto was itself
incomplete:

- **`.krt-modal-close` had no CSS rule at all** — every canonical dialog rendered the browser's
  default grey bordered button in the corner of a HUD frame. #1884's touch sweep found this
  independently and **already fixed it** on the branch this change stacks on, matching it to
  `.close-sidebar-btn`; that treatment is kept as the ratified one. What this change adds is the
  `flex-shrink: 0` a flex head needs so a long German title cannot squeeze the ✕ narrower than its
  glyph, and a `:focus-visible` ring — the control is the first Tab stop inside a dialog (the
  org-chart focus-trap test asserts exactly that) and a colour change alone does not say so on a
  dark HUD. `.oc-modal-close`, the page-local precedent, is deleted with the port.
- **The head styled `h2` while the shared `modal-wrapper` fragment emits `h3`.** A
  fragment-rendered title fell through to the global heading rule and came out orange at the
  browser's default `h3` size instead of white at `0.85rem`. The rule now covers both, so the
  fragment-rendered dialogs finally match the hand-written ones.
- **`bank.a11y.closeModal` was the close button's label on every page**, including the fifteen that
  have nothing to do with the Bank. It is renamed `general.a11y.closeModal`.

These are corrections to the 42 dialogs that were *already* canonical, not to the ported ones.

The `.krt-modal-close` entry is worth reading twice, because it is the same finding arriving from
two directions: #1884 reached it by **measuring** (a touch sweep that noticed a control below the
44 px floor) and this change reached it by **porting onto it** (the legacy `.close-modal` was
styled, so moving a dialog onto an unstyled one would have been a downgrade). Neither route needed
the other, and a defect a stylesheet never declared at all is precisely the kind that review does
not catch — it renders as "slightly off" rather than as an error.

### Two guards, and why both

`MODAL_SHAPES` and `SingleModalShapeTest` are not the same check twice.

`MODAL_SHAPES` drives the **browser sweep**: it reveals each dialog, measures its real geometry at
five device widths and photographs it. It answers "does this dialog lay out correctly?" and it can
only ask that of a shape somebody entered in the list — which is the drift its own Javadoc named
and could not close.

`SingleModalShapeTest` reads the templates and stylesheets as **text** and answers a different
question: "is there a second shape at all?" It needs no browser, so it runs in the frontend `test`
source set on every push rather than only on a PR carrying the `e2e` label — which is what closes
the standing gap, since a fourth shape's first commit is the cheap moment to catch it, not its
second duplicated defect. It also pins two invariants the geometry sweep cannot see: that every
overlay carries exactly one frame, and that a footer's submit button is inside the form it submits.
That last one caught two real defects in this change; a submit button outside its form does
nothing at all when clicked, and looks perfect in a screenshot.

## Alternatives considered

- **Keep three shapes and keep fixing three times.** The status quo. Every future dialog defect and
  every design-system change to dialogs is multiplied, and a fourth shape is undetected by
  construction.
- **Port only legacy A** — the shape that had just cost a duplicated fix. Halves the benefit and
  leaves the second open/close contract and the drift risk in place.
- **Teach the shared CSS to cover the legacy selectors too**, e.g. `.modal-content` in the same rule
  set as `.krt-modal`. Cheaper, and it cements three shapes and their three contracts rather than
  removing them. It would also have left the live-sync probe blind, since that keys on the root
  class and not on the box.
