# ADR-0405 — A fragment swap waits for a press inside its container to end

- **Status:** Accepted
- **Date:** 2026-11-23
- **Deciders:** @greluc
- **Related:** spec REQ-FE-038 · REQ-FE-010 · REQ-FE-013 · REQ-FE-015 · ADR-0012 · ADR-0094 ·
  ADR-0289 · `kernel/krt-fetch.js` (`swap`) · `kernel/krt-press.js` (`pressSettled`)

## Context

`krtFetch.swap` replaces a container's whole markup (`setTrustedHtml`). A browser dispatches a
`click` only when the pointer is pressed and released on the same element. When a swap lands
between `pointerdown` and `pointerup`, the pressed control is removed from the document, no
`click` follows, and the press does nothing. The user sees a button that ignored them.

Several swap sources run without the user's involvement, so they can land mid-press at any time:
the page's own refresh after a write, a peer's change through the live-sync receiver (REQ-FE-010,
REQ-FE-015), the reconnect resync, and the writer's own echo. On the job-order page a money write
refreshes `money`, `header`, `actions` and `participations` itself, and the backend's
`publishAfterCommit` for the same write then reaches every socket in `order:{id}`, the writer's
included: the relay excludes the sender only for frames a browser sent, and the backend does not
know which socket wrote. The receiver re-renders the sections again 400–800 ms later, and a press
on "Geld bewegen" straddling that swap is lost. The E2E suite met it as a flaky
`JobOrderBankMoneyE2eTest`; Playwright reports such a click as done because its hit-target check
covers only the first pointer event.

## Decision

**`krtFetch.swap` holds its DOM write while a press of the primary pointer's main button that began
inside the container is in progress, and writes as soon as the press has ended.**

- The kernel module `krt-press.js` tracks that press with capture listeners on `window` —
  `pointerdown` starts it, `pointerup` and `pointercancel` end it, and so does `blur` of the window
  — and offers `pressSettled(element)`, which `swap` awaits before it writes.
- An ended press settles in the macrotask after its `click`, so the pressed control's click
  handlers run before the markup is replaced. A press that produces no `click` (a drag, a
  selection) settles a short grace period after its `pointerup`, because a tap's `click` can
  follow its `pointerup` as a separate input event.
- The held swap keeps every other part of its contract: the REQ-FE-013 sequence guard is checked
  again after the hold, so a newer swap still wins; `krt:swapped`, the `history` update and the
  scroll restore follow the write; the returned promise resolves `true` once the write happened.
- A press outside the container holds nothing.

## Consequences

- A press is never swallowed by a swap, whichever source started it and whatever the fragment
  contains, on every page that renders through `krtFetch.swap` — `krtPageSections` included.
- A caller awaiting a swap waits for a press inside the container to end; for a click that is a few
  milliseconds. A held press holds that container's update for as long as the button is down.
- The pressed control's handler runs against the markup the user saw. A handler that sends that
  markup's version meets the REQ-FE-003 conflict confirm instead of doing nothing.
- A keyboard activation is not tracked: Enter activates on `keydown`, and the receiver already
  defers a peer's change while focus is inside the section (REQ-FE-010).
- Writers that bypass `swap` (`setTrustedHtml` or `replaceWithTrustedHtml` on a section) are not
  covered.

## Alternatives considered

- **Skip a fragment that is byte-identical to the last one applied.** Rejected: it ends only the
  echo of an unchanged section, and only where the markup is stable. Every `th:action` form renders a
  fresh masked `_csrf` value per response, so a section with a POST form never compares equal; a
  peer's real change mid-press still swallows the press; and whether `krt:swapped` fires on a skip
  would change what the order page's listeners re-read.
- **Suppress the writer's own echo at the relay or the receiver.** Rejected: the backend's frame
  carries no origin, so an id would have to travel from the browser write through the frontend,
  the backend and Redis to the relay; the frame names sections the page does not refresh itself
  (`dialogs`, `kpi`), which would then stay stale; and a time window in the receiver would drop a
  genuine peer change that lands in it — the staleness REQ-FE-010 forbids.
- **Leave it and let the tests repeat a swallowed press.** Rejected: users keep losing presses, and
  each E2E flow would need the retry.
