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
 * The gated tombstone sweep that marks rows missing from the SC Wiki feed as {@code
 * scwiki_deleted}.
 *
 * <p>Runs only when the run saw at least one Wiki row and the fetch was a complete census, so an
 * empty or partial fetch can never tombstone the catalogue.
 */
public final class ScWikiOrphanSweep {

  /** Non-instantiable static helper. */
  private ScWikiOrphanSweep() {}

  /**
   * Runs the tombstone sweep only when {@code seen} is non-empty and the fetch was complete, then
   * logs how many rows were marked; an incomplete fetch logs a WARN instead.
   *
   * @param seen the external UUIDs seen in this run; empty skips the sweep
   * @param fetchComplete whether the fetch that produced {@code seen} was a full census; {@code
   *     false} skips the sweep
   * @param markDeletedExcept the repository tombstone operation, called at most once with {@code
   *     seen} and returning the number of rows marked
   * @param log the calling sync's logger
   * @param entityLabel the row noun for the log line (e.g. {@code "ship_type"})
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
