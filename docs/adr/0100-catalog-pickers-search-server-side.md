# ADR-0100 — Catalog pickers search server-side; complete-list endpoints stay unbounded

- **Status:** Accepted
- **Date:** 2026-07-16
- **Deciders:** Repository owner (@greluc)
- **Related:** spec [`frontend-ajax-mutations.md`](../specs/frontend-ajax-mutations.md)
  `REQ-FE-016` · extends [ADR-0053](0053-standardize-user-selection-on-searchable-combobox.md)
  (searchable-combobox standard) and the remote-source registry of
  [ADR-0089](0089-bank-scoped-user-search.md) (#1193)

## Context

REQ-FE-016 converted the material and location pickers to searchable comboboxes. The first cut
kept them in **local-filter mode**: the page embedded the full catalog as server-rendered
`<option>`s and the combobox filtered it in the browser. To bound the page payload, the
unpaginated lookup endpoints (`GET /api/v1/materials/lookup`, `GET /api/v1/locations/lookup`)
were capped at 1000 rows.

The cap was rejected in review: a fixed bound on a complete-list surface **silently hides the
tail** — beyond 1000 entries, a material or location would become unpickable app-wide with no
error, no hint and no metric, and client-side filtering cannot recover what never reached the
page. The codebase has already paid for this defect class twice: the orderable-item picker
"silently hid every item past the alphabetical cap once the catalog outgrew it" (fixed via
`/orders/item-search`, #1193), and the user pickers moved to `/users/search` as "the scaling
switch for 5000 accounts" (ADR-0085/0089). Local-filter mode also multiplies the payload: every
server-rendered order/refinery material row embedded its own copy of the full option list.

## Decision

Catalog pickers (materials, locations) search **server-side**, exactly like the item and user
pickers — and **no complete-list endpoint carries a silent cap**.

- Backend picker searches: `GET /api/v1/materials/search` (visible-only, optional
  `jobOrderOnly`/`rawOnly` narrowing for the orders/refinery subsets, refined material
  fetch-joined for the picker metadata) and `GET /api/v1/locations/search` (non-hidden) — paged,
  name-sorted, `LikePatterns`-escaped LIKE, deliberately uncached (user-typed query strings would
  pollute the shared master-data caches).
- Frontend relays `GET /catalog/material-search` / `GET /catalog/location-search` (25 rows per
  query, fail-soft empty list, `permitAll` — the anonymous order form carries a material picker).
- Marker-value registry `krt-catalog-search.js` (`remote-materials`, `remote-materials-joborder`,
  `remote-materials-raw`, `remote-locations`); material options carry
  `{quantityType, refinedId, refinedName}` as the option `data` map for the REQ-FE-016 metadata
  mirror.
- Edit/redisplay states seed exactly one selected `<option>` server-side (gated `th:if`);
  programmatic fills use the extended `krtCombobox.setValue(value, label, data?)` — in remote
  mode a label-less `setValue` cannot resolve the visible text, so call sites pass the label or
  resolve the entry through the search relay first (SCMDB import, refinery suggestion chips).
- The unpaginated lookup endpoints (`/api/v1/materials/lookup`, `/api/v1/locations/lookup`)
  return the **complete** list again (cap reverted). They serve complete-list surfaces (the
  inventory filter checkbox lists, API consumers) where completeness is the contract; their size
  is governed by the catalog's nature (UEX/SC-Wiki/universe sync plus admin curation), not by a
  hidden bound.

## Consequences

- Every catalog entry stays reachable at any catalog size — by typing — and the page payload is
  bounded by the fixed response page (25 rows), independent of catalog growth. The N-copies
  problem (full option list per material row) is gone.
- Opening a picker now shows a short "loading" state before the first page renders (matching the
  item/user pickers); without JavaScript the converted selects degrade to placeholder + seeded
  option only, the degradation already accepted for the remote user pickers (ADR-0089).
- Programmatic fills must carry labels: a bare `.value =` or `setValue(id)` on a remote picker is
  a defect (blank textbox); the SCMDB import and the refinery suggestion chips resolve entries
  through the search relay asynchronously.
- The binding rule generalises beyond pickers: **a fixed bound on a surface whose contract is the
  complete list is a defect** — bounded responses are only acceptable where a narrowing search or
  real pagination keeps every entry reachable (REQ-FE-016 acceptance).

## Alternatives considered

- **Cap the lookup endpoints (first cut)** — rejected: silent truncation, the exact defect class
  of the pre-#1193 item picker; "works today" only because the catalogs are currently small.
- **Local-filter mode without any cap** — rejected: findability holds, but the page payload grows
  linearly with the catalog and multiplies per rendered row; the scmdb-import name matching would
  keep an unbounded full-catalog template in the page forever.
- **Threshold auto-switch (local below N, remote above)** — rejected: permanent dual-mode
  complexity in every template, JS call site and test for a latency win of one debounced fetch;
  the item/user pickers set the single-mode precedent.

