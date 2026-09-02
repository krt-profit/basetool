/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.greluc.krt.profit.basetool.backend.service.scwiki;

import java.util.Set;
import java.util.UUID;
import java.util.function.ToIntFunction;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * The gated tombstone sweep every SC Wiki sync runs at the end of a successful pass: rows the Wiki
 * feed no longer lists are marked {@code scwiki_deleted} (SC_WIKI_SYNC_PLAN.md §8.7). This helper
 * exists to make the <b>data-safety gates impossible to forget</b>: the sweep runs only when the
 * run actually saw at least one Wiki row AND the fetch that produced those rows enumerated the
 * whole feed.
 *
 * <p><b>The plan describes only the first of those two gates.</b> §8.7 predates the census gate,
 * which arrived with ADR-0147 on 2026-08-28 — so read that ADR alongside the section, and treat the
 * second gate below as the authority rather than the plan.
 *
 * <p><b>Why the gate is load-bearing:</b> {@code markScwikiDeletedExcept(seen, now)} tombstones
 * <em>every</em> catalogue row whose external UUID is not in {@code seen}. If a run reached the
 * sweep with an empty {@code seen} set — every fetched row had a null UUID, or every row threw — an
 * ungated call would pass an empty exclusion set and tombstone the <b>entire catalogue table</b>.
 * The empty-<em>response</em> short-circuit each sync does before its row loop guards the common
 * outage case, but not the "fetched rows, processed none" edge; this gate is the backstop for it.
 * Routing the sweep through here means a caller cannot invoke the tombstone without the gate.
 *
 * <p><b>The second gate: the fetch must have been a full census.</b> A non-empty {@code seen} set
 * proves the run saw rows, not that it saw <em>all</em> rows. A page walk that died on page 2 of 6,
 * or whose pagination metadata vanished behind an upstream field rename, still hands the sync
 * hundreds of genuine UUIDs — enough to sail past the emptiness gate — while every row on the pages
 * that were never requested is missing from {@code seen} for a reason that has nothing to do with
 * the Wiki dropping it. Sweeping then soft-deletes the un-fetched remainder. So the sweep also
 * requires {@code ScWikiClient.FetchResult.complete()}, and refuses (with a WARN naming the
 * incompleteness) when the walk could not vouch for the census. Orphan detection is merely delayed
 * to the next healthy run; a wrong tombstone is not.
 *
 * <p>Adopted by the two syncs whose end-of-run sweep is byte-identical bar the repository and the
 * entity label — {@link ScWikiVehicleSyncService} ({@code ship_type}) and {@link
 * ScWikiManufacturerSyncService} ({@code manufacturer}). {@code ScWikiCommoditySyncService} and
 * {@code ScWikiItemSyncService} deliberately keep their sweep inline: the former adds an empty-set
 * {@code WARN} diagnostic and calls the differently-named {@code markScwikiDeleted}, the latter
 * folds the sweep into a broader helper — forcing either through this shape would drop a log line
 * or over-parameterise the helper for no gain.
 *
 * <p>Lives in {@code service.scwiki} beside the pilots (not the generic {@code support} leaf) since
 * the {@code scwiki_deleted} / "in Wiki feed" wording is SC-Wiki domain knowledge; it holds no
 * state and takes the caller's {@link Logger} so the log line keeps the sync's own logger name.
 */
public final class ScWikiOrphanSweep {

  /** Non-instantiable static helper. */
  private ScWikiOrphanSweep() {}

  /**
   * Runs the tombstone sweep only when the run saw at least one Wiki row <em>and</em> the fetch
   * that produced those rows was a full census, then logs how many rows were marked (§8.7). A run
   * that saw no Wiki rows is a silent no-op — never a full-table tombstone; a run whose page walk
   * was incomplete logs a WARN explaining why it stood down (see the class Javadoc for why both
   * gates are load-bearing).
   *
   * <p>The WARN cannot fire on a healthy run: {@code fetchComplete} is only {@code false} when the
   * client itself already logged a failed page, absent pagination metadata on a full page, or a
   * {@code meta.total} mismatch.
   *
   * @param seen the external UUIDs seen in this run; the sweep's exclusion set and the first gate —
   *     an empty set skips the sweep entirely
   * @param fetchComplete {@code ScWikiClient.FetchResult.complete()} for the fetch that produced
   *     {@code seen}; {@code false} means pages are missing from the census and the sweep is
   *     skipped
   * @param markDeletedExcept the repository tombstone operation, invoked with {@code seen} and
   *     returning the number of rows marked (typically {@code s -> repo.markScwikiDeletedExcept(s,
   *     now)}); called at most once, and only when both gates pass
   * @param log the calling sync's logger, so the "Marked … row(s)" line carries the sync's own
   *     logger name and level
   * @param entityLabel the table/row noun for the log line (e.g. {@code "ship_type"}, {@code
   *     "manufacturer"})
   */
  public static void sweepDeletedOrphans(
      @NotNull Set<UUID> seen,
      boolean fetchComplete,
      @NotNull ToIntFunction<Set<UUID>> markDeletedExcept,
      @NotNull Logger log,
      @NotNull String entityLabel) {
    if (seen.isEmpty()) {
      return;
    }
    if (!fetchComplete) {
      log.warn(
          "Skipping the {} scwiki_deleted sweep: the Wiki page walk did not enumerate the whole"
              + " feed this run, so the {} uuid(s) it saw are not a complete census and every row"
              + " outside them would be tombstoned for never having been fetched.",
          entityLabel,
          seen.size());
      return;
    }
    int marked = markDeletedExcept.applyAsInt(seen);
    if (marked > 0) {
      log.info("Marked {} {} row(s) scwiki_deleted (no longer in Wiki feed)", marked, entityLabel);
    }
  }
}
