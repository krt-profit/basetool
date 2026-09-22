# ADR-0195 — A complete residual census vouches for the kind passes it contains

- **Status:** Accepted
- **Date:** 2026-09-22
- **Deciders:** @greluc
- **Related:** ADR-0147 (a page-walk census is identity-based) ·
  [`docs/specs/data-persistence.md`](../specs/data-persistence.md) (`REQ-DATA-014`) ·
  [`docs/specs/observability.md`](../specs/observability.md) (`REQ-OBS-011`) ·
  `ScWikiItemSyncService.residualVouchesForPool` · `monitoring/prometheus/alerts/business.yml`

## Context

ADR-0147 gave the SC-Wiki page walk an identity-based census so the cross-kind `scwiki_deleted`
orphan sweep could run again. It did not run again.

Mode B walks seven per-kind endpoints and then the residual `/api/items` catch-all, accumulating one
cross-kind `seen` set, and the sweep tombstones every local row missing from it. The gate was the
conjunction: **every** pass must have returned a complete census. One of them never does.

Measured against the live API on **2026-09-22**, at the production page size of 200:

| endpoint | `meta.total` | merged | distinct | repeated | complete |
| --- | --- | --- | --- | --- | --- |
| `/api/weapon-attachments` | 106 | 106 | 106 | 0 | yes |
| `/api/weapons` | 413 | 413 | 413 | 0 | yes |
| `/api/vehicle-weapons` | 173 | 173 | 173 | 0 | yes |
| **`/api/vehicle-items`** | **3 281** | **3 281** | **3 278** | **3** | **no** |
| `/api/armor` (`FPS.Armor`) | 2 382 | 2 382 | 2 382 | 0 | yes |
| `/api/clothes` (`FPS.Clothing`) | 1 866 | 1 866 | 1 866 | 0 | yes |
| `/api/food` (`FPS.Consumable.Food`) | 221 | 221 | 221 | 0 | yes |
| `/api/items` (residual) | 12 331 | 12 331 | 12 331 | 0 | yes |

`/api/vehicle-items` orders on a non-unique key, so rows that tie straddle a page boundary: two rows
named *SHIELDS* come back on both page 14 and page 15, a *Tempest II Missile* on both 15 and 16, and
three other rows are consequently never served at all. Both census branches fire — a repetition and
a shortfall of three — the pass is INCOMPLETE, and the sweep stands down. **Every run.** The walk is
deterministic: two walks minutes apart returned byte-identical distinct sets.

There is no client-side remedy, and this was re-measured rather than inherited:

- `sort=uuid`, `sort=id` and `sort=-uuid` all return the *same* order as each other and a different
  one from the default, and pages 1 and 2 then **overlap by four rows** — the parameter is accepted
  and not applied to the paginated query. `sort=name,uuid` is ignored outright.
- `page[size]` is **capped at 200** upstream: 500, 1 000 and 5 000 all answer `per_page: 200`. A
  single-page walk, which would have no boundary to shuffle across, is not available.
- Walking at a second page size and unioning does not close it either: at 150 the same feed yields
  **32** duplicates, and the union of the 200-walk and the 150-walk still reaches only 3 280 of
  3 281.

So the incompleteness is real, permanent and upstream. The consequence was not: the item orphan
sweep has been standing down on every run since ADR-0147 restored it, so **nothing removed from the
Wiki has ever been tombstoned** — not vehicle items, but the entire `game_item` catalogue, because
one conjunct is false. `ScWikiOrphanSweepStandingDown` has been firing correctly the whole time.

The sweep's safety condition is narrower than the gate that implements it. The sweep is safe exactly
when `seen` holds **every item the Wiki currently serves** — and the kind endpoints are filtered
views over the very pool the residual pass enumerates unfiltered. Measured the same day: all seven
kind endpoints are strict subsets of `/api/items`, **zero** UUIDs outside it. The three rows the
`vehicle-items` walk misses are in the pool, and the residual pass walked the pool completely.

## Decision

**A complete residual `/api/items` census vouches for the cross-kind `seen` set, even when a kind
pass could not vouch for its own.** The sweep runs when `seen` is non-empty and either

1. every pass returned a complete census — the ADR-0147 gate, unchanged — or
2. the residual pass returned a complete census **and** it enumerated every UUID the run saw.

Clause 2's second half is a **runtime check, not an assumption**. `runKindPass` now reports the
UUIDs it was served alongside its success flag — kept apart from `seen`, which records which pass
*claimed* a row and therefore under-reports what a later pass saw — and
`residualVouchesForPool` refuses the relaxation if any pass was served a row the residual census
does not contain, falling back to the strict gate with a `WARN`. The subset relation is a property
of the upstream's data model that this repository cannot enforce; it can verify it on every run, and
it does.

The asymmetry that drove ADR-0147 is unchanged and drives this too: delaying orphan detection is
recoverable, a wrong tombstone is not. Clause 2 does not weaken that — it identifies the case where
no row *can* be hidden, and refuses to fire when it cannot show that.

## Consequences

- The cross-kind orphan sweep runs again, on the strength of a census that is complete rather than a
  conjunction that can never be satisfied. **On the first production run after this ships it will
  tombstone the whole backlog that accumulated while it stood down** — a soft delete
  (`scwiki_deleted`), gated as before on `scwiki_synced_at IS NOT NULL`, so UEX-only rows are never
  stamped. Expect one large `Marked N game_item row(s) scwiki_deleted` line, and read it before
  assuming a defect.
- `basetool_catalogue_orphan_sweep_skipped_total{sweep="item",reason="incomplete"}` stops
  incrementing for this cause, so **ScWikiOrphanSweepStandingDown** falls silent. It keeps its
  expression and threshold: a residual pass that cannot vouch for itself, or a kind endpoint that
  leaves the pool, still stands the sweep down and still fires it.
- **ScWikiCensusIncompleteStreak** keeps firing daily, because it keys off
  `basetool_external_fetch_errors_total{source="scwiki"}` and the `vehicle-items` walk still
  increments that once per run — correctly, the walk *is* incomplete. What changed is what it
  implies: its description said the sweep "refused to run on all of them", which is no longer true.
  The annotation is corrected to say so and to point at ScWikiOrphanSweepStandingDown for the actual
  stand-down. **This alert therefore has a standing, known cause with no fix available to us**;
  retuning or routing it is deliberately left as a separate decision rather than folded in here.
- A relaxed run logs at `INFO` naming how many passes failed and how large the vouching census was,
  so "the sweep ran although a pass failed" is never silent.
- The residual pass now costs one extra `HashSet` of the pool's size for the duration of a run
  (~12 300 UUIDs). Each kind pass builds one too and drops it immediately.

**Rejected.** *Passing `sort=uuid`* — measured again on 2026-09-22 and still worse, not better; the
parameter is not applied to the paginated query. *Raising `page[size]` so the feed fits one page* —
the upstream caps it at 200. *Walking twice at different page sizes and unioning* — measured; still
one row short of the announced total, at double the request cost. *Deduplicating or ignoring the
repetition* — ADR-0147 rejected this and it is no more true here: the duplicate is the symptom, the
rows that were never served are the problem. *Excluding `/api/vehicle-items` from the gate by
configuration* — the per-endpoint carve-out ADR-0147 already rejected, and it would go stale in
silence the day upstream fixes its paginator, whereas a residual census that stops being complete
takes the relaxation away by itself. *Scoping the sweep to the kinds that did vouch for themselves* —
a row's kind comes from the pass that claimed it, so a row moving between kinds mid-walk would be
tombstoned by the kind that no longer lists it.
