# ADR-0033 — scmdb.net export as a fourth import shape + structural tag matching

- **Status:** Accepted
- **Date:** 2026-06-21
- **Deciders:** Lucas Greuloch (@greluc)
- **Related:** spec [`docs/specs/blueprint-import-name-matching.md`](../specs/blueprint-import-name-matching.md) REQ-INV-014, REQ-INV-019 · issue [#327](https://github.com/greluc/basetool/issues/327) · [ADR-0008](0008-refinery-extract-json-contract.md) (additive contract evolution it mirrors)

## Context

The personal-blueprint import already accepted three upload shapes — a bare array, the SCMDB
log-watcher document, and the Basetool Blueprint Extractor document — all of which capture the
in-game `"Received Blueprint: <name>"` notification and so carry a `blueprints` array of entries
keyed by `productName`, matched against the master product list by normalized name (REQ-INV-006/007).

Users also keep their unlocked blueprints on [scmdb.net](https://scmdb.net), whose profile / tracking
export (`version` 3) likewise carries a top-level `blueprints` array — but with a different entry
shape: the product name is under `name`, a `completed` flag distinguishes unlocked from not-yet-owned
blueprints (the export is a checklist, not a log capture), there is no acquisition timestamp, and,
crucially, each entry carries the **DataForge blueprint key** under `tag` (e.g.
`BP_CRAFT_behr_rifle_ballistic_02_civilian`). The file also nests an unrelated `missions` tracker and
a `profile` block. The goal: let users feed this export into the existing import alongside the other
two, without regressing them.

The `tag` is the same identifier the basetool stores as `blueprint.scwiki_key` and already matches
against in the P4K import (`findFirstByScwikiKeyOrderByScwikiUuidAsc`). That makes a structural
(key-based) match possible for this source — something the watcher / extractor exports cannot offer
because they only emit a display name.

## Decision

We will accept the scmdb.net export as a **fourth** upload shape and add a structural **tag match**
ahead of the name chain.

- **Envelope reuse, entry-level adaptation.** The import keeps consuming only the top-level
  `blueprints` array (`@JsonIgnoreProperties(ignoreUnknown = true)`); `missions`, `profile`, `url`,
  and `favorite` are ignored exactly like every other foreign envelope field. At the entry level
  (`BlueprintExportEntryDto`) we read `name` as a Jackson `@JsonAlias` of `productName`, skip entries
  with `completed == false`, and capture `tag`. A `null` `completed` (the watcher / extractor exports)
  still counts as owned, so those exports are unchanged.
- **Structural tag match, additive and conservative.** `BlueprintProductService.scwikiKeyToProductKeyIndex()`
  builds a `scwiki_key → product_key` index over all active recipes; the import resolves an entry's
  `tag` against it **first**, short-circuiting to `MATCHED`. The lookup is **case-insensitive** (the
  Wiki keeps CamelCase, scmdb.net lower-cases) and **unambiguous-only** (a non-UNIQUE `scwiki_key`
  that maps to two diverging product keys is excluded). Because the tag is the structural identity,
  the parse also **de-duplicates by tag** (falling back to name only for tag-less entries), so two
  distinct blueprints scmdb.net displays under one name (a genuine piece + a CIG-mislabeled one, as
  in the real export) do not collapse into one and lose an owned product. On any miss the entry falls
  through to the unchanged exact → alias → fuzzy name chain. The tag step therefore never regresses
  an import — at worst it is a no-op and the name match decides as before; the index is built lazily
  so tag-less imports pay nothing for it.
- **No new contract surface.** The tag match reuses the existing `MATCHED` status, so there is no new
  status enum value and no `openapi.json` / DTO-contract change; there is no database migration (the
  index is computed from existing columns). A tag-resolved entry whose `name` does not normalize to
  the product key still learns a `blueprint_external_alias` on apply, bootstrapping future name-only
  imports.

## Consequences

- scmdb.net users import their unlocked blueprints through the same modal, with **no UI change** (the
  file picker already accepts `.json`; the button is format-agnostic).
- The scmdb.net import is **more reliable than name matching** for the cases REQ-INV-007 exists to
  patch: a blueprint whose `output_name` is CIG-mislabeled, or a cosmetic-variant spelling, still
  resolves via its structural `tag`. Those resolutions also seed name aliases, improving later
  name-only imports from any source.
- The tag match adds one extra `findActiveProductRows("")` scan per preview (≈1600 rows, grouped in
  Java) — negligible, and the same order of work the name index already does.
- If a future scmdb.net key namespace diverges from `scwiki_key`, the tag step simply stops matching
  and the name chain carries the import; nothing breaks loudly. Keeping the match unambiguous-only is
  the deliberate guard against a duplicate-key wrong pick.

## Alternatives considered

- **Name match only (ignore `tag`):** the minimal change — accept `name` + `completed` and let the
  existing name chain resolve everything. Rejected as leaving value on the table: the export hands us
  the canonical structural key, and name matching is exactly what stumbles on CIG mislabels and
  variant spellings the tag sidesteps. Tag-first with a name fallback is strictly better and no
  riskier (the fallback is the name-only behaviour).
- **A new `MATCHED_BY_TAG` status:** more transparent in the preview, but it would widen the API
  contract (status enum → `openapi.json`, frontend grouping, i18n) for a distinction that is an
  internal resolution detail; "auto-matched" is accurate either way. Rejected for surface.
- **Case-sensitive / ambiguity-tolerant tag lookup:** rejected — the two sources demonstrably differ
  in case, and `scwiki_key` is not UNIQUE, so a case-sensitive or first-wins lookup would miss real
  matches or risk an arbitrary wrong pick. Lower-cased, unambiguous-only is the safe contract.
- **A separate scmdb.net importer / endpoint:** rejected — the resolution, ownership, alias-learning,
  and preview/apply machinery are identical; forking them would duplicate the concurrency-sensitive
  apply path for no benefit.
