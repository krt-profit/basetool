# ADR-0273 — The notification push carries no typed signal

- **Status:** Accepted — decided ahead of the change; implemented in #2673. Supersedes
  [ADR-0146](0146-the-notification-push-carries-what-arrived.md).
- **Date:** 2026-10-17
- **Deciders:** @greluc (issue #2638)
- **Related:** [ADR-0016](0016-notification-transport-polling-sse.md) (the transport) ·
  [ADR-0094](0094-tool-wide-topic-room-live-sync-relay.md) (the Redis fan-out) ·
  [ADR-0245](0245-group-recipients-time-based-notices-and-muting-extend-the-notification-engine.md)
  (muting, which needs the per-type grouping for its own reason) ·
  [ADR-0268](0268-the-backend-is-internal-only-and-ingest-is-the-single-public-api-entry-point.md) ·
  specs `REQ-NOTIF-010`, `REQ-NOTIF-018`, `REQ-NOTIF-021` · `NotificationSignal`,
  `NotificationFanout`, `NotificationStreamService`, `RedisNotificationFanout`

## Context

ADR-0146 put a payload on the SSE `notification` event: the notification type, the originating
entity's type and id, and the render parameters. It did so for two app requirements that a bare ping
cannot satisfy — filing the entry under one of five shade channels, and opening the right screen on a
tap — and it was careful to note that the web client was unaffected *"by construction, not by luck:
its handler has no parameter to read."* That is still literally true:

```js
source.addEventListener('notification', () => {
    markSseHealthy();
    refreshUnreadCount();
    …
});
```

The frontend relays the backend's stream (`/api/v1/notifications/stream` → `/notifications/stream`)
and the browser ignores the data.

With the app discontinued, the payload has no reader. What is left is machinery kept warm for nobody:
`NotificationSignal` on the wire, the fan-out's `publish(recipients, signal)` seam, the optional
field on the Redis message, the serialisation in `NotificationStreamService`, and three
degrade-to-`new` fallbacks — an unserialisable signal, a peer on an older build, an unknown
notification type — each of which exists to protect a client that is gone from a payload it no longer
receives.

One thing in ADR-0146's reasoning is load-bearing for a different reason and must not be removed with
the payload. Its keyed shape — `createFromEvent` returning `Map<NotificationSignal, Set<UUID>>`
rather than a flat recipient set — was justified by the payload ("the same trigger raises different
kinds for different audiences"), but per-type grouping is also what muting needs:
`NotificationMuteService.withoutMuted` takes recipients keyed by `NotificationType` (ADR-0245), and
the inbox rows are per type regardless.

## Decision

**The `notification` event is an empty "something changed" event again.**

- **The wire carries no data.** The event name is unchanged — that is the part
  [api-conventions.md](../specs/api-conventions.md) freezes — and its payload reverts to the bare
  push every client had before ADR-0146.
- **The fan-out seam takes recipients, not a signal.** `NotificationFanout.publish` and
  `NotificationStreamService` lose the `NotificationSignal` parameter; the Redis message loses its
  optional signal field at the unchanged payload version, so neither direction of a rolling deploy
  depends on the other.
- **One push per recipient per event.** Where an event raised two kinds for two audiences, ADR-0146's
  two pushes said two different things; an empty push says the same thing twice, so the fan-out
  publishes to the union of the recipients once.
- **The per-type grouping upstream stays**, because muting and the inbox rows need it. What is
  removed is the signal as a payload and as the fan-out's parameter — not the knowledge of which type
  reached whom, which never travelled over the stream for the web client anyway.
- **A refresh-only event stops being a special case.** REQ-NOTIF-018's supersede path sent a signal
  with no type so that the badge would move with nothing to file; with no payload at all, that is
  simply what every push is.

If a future client ever needs to know *what* arrived, the event name can carry data again — ADR-0146
established that and the finding holds. It would be a new decision, with a reader attached.

## Consequences

**The web client is untouched.** Its handler takes no argument, so there is nothing to adapt in the
browser, in the frontend's relay, or in the SSE health handling.

**Three fallback branches disappear with the thing they fell back from.** A serialisation failure, an
older peer and an unknown type each produced the bare push; now there is only the bare push.

**Render parameters stop travelling over the stream.** ADR-0146 argued, correctly, that they reach
the same recipient over the same authenticated connection from their own inbox, so nothing new was
exposed. Removing them is not a fix for a leak; it removes a copy of member-visible text from a
transport that does not need it, which is one fewer place to reason about.

**Push volume goes down slightly and badge behaviour is unchanged.** A member in two audiences of one
event gets one push instead of two and refetches once.

**The event is now a pure liveness-plus-dirty signal**, which is what ADR-0016 specified in the first
place: an in-app polling baseline with SSE as the enhancement. The payload was the exception, not the
model.

## Alternatives considered

*Keep the signal for a future client.* A payload nobody reads is a payload nobody notices breaking,
and the project has the receipt: ADR-0146 shaped a seam, a Redis field and three fallbacks around a
client that existed for seven weeks.

*Keep the type and drop the render parameters.* Half the payload, the same absence of a reader, and
the type alone would still need the keyed fan-out and the unknown-type fallback.

*Remove the event entirely and let the client poll.* The polling baseline exists (ADR-0016), but the
push is what makes the badge move without a reload, and REQ-FE-010's live expectations are built on
it.

*Keep `publish(recipients, signal)` and always pass `refreshOnly()`.* It would leave the seam honest
about nothing: a parameter with one possible value, which the next reader has to prove is always that
value.
