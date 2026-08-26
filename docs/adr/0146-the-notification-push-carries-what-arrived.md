# ADR-0146 — The notification push carries what arrived, per notification type

> **Status:** Accepted · **Date:** 2026-08-26 · **Deciders:** @greluc
> **Related:** `REQ-NOTIF-021`, `REQ-NOTIF-010`, `REQ-NOTIF-018`, ADR-0094 (the Redis fan-out),
> `krt-profit/basetool-android` `REQ-APP-UI-007` (the client this exists for)

## Context

The SSE `notification` event carried the literal string `new`. The web app's handler is
`function () { … }` — it takes no argument, refetches the unread count, and is done. For a browser
that is the whole job.

The Android app needs two more things and can derive neither from a ping:

- **Which channel to file the shade entry under.** Its design specification names five
  (Einsätze & Check-In, Aufträge & Zuweisungen, Materialbörse, Bank & Auszahlungen, System &
  Ankündigungen) so a member can silence one kind and keep another. With no kind on the wire, every
  push is the same message and four of those channels would be switches that silence nothing.
- **Which screen a tap should open.** Its chapter 03 requires *„Push → Ziel-Screen direkt"*. With no
  entity on the wire, every notification opens the inbox.

The alternative — fetch the newest unread notification after each ping — costs a round trip per
event and races: two events in quick succession both read "the newest", and one of them is wrong
about what it is announcing.

## Decision

**The event's `data` carries a signal**: the notification type, the originating entity's type and
id, and the render parameters. The event **name** is unchanged, which is the part
[api-conventions.md](../specs/api-conventions.md) freezes.

**A signal is per notification type, not per event.** This is the non-obvious half. One event
resolves to a `Map<NotificationType, Set<UUID>>` — the same trigger raises different kinds for
different audiences — so a payload describing the *event* would be wrong for at least one recipient.
`NotificationCreationService.createFromEvent` therefore returns its result **keyed by signal**
rather than flattened to a recipient set, and `NotificationFanout.publish` takes the signal
alongside the recipients. The seam's shape now matches what is actually true about the data.

**A recipient whose inbox was only cleared keeps the historic `new`.** When an event supersedes
stale items (REQ-NOTIF-018) those recipients receive nothing new: their badge must move, but there
is no message to file or open. Modelling that as a signal with no type keeps one code path and
leaves the wire byte-identical for the case it already covered.

**Every failure degrades to `new`.** A signal that will not serialise, a Redis peer on an older
build, a notification type an instance does not know — each falls back to the bare push. A client
that cannot be told *what* arrived is still told *that* something did, which is exactly what it had
before this ADR.

**The Redis message gains an optional field at the unchanged payload version.** A peer running an
older build ignores it; this build receiving a message without one produces a refresh-only signal.
Neither direction of a rolling deploy depends on the other having landed first, so no ordering
constraint enters the deploy.

## Consequences

- Clients that ignore the payload are unaffected. The web app is such a client by construction, not
  by luck: its handler has no parameter to read.
- The render parameters now travel over the SSE stream. They already travel to the same recipient
  over the same authenticated connection from their own inbox, so no new audience can see them.
  What a client renders on a **lock screen** remains the client's rule; on Android that is a
  private channel plus a fixed public replacement, enforced there by construction and untouched by
  this.
- Two audiences of one event now cost two pushes instead of one. That is the number of distinct
  things being said, and the previous single push was only cheaper because it said less.
- Rejected: fetching the newest notification after the ping (a round trip per event, and a race
  between two events about which is "newest"); putting the whole rendered sentence on the wire (the
  client assembles it from type + params so it can localise and degrade honestly — see the app's
  `REQ-APP-NOTIF-005`); and a new event name (it would break the frozen contract to add data the
  existing name can carry).

