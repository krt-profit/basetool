# ADR-0053 — Standardize user-selection fields on the shared searchable combobox

- **Status:** Accepted
- **Date:** 2026-06-29
- **Deciders:** @greluc
- **Related:** spec REQ-FE-011 · `krt-searchable-select.js` · ADR-0012 (krtFetch foundation)

## Context

The member base has grown noticeably and is expected to keep growing. Most fields that pick a
user/member were plain `<select>` dropdowns listing every user, which become unusably long and offer
no way to find a person by typing. The tool already ships a progressive-enhancement combobox
(`krt-searchable-select.js`) used in a handful of places (bank holder registration, grant creation,
the Leitung add-member modal, the orders item search), but it was loaded per page, filtered on the
visible option label only, and was not applied to the bulk of user pickers.

Two facts shaped the options:

- The backend `/api/v1/users/search` already matches **username OR display name** case-insensitively
  and is squadron-scope-aware, and `UserReferenceDto`/`UserDto` expose `username`, `displayName` and
  `effectiveName` separately — so both names are available both server-side (preloaded options) and
  on demand (remote search).
- The candidate set differs per field (all users, staffel participants, registered tool users,
  evaluatable members, bank holders-by-handle), and some fields (mission participant-add, party-lead)
  must also accept a **free-text guest** name, which a "must pick from the list" combobox forbids.

## Decision

We will make **every field that selects a registered user/member a searchable combobox**, rendered by
the shared `krt-searchable-select.js`, filtering on **both** username and display name. This is a
binding requirement (REQ-FE-011).

- **Local-filter by default.** Pickers keep their server-rendered `<select>` of candidate users
  (preserving each field's existing scope and the no-JS fallback) and opt in with a
  `data-krt-combobox` marker. Each `<option>` carries the secondary search term (`username`) in
  `data-search`; the combobox folds it into the filter haystack so a label that shows only the
  display name still matches the login handle. We do **not** route every picker through a remote
  `/users/search` endpoint — that would force per-field scoped search APIs and lose the names in
  edit-mode seeding for little gain at the current scale.
- **One global enhancer.** The script is loaded globally from `fragments/head.html` and
  auto-initialises every `select[data-krt-combobox]` on `DOMContentLoaded` and on `krt:swapped`
  (covering live-update fragment swaps), with shared default labels in `window.krtComboboxI18n`. A
  `window.krtEnhanceComboboxes(root)` hook upgrades pickers a page builds dynamically (cloned modal /
  selector rows). Per-page wiring is removed.
- **Non-destructive enhancement.** The combobox carries the original control's `name`, `id` and
  generic `data-*` (incl. `data-role` / `data-trigger`) onto its hidden input, and exposes a
  `setValue` API, so existing `getElementById` reads, form submission, change-delegation and
  programmatic preselection keep working without rewriting every consumer.
- **Two carve-outs.** Guest-capable mission fields (participant-add, party-lead) keep the
  `/users/search`-backed free-text autocomplete (already both-name searchable). Bank **holder**
  pickers become searchable comboboxes that filter the **handle** (holders may be non-users with no
  separate username/display name).

## Consequences

- Every user picker now scales with the member base: type-to-filter by name or handle, no endless
  dropdowns. New user-selection surfaces get the behaviour for free by adding one marker attribute.
- The enhancer is a single, globally-loaded, idempotent module; the `krt:swapped` hook keeps it
  consistent with the live-update contract (REQ-FE-001/010).
- Cost: pages that preload all users (admin pickers, and now notification-rule selectors) ship the
  full user list as `<option>`s. Acceptable at the current scale; if the list grows into the
  thousands the same fields can switch to the component's existing `remoteSource` mode (the orders
  item search already uses it) behind `/users/search` without changing the contract.
  **Follow-up (2026-07-10, #1193 / ADR-0085 / ADR-0089):** that trigger was reached. Every picker that
  preloaded the full or admin-"all-squadrons" roster now runs in `remoteSource` mode — driven
  declaratively by the marker value (`data-krt-combobox="remote-users"` → `/users/search`,
  `remote-bank-users` → the new bank-audience `/users/search-bank`, ADR-0089) via the shared
  `window.krtComboboxRemoteSources` registry — with edit-mode seeding (one seeded `<option>` /
  `/users/{id}` name lookup) and the no-JS `<select>` fallback preserved. Local-filter stays only for
  the small **scoped** pickers (a single mission's participants), and the two carve-outs below are
  unchanged.
- Cost: dynamically-cloned pickers (refinery store split rows, notification-rule selector rows) must
  re-enhance via `krtEnhanceComboboxes` and, where a row is cloned from already-enhanced DOM, rebuild
  the control from a pristine `<template>` — a small amount of per-page glue.
- E2E tests that drove the converted `<select>`s with Playwright `selectOption()` had to switch to
  filling the combobox textbox and picking an option.

**Follow-up (2026-07-16, REQ-FE-016):** the standard was extended from user pickers to **catalog
pickers** — material, game-item and booking-flow location selects. To make that possible the
component gained an option-metadata mirror: the selected option's extra `data-*`
(`data-quantity-type`, `data-refined-id`/`-name`, …) is mirrored onto the hidden input by one
shared helper on every value-set path (commit, preselect seeding, typed exact match, `setValue`),
with stale-key removal and select-level passthrough keys reserved — because enhancement removes
the native `<option>`s, and consumers previously read `selectedOptions[0].dataset.*`. The binding
conversion checklist (grep for `querySelector('select')` / `.selectedOptions` / `.selectedIndex` /
`.options[` / `.value =` writes / `cloneNode` per site) and the live-row-clone prohibition live in
REQ-FE-016 (`docs/specs/frontend-ajax-mutations.md`).

## Alternatives considered

- **Remote `/users/search` combobox for every field** — rejected as the default: it needs per-field
  scoped search endpoints to preserve each picker's candidate set, and edit-mode seeding would show a
  raw id instead of the user's name (the rule selector stores only the sub). Kept available as the
  opt-in scaling path via the existing `remoteSource` config.
- **Change the option label to `DisplayName (username)`** so the existing label-only filter matches
  both — rejected: it changes the visible text across the whole app for a search-only requirement;
  the per-option `data-search` keeps labels unchanged.
- **A third-party combobox library (Choices.js, Tom Select, …)** — rejected: a new dependency and CSP
  surface for behaviour the existing in-house, design-system-styled, ARIA-correct component already
  provides.
- **Leave the bespoke mission guest autocompletes and migrate them too** — the strict combobox forbids
  free text, so converting them would drop guest entry; left as-is per the carve-out.

