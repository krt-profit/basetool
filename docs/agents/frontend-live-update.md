# Frontend live update

Read this before adding, changing or removing any frontend mutation, read or live-synced section.

**Live update is a binding requirement: every part of the frontend must support live update to
the current standard.** Every create / update / delete / toggle / reorder / filter / paginate
interaction updates the DOM **in place** through the shared `krtFetch` / `krtCsrf` / fragment-swap
foundation — **no full-page reload on success** (the only two sanctioned reloads are the
optimistic-lock conflict confirm and the bfcache history-restore of `REQ-FE-008`) — derived UI
outside the swapped fragment is refreshed too, and on any surface where several users can see the
same state a peer's change propagates to the others without a manual reload. The current standard,
its full `krtFetch`/fragment-swap contract and the live multi-user sync live in
[`docs/specs/frontend-ajax-mutations.md`](../specs/frontend-ajax-mutations.md)
(`REQ-FE-001…010`, ADR-0012/0013/0031). A new or changed frontend surface that reloads the page on
success, leaves a sibling/peer view stale, or hand-rolls a `fetch`/CSRF write outside `krtFetch` is
incomplete — extend the standard to cover it, don't fall back to a reload.

**Reads go through `krtFetch` too (REQ-FE-031).** `krtFetch.getJson(url)` for JSON,
`krtFetch.get(url, {accept, headers, signal, key})` for a fragment, a blob or a status the caller
reads itself. Both send the background marker, hand a lost session or the consent gate to the
navigation, and refuse a redirected answer (`get` resolves null, `getJson` rejects). A raw `fetch`
or `XMLHttpRequest` fails `:frontend:lintJs` outside `krt-fetch.js` and `krt-client-error.js`, and
`BackgroundReadGateContractTest` fails one in an inline template script.

**Wire a section-structured page through `krtPageSections.create`** (`kernel/krt-page-sections.js`, ADR-0289): declare the `topicClass`, the sections, the topic and the receiver once and use its `write` / `refresh` / `notify` / `receiver`; do not hand-roll a `reswap*` / `refresh*` helper, a `*_SECTIONS` map, a `createReceiver` call, a `dataset.version =` write or a 409 branch in a page module. A cross-topic poke goes through `krtPageSections.publish`.

**Live update and multi-user sync move with every feature — added, changed *or* removed.** Whenever
you add, change or remove a frontend surface that participates in live update or live multi-user
sync (a new editable section, a renamed/retired one, a new mutation on an existing section), you
**must** update its live-update and peer-sync wiring in the **same change** — never defer it to a
follow-up. For the multi-user sync in particular, a section key must stay consistent across **all**
its mirror points at once: the acting client's broadcast (the page's section/seam map), the server
relay's accept-list (`LiveSyncTopicClass.allowedSections`), and the receiving client's apply map. A key present
in one but missing from another **silently** leaves other viewers stale with no error — the
REQ-FE-010 defect that shipped when `objectives`/`frequencies` were added to the write seam but not
the receiver/relay. The server renders the relay whitelist into the head (`meta[name=krt-live-sync-sections]`) and `krtPageSections.create({topicClass})` drops a wired key outside it, so the page never keeps its own list of allowed keys. Prefer deriving these maps from a single source of truth so they cannot diverge;
where they can't share one, changing one **requires** changing the others in the same PR, and the
change is incomplete otherwise. `LiveSyncSectionMapParityTest` fails the build when the relay's
whitelist and a page's JS seam map disagree.
