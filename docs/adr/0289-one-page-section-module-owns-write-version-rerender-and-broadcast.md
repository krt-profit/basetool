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
- The section keys exist in the relay whitelist (`LiveSyncTopicClass`) and in the page's
  declaration; the amendment below makes the whitelist the one list the page reads.
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

## Amendment: the server renders the allowed section keys (#2762)

The head fragment renders one `<meta name="krt-live-sync-sections" data-topic-class="MISSION"
 content="crew,finance,...">` per `LiveSyncTopicClass`, from `allowedSections()` through the `liveSyncSectionKeys`
bean, so every page carries the whitelist and `LiveSyncTopicClass` stays the single
source of the key list.

- `krtPageSections.create` takes `topicClass`, reads the meta of that class and keeps only the wired
  sections the server allows. A wired key the server did not render is logged and dropped, a missing
  meta is logged and leaves the wiring as declared. The seam exposes the list as `allowedKeys`.
- `sections:` in a page module is the container and fragment wiring per key, not a list of the
  allowed keys; a key the page does not wire is simply not re-rendered.
- Versions are not duplicated into the head: a section container already renders its own
  `data-version` and a re-render or `propagateVersion` refreshes it, so the echo needs no second
  source.
- `LiveSyncSectionKeysTest` pins the rendered head against the enum. `LiveSyncSectionMapParityTest` keeps
  checking that a page wires every key of its room, and that every `topicClass` names an existing class
  and every live seam declares one.
