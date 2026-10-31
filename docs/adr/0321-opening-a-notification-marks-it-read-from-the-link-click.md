# ADR-0321 — Opening a notification marks it read from the link click

- **Status:** Accepted
- **Date:** 2026-10-31
- **Deciders:** @greluc
- **Related:** REQ-NOTIF-005 · REQ-NOTIF-022 · REQ-NOTIF-032 · REQ-FE-001 · spec
  [`notifications.md`](../specs/notifications.md)

## Context

A notification is about something: a refinery order that is ready, a job order that moved to the
member's unit, a donation that arrived. Members expect a click on the notice to take them there and
the notice to count as dealt with. The inbox page already linked its rows to a page per type
(REQ-NOTIF-022), but the bell dropdown, where most clicks happen, had no links, following a link
left the notice unread, and many links ended on a list rather than on the item.

Marking read is a write. It has to survive the navigation it accompanies, work when the member opens
the subject in a new tab, and must not turn a link into something a prefetcher or a link preview can
trigger.

## Decision

1. **The row link points at the subject itself.** Every row, in the bell dropdown and on the inbox
   page, links straight to the target the frontend resolves for it (`NotificationTargets`). The link
   is an ordinary same-origin `href`, so copying it, opening it in a new tab and keyboard activation
   behave as for any link, and loading it changes nothing.
2. **The click marks read, through the existing write.** A click on an unread row's link sends the
   existing `POST /notifications/{id}/read` through `krtFetch`. A plain activation waits for that
   write, at most 1.5 seconds, and then navigates; a click that opens a new tab or window (modifier
   key or middle click) keeps the browser's own handling and marks read in the background, because
   the current page stays open and patches the row and the badge in place. A failed write leaves the
   row unread and shows no toast; the member is already on the way to the subject.
3. **The target is the item, not its list.** Where the subject has a detail page, the link opens it.
   Where it lives on a list page, the link carries the item so the page can bring it into view; a
   value the page cannot resolve shows the plain list. Types whose subject is gone or that the
   recipient cannot open stay unlinked, and every type is either mapped or listed as unlinked, which
   a test enforces.

## Consequences

- One click opens the subject and clears the badge entry; marking read by hand stays possible.
- "Open in new tab" from the browser's context menu bypasses the click, so it does not mark read.
  The notice stays unread until the member marks it or clicks it.
- No new endpoint, no new backend contract and no unsafe `GET`: marking read keeps its CSRF-guarded
  `POST`, and the target pages keep their own access checks.

## Alternatives considered

- **A `GET /notifications/{id}/open` that marks read and redirects.** Works without script and for
  every way of opening a link, but makes a `GET` mutate state, so a link prefetcher, a preview or a
  crawler following the link marks notices read the member never saw.
- **A `keepalive` write fired while the page unloads.** Needs a CSRF-capable beacon in the transport
  kernel for one caller, and gives no signal whether the write landed before the next page reads the
  badge.
