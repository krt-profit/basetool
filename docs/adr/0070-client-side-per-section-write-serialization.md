# ADR-0070 — Client-side per-scope serialization of optimistic-locked writes

- **Status:** Accepted
- **Date:** 2026-07-03
- **Deciders:** @greluc
- **Related:** spec REQ-FE-012 · REQ-FE-003 · REQ-ORG-018 · ADR-0012

## Context

Every in-place write echoes the entity's optimistic-lock version so a genuine two-user conflict
surfaces as an HTTP 409 (REQ-FE-003). The version is read from the DOM (`data-version` /
`data-*-version` holders) **when the handler fires** and baked into the payload. The inline editors
fire writes fire-and-forget: nothing coordinates two writes a single user triggers in quick succession
against the **same** lock scope.

That produced a first-party 409 the user could not avoid. In the mission Ziele editor, typing a goal
title and immediately clicking "+" (or the Klassifizierung dropdown, or a ▲▼ reorder) dispatched two
writes: the blur-triggered `change` (edit) and the click (add), **both** carrying the version read
before either response returned. The first bumped the section version server-side; the second arrived
stale and 409'd; "Aktuelle Werte laden" then reloaded and discarded the just-typed goal. The same
shape existed across the tool wherever a version-carrying inline write can be re-fired before its
response returns — the bank-grant flag matrix, the inventory job/mission association selects and note
edit, and the order status + variant-counting toggles.

This is **not** the conflict REQ-FE-003 / the OPTIMISTIC_LOCK reload-confirm exist for — that is two
different users. Here one user collides with their own back-to-back edits. The fine-grained
per-section lock counters (REQ-ORG-018) must stay: editing Ziele must never block a concurrent Ablauf /
core / schedule edit, so a blanket global write lock is unacceptable.

## Decision

We will **serialize a client's writes per lock scope and resolve their version lazily at send time**,
centralised in `krtFetch`:

- A write declares `opts.serialize` — a lock-scope key. Writes sharing a key run **strictly one at a
  time in submission order** (`runSerialized`, a per-key promise chain). The primitive is also exposed
  as `krtFetch.serialize(key, task)` so raw-`fetch` call sites (inventory, notes) share the same queue.
- `opts.url` and `opts.payload` may be a value **or** a `() =>` thunk that `write` / `submitForm`
  evaluate at **send** time — after the queue lets the write proceed — so a queued write reads its
  version from the DOM the moment it is actually sent, not when it was queued.
- `send` **awaits** a thenable `onSuccess`, so a serialized chain waits for the caller's fragment
  refresh (which rewrites the `data-*-version` holder the next write re-reads) before the next queued
  write starts.
- The `sectionWrite` seam defaults `serialize` to the section key, so every section write is
  auto-serialized by section with no per-call-site opt-in; distinct sections keep distinct keys and
  stay concurrent.

## Consequences

- A user's own sequential edits of one scope always succeed in order and never self-409; the just-typed
  entry is never lost. The genuine two-user conflict path (REQ-FE-003) is untouched — serialization
  only orders **one** client's own writes.
- REQ-ORG-018 fine-grained locking is preserved: the serialize key is per section / per row / per
  order, so disjoint scopes never block each other.
- Writes to one scope now queue behind each other instead of racing, adding at most one round-trip of
  latency per queued write — acceptable for human edit cadence and strictly better than a 409 + reload.
- A payload that carries **sibling** state the prior write may have changed must re-read that state at
  send time too, not just the version (the bank-grant toggle re-reads all three flags in its payload
  thunk, else a serialized second toggle would revert the first). This is a per-call-site obligation,
  called out in REQ-FE-012.
- Existing call sites are unaffected unless they opt in (`serialize` + thunk payload/url); the change is
  additive to `krtFetch`.

## Alternatives considered

- **Disable the trigger for the round-trip only.** The existing submit-button double-submit guard
  (REQ-FE-001) covers a form's own button, but the self-collision is between *different* controls on
  one scope (a title `change` racing an add `click`), which no single disabled button covers.
- **Optimistically bump the version client-side (read v, send v, assume v+1).** Fragile: it assumes the
  server always bumps by exactly 1 and never diverges, and it desyncs the instant a write partially
  succeeds. Reading the authoritative post-write version from the refreshed DOM is safe.
- **A single global write lock per page.** Would serialize disjoint sections against each other,
  violating the REQ-ORG-018 fine-grained-lock invariant (a Ziele edit blocking an Ablauf edit).
- **Debounce the inline handlers.** Only widens the race window; two interactions still ship the same
  version, just later, and it delays every save.

