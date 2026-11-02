# ADR-0324 — The dependency scan reads a cached NVD feed, refreshed on its own schedule

- **Status:** Accepted
- **Date:** 2026-11-02
- **Deciders:** @greluc
- **Related:** REQ-OPS-054 · REQ-OPS-024 · issue #2775 ·
  [`dependency-check.yml`](../../.github/workflows/dependency-check.yml) ·
  [`nvd-feed-refresh.yml`](../../.github/workflows/nvd-feed-refresh.yml)

## Context

The OWASP dependency scan downloaded and updated the NVD dataset in the same step that analysed the
build, and kept the dataset in an Actions cache keyed by ISO week. Only a non-PR run that missed
the cache and finished saved it. In practice the cache was mostly absent:

- the key changed every Monday, so the first run of a week started cold;
- GitHub deletes a cache after seven days without access, and evicts the least recently used ones
  once the repository passes 10 GB — on 2026-11-02 the repository held 11.8 GB of caches and no
  `nvd-feed-` entry, two days after a push run had restored one;
- the weekly scheduled run, the main saver, failed from 2026-10-19 on, first without a verdict
  and then on a real finding (Vert.x 4.5.34, fixed by the 4.5.35 bump of 2026-10-31).

Without a cache every run, pull requests included, rebuilt the dataset from nvd.nist.gov. That
takes about seven minutes when the site is fast and stalls for the full 30-minute step limit when
it is not, which failed pull requests that changed no dependency at all.

## Decision

We will separate keeping the NVD dataset current from scanning with it.

1. **A refresh workflow owns the dataset.** `nvd-feed-refresh.yml` runs daily, on demand, and on a
   `main` push that touches the root build script or the version catalog (a plugin bump may need a
   new database). It restores the newest feed, runs `dependencyCheckUpdate` with up to three
   time-limited attempts, stamps `refreshed-at`, and saves under a key unique to the run. A daily
   save keeps a recently created, recently used entry, so neither the seven-day expiry nor LRU
   eviction removes the feed between refreshes.
2. **One feed on `main`.** After the save a separate job — the only one holding `actions: write`,
   and only on `main` — deletes every other `nvd-feed-` cache, provided the new one is listed. The
   daily save therefore never accumulates.
3. **The scan never updates.** `dependency-check.yml` restores the newest feed and runs with
   `-PdependencyCheckAutoUpdate=false`. It needs no NVD API key and contacts nvd.nist.gov not at all.
4. **A missing or stale feed fails fast.** No restored cache, no stamp, or a stamp 168 hours old
   (the `nvd.validForHours` the dataset was built for) fails the scan within seconds with an error
   naming the refresh; from 48 hours it warns.
5. **Local runs keep updating.** The property defaults to `true`; only CI turns it off.

## Consequences

- A slow nvd.nist.gov delays the daily refresh, which retries, and no longer fails a pull request.
  The scan's step limit drops from 30 to 15 minutes.
- A pull request scans against a feed up to a day old. A CVE published that day shows on the next
  run after the refresh, which is within the weekly cadence the scan always had.
- A refresh that keeps failing turns every scan red after seven days instead of letting it run on an
  outdated feed; the failed refresh opens its own issue days before that.
- A plugin bump that changes the database schema scans red on its pull request, since `main`'s feed
  has the old schema; the refresh after the merge rebuilds it. The scan is not a required check.
- A pull request that changes the refresh workflow runs it, saving a feed in the pull request's own
  cache scope; the cache janitor deletes it when the pull request closes.

## Alternatives considered

- **Keep the combined step and raise its time limit.** A stall has no upper bound; a longer limit
  only makes each failure slower.
- **Fall back to a cold download in the scan when no cache is found.** That is the failure this
  decision removes, and it would hide a broken refresh.
- **Trigger the refresh from a scan that finds no cache.** The scan would need `actions: write` on
  pull requests and would still have to wait for the refresh to finish; a clear error and a manual
  re-run are simpler.
- **Exempt the NVD cache from eviction.** GitHub offers no pinning; a daily save is the only lever.
