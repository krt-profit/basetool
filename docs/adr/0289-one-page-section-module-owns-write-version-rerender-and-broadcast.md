# ADR-0289 — One page-section module owns write, version, re-render and broadcast

- **Status:** Accepted
- **Date:** 2026-10-18
- **Deciders:** @greluc
- **Related:** spec REQ-FE-001…010 · REQ-FE-015 · ADR-0012 · ADR-0069 · ADR-0071 · ADR-0094 · issue #2734

## Context

The live-update choreography REQ-FE requires (write, propagate the new version, re-render the
section, broadcast to peers, receive a peer's change) had one kernel helper, `krtFetch.sectionWrite`,
used by six page modules. About fifty more wired `sendChanged`, `createReceiver`, a local
`*_SECTIONS` map and a `reswap*` helper by hand, and the section keys lived in the sender map, the
relay whitelist and the receiver map, kept aligned by `LiveSyncSectionMapParityTest`. A page's
behaviour was covered only by e2e.

## Decision

A page-section module, `krtPageSections` in `kernel/krt-page-sections.js`, replaces
`krtFetch.sectionWrite`. A page declares its sections once and the module owns write (with the 409
confirm and the per-section serialization of ADR-0071), version propagation, the in-place re-render,
the broadcast and the receiver. The sender map and the receiver map become one declaration; the
parity test reads the `sections:` property of the declaration instead of a named constant.

The write's `propagateVersion` option replaces a hand-written `dataset.version =` or `syncVersion`
write on a page that patches the DOM without a re-render; a section re-rendered after the write gets
its version from the fragment, so no page needs both.

Its seam is covered by a node test over a fake DOM running the real `krt-fetch.js` transport
(`:frontend:testPageSectionsJs`). The six pages that used `sectionWrite` migrate in this change.

## Consequences

- A migrated page has no local reswap/refresh helper, section map, receiver wiring or version write.
- The section keys still exist in the relay whitelist (`LiveSyncTopicClass`) and in the page's
  declaration. Rendering the allowed keys and versions into `data-*` attributes from the server, so
  no JS copy remains, is a possible next step and not part of this decision.
- Pages not yet migrated keep working unchanged and move with the next change that touches them.

## Amendment: remaining pages (#2757)

Migrating the remaining pages widens the declaration without changing the decision:

- `dict` and `keys` are optional, for a page that only receives or needs no localized section strings.
- `sections` and `pageUrl` are optional, for a page that only writes.
- A write may carry `onConflict(body, response)`, which runs instead of `onError` on a 409 and
  replaces the conflict confirm unless it returns `false`; `onError` never sees a 409. A
  `conflict` object passed to the write wins over the derived strings.
- `submitForm` applies the same decoration to `krtFetch.submitForm`.
- `receiver.retargetable` builds the receiver before the topic is known, and `receiver.refresh`
  wraps the re-render of a peer change for a page that restores state around the swap.
- `refresh` fetches sections that share a container and fragment once.

These widen the declaration and do not change who owns the choreography.
