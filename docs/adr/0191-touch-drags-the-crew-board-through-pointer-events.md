# ADR-0191 — Touch drags the crew board through Pointer Events, because native drag is a mouse gesture

- **Status:** Accepted — implemented
- **Date:** 2026-09-18
- **Deciders:** @greluc (reported the defect and asked for the gesture itself, not only its
  labelling), Claude (implementation)
- **Related:** issue [#1936](https://github.com/krt-profit/basetool/issues/1936) ·
  specs [`mission-detail-tabs.md`](../specs/mission-detail-tabs.md) `REQ-MISSION-005` (amended) ·
  [`ui-design-system.md`](../specs/ui-design-system.md) `REQ-UI-009`, `REQ-UI-020` ·
  [ADR-0069](0069-inline-js-page-module-extraction.md) (no bundler, so a
  library would be vendored by hand) · [ADR-0172](0172-the-phone-class-gets-its-own-layout-contract.md)
  (the phone class this defect was reported on)

## Context

REQ-MISSION-005 says the crew board can be operated three ways — drag and drop, a click fallback,
and the keyboard. On a phone only two of the three ever worked, and nobody had noticed.

**HTML5 drag and drop is a mouse gesture.** `draggable="true"` and the `dragstart` / `dragover` /
`drop` family are driven by mouse input; no mobile browser synthesises them from touch. A finger on
a `draggable` row therefore produces no drag at all, and the long press that a user naturally tries
instead is claimed by the platform: Android raises its own context menu over the row, iOS its
callout, and the text-selection handles come up on top. The board's drag half was not degraded on a
phone — it was absent, and the platform's reaction to the attempt looked like the app misbehaving.

The board's own labels made this worse by pointing at the one gesture that could not work
("Teilnehmer hierher ziehen"). That half was fixed first, separately: the zone hints now name the
click path, which is what let a phone user get anything done at all. It is a workaround. A board
whose primary gesture is dead on the client the app now ships to as a mobile client (REQ-UI-020) is
a defect, and labelling it honestly does not make it one less.

Two constraints shape what can replace it. The frontend has **no bundler and no module system**
(ADR-0069): a drag library would be vendored by hand into `static/js/vendor/` and carried forever.
And on a phone **the rows are the board** — they cover most of the crew pane's surface — so any
gesture that claims the finger on contact takes the board's scrolling with it.

## Decision

**We will drive touch and pen drags on the crew board with Pointer Events, behind a press-and-hold,
and leave the mouse on the native implementation.**

1. **A second drag path, not a replacement.** `pointerdown` with `pointerType !== 'mouse'` starts
   it; the mouse returns immediately and keeps native drag, including the drag image that Pointer
   Events have no equivalent for. Both paths end in the same `moveParticipant`, so unit → unit,
   drop on the pool, and release-over-no-zone-unassigns are identical between them by construction
   rather than by a second implementation that has to be kept in step.
2. **A 320 ms stationary hold arms it.** A finger that travels more than 12 px before the hold
   elapses cancels the press, and the page scrolls as it always did. This is what keeps a tall
   board scrollable on the device where the rows leave no other surface to scroll from.
3. **The platform's long-press behaviour is suppressed, in four places, because no one of them is
   enough.** `user-select: none` removes the text selection the Android menu hangs off,
   `-webkit-touch-callout: none` removes iOS's callout, a `contextmenu` handler cancels the menu for
   as long as a touch press on a row is live, and a **non-passive** `touchmove` handler cancels
   scrolling for as long as a drag is live — Chrome makes document-level `touchmove` passive by
   default, and a passive listener's `preventDefault` is ignored.
4. **`touch-action: pan-y pinch-zoom` on the row, not `none`.** Vertical panning and pinch-zoom stay
   with the browser; the hold is what claims the gesture. `none` would have been simpler and would
   have cost the board its scrolling and a zoom-dependent reader their zoom.
5. **The hit-test is `elementFromPoint`, not the event target.** The pointer is captured by the row
   (implicitly on touch, explicitly where the capture call succeeds), so the event's own target
   stays the row wherever the finger actually is; the zone under the finger has to be looked up by
   coordinate.

## Consequences

- The crew board is operable by finger, which is the client this app is used from most.
- **A participant's name in a board row is no longer selectable text**, on every device. The row is
  a drag handle first; the same names are selectable wherever they are rendered as prose.
- The 320 ms hold and the 12 px threshold are **judgement, not measurement**. They match the
  common range for this gesture but were not tuned against a device, and a user who finds the hold
  long or short is reporting a real thing.
- **Only the crew board is converted.** Every other native-`draggable` surface — the mission Ziele
  and Ablauf reorder editors, the job-order queue reorder, the admin mission-data reorder — is
  still mouse-only under touch, for exactly the same reason. Tracked separately; this ADR's pattern
  is what they should adopt, and the shared parts should move out of `mission-detail.js` when the
  second surface needs them, not before.
- **The verification gap is real and is stated here rather than discovered later.** This was built
  in an environment with no device, no browser and no Docker. The E2E test drives synthetic
  `PointerEvent`s, which exercise the board's state machine end to end — hold, activation,
  hit-test, drop, backend write — but no headless engine can show that Android actually withholds
  its context menu or that the page actually stops scrolling under the finger. Those two need a
  human with a phone, and until one has looked, the suppression is reasoned rather than observed.

## Alternatives considered

- **A drag library (SortableJS, dragula, …)** — the mature answer, and the wrong shape here: with
  no bundler (ADR-0069) it is a hand-vendored bundle in `static/js/vendor/`, carried and updated by
  hand, to serve one board. The pointer state machine this replaces it with is about 120 lines.
- **Replacing native drag with the pointer path on every input** — one implementation instead of
  two is genuinely attractive, and it loses the browser's drag image, its autoscroll on some
  engines, and its accessibility affordances on the input where all of them already work. A larger
  blast radius for no gain on the device that had the defect.
- **`touch-action: none` on the rows** — the usual recipe for an immediate drag, and it makes a
  phone-height board unscrollable, because the rows are the board. Rejected with the immediate drag
  it belongs to.
- **Starting the drag on contact, without a hold** — same problem as above, reached from the other
  direction.
- **Leaving it at the labelling fix** — the click path does work, and shipping only that would mean
  the app's answer to "drag is broken on your phone" is a sentence explaining that it is. Accepted
  as the interim, never as the outcome.
- **A drag handle (a grip the finger must hit) instead of a hold** — it keeps scrolling everywhere
  and avoids the suppression entirely, at the cost of a 44 px target per row on the class with the
  least room (REQ-UI-009) and a gesture the user has to discover. Worth revisiting if the hold
  turns out to be awkward in practice.
