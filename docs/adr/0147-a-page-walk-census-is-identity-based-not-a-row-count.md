# ADR-0147 — A page-walk census counts identities, and a surplus is not a gap

> **Status:** Accepted · **Date:** 2026-08-28 · **Deciders:** @greluc
> **Related:** `REQ-DATA-014`, `REQ-OBS-001`, `REQ-OBS-011` (`ScWikiCensusIncompleteStreak`),
> `ScWikiClient.fetchAllPagesResult`, `ScWikiItemSyncService` Mode-B cross-kind orphan sweep

## Context

The SC-Wiki page walk answers one safety question for its callers: *did this walk enumerate the
whole feed?* Only a `complete` result may drive a tombstone sweep, because a sweep marks every
catalogue row **missing from the merged list** `scwiki_deleted` — rows that were never fetched are
indistinguishable from rows the Wiki dropped.

The criterion for that answer was `accumulated.size() == meta.total`. It fails in both directions.

**It reports a gap that does not exist.** `GET /api/items` — the endpoint the residual `GENERIC`
catch-all pass walks — serves **12 331** rows across the 62 pages it announces while its own
`meta.total` says **12 283**. Verified against the live API on 2026-08-28: the walk is
duplicate-free (12 331 distinct UUIDs), `meta.last_page`, the `next: null` link and the short final
page all agree with 62 pages, the numbers are stable across repeated walks, and each of the other
ten synced endpoints (`/armor`, `/clothes`, `/food`, `/weapons`, `/weapon-attachments`,
`/vehicle-items`, `/vehicle-weapons`, `/commodities`, `/vehicles`, `/manufacturers`,
`/blueprints`) matches its own total exactly. The upstream's count query under-reports what its own
paginator serves, on that one endpoint.

In production that made the item backfill's residual pass `INCOMPLETE` on **every** run, which
makes `allPassesSucceeded` false, which skips the cross-kind orphan sweep on every run — so nothing
removed from the Wiki was ever tombstoned, while `scwiki_sync` kept recording success with a
healthy item tally and `SyncZeroItems` / `ExternalSyncStale` / `ScWikiStepFailing` all stayed quiet.
It also burned one `basetool_external_fetch_errors_total{source="scwiki"}` per run, which is
precisely the persistence shape `ScWikiCensusIncompleteStreak` watches for. The alert was right; the
check it was watching was wrong.

**It misses a gap that does exist.** Comparing *sizes* cannot see a walk over a feed that is being
written to. An upstream insert or delete shifts every later row across the page boundaries, so the
walk is served some rows twice and never sees others — and one duplicate cancels one omission in
the total. A feed that repeats row 3 in place of row 4 hits `merged == meta.total` exactly and was
waved through as a full census, with row 4 a tombstone candidate for never having been fetched.

## Decision

**The census measure is the number of distinct row identities, not the number of merged rows.**

1. `ScWikiClient.fetchAllPagesResult` bounds its type parameter on a new `ScWikiRow` interface
   (`UUID uuid()`), implemented by the five paginated row DTOs. A paginated endpoint whose rows
   carry no identity cannot be census-checked at all, and the compiler now says so rather than the
   check silently degrading. Rows the upstream serves *without* a UUID each count as their own row,
   so an id-less feed does not read as one row repeated N times.
2. **A repeated row is a census failure in its own right** — `WARN`, one fetch-error, `complete =
   false`. It is the only observable trace of a pagination window that moved under the walk.
3. **A shortfall against `meta.total` stays a census failure**, now measured against the distinct
   count. This is the direction that gets live rows tombstoned.
4. **A surplus is not a census failure.** More distinct rows than the upstream's own total claims
   cannot hide a row from a sweep: those rows were *seen*, and every shape that can hide one
   surfaces above as a repetition or a shortfall. It is reported at `INFO` and the census stands.
5. **A feed that announces more pages by the end of the walk than page 1 did is a census failure.**
   The loop bound is fixed when page 1 answers, so the tail was never requested — the same "we
   never asked" case as a dropped page. This is what lets (4) stand without letting a growing feed
   slip through with it.
6. **Row-count baselines come from page 1; the page count is re-read from every page.** On a
   shrinking feed a fresher, lower total would hide exactly the rows a mid-walk deletion pushed out
   of the window before the walk reached them.

The per-call `FetchErrorLatch` (ADR-free, 2026-08 logging audit) is unchanged: a walk exhibiting a
repetition *and* the shortfall it causes still counts as one failed fetch, with a `WARN` per
symptom.

## Consequences

- The cross-kind orphan sweep can run again, so Wiki-side deletions are tombstoned again — the
  behaviour REQ-DATA-014 requires and the reason the flag exists. The sweep itself is unchanged and
  still soft-deletes only Wiki-written rows (`scwiki_synced_at IS NOT NULL`).
- `ScWikiCensusIncompleteStreak` returns to watching upstream failures instead of firing daily on a
  defect in the check. Its metric, threshold and runbook row are untouched.
- The merged list is deliberately **not** deduplicated before it is returned. A repetition marks the
  walk incomplete and is logged with both counts; silently collapsing it would hide the anomaly and
  change what every caller ingests to fix a case that must not be ingested as normal anyway.
- One more thing to keep true: a new paginated Wiki DTO must implement `ScWikiRow`. That is the
  point — it is a compile error, not a silently weaker guard.
- **The same contract now covers the UEX client** (REQ-DATA-014): `UexClient.FetchResult` gained a
  `complete()` flag for the identical reason one endpoint down. Its item sync makes one call per
  category, a failed call degrades to an empty list, and two categories are legitimately empty — so
  the row list alone could not tell "UEX dropped these" from "we never asked", and one 5xx on one of
  ~50 calls soft-deleted that whole category. UEX needs no *census* (each call is a single
  unpaginated answer, not a page walk), only the same refusal to sweep on a fetch it cannot vouch
  for.

**Rejected.** *Relaxing the mismatch to "shortfall only" and stopping there* — the cheap fix, and
the unsafe one: it removes the accident that currently catches a re-paginated walk (surplus →
incomplete) without replacing it, leaving the size-cancellation hole wide open. *A per-endpoint
carve-out for `/api/items`* — a config flag that goes stale in silence the day upstream fixes its
count query, and that does nothing for the other direction on any endpoint. *Deduplicating the
returned rows* — see above. *Re-fetching page 1 at the end of the walk to confirm the totals* — one
more request per walk, and it answers a question about the *feed* when the open question is about
the *rows we hold*.
