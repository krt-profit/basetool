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

package de.greluc.krt.profit.basetool.backend.model.dto;

import java.util.UUID;

/**
 * Summary of a P4K catalog import, shared by the preview ({@code dryRun}) and apply paths of {@code
 * P4kImportService}.
 *
 * @param dryRun {@code true} for a preview that wrote nothing
 * @param seedingEnabled whether this run seeds new rows; always {@code true} for a preview
 * @param manufacturers manufacturer reconciliation counts
 * @param items item reconciliation counts
 * @param ships ship reconciliation counts
 * @param commodities commodity reconciliation counts
 * @param blueprints blueprint reconciliation counts
 * @param ingredientsResolved number of existing {@code blueprint_ingredient} rows whose FK was
 *     resolved via the stored Wiki UUID
 * @param runId the sync-report run id of the audit events; {@code null} for a preview
 */
public record P4kImportResultDto(
    boolean dryRun,
    boolean seedingEnabled,
    Counts manufacturers,
    Counts items,
    Counts ships,
    Counts commodities,
    Counts blueprints,
    int ingredientsResolved,
    UUID runId) {

  /**
   * Per-type reconciliation tally: {@link #matched}, {@link #created} and {@link #unmatched}
   * partition the records with a usable join key; the other counts are sub-tallies over the matched
   * rows.
   *
   * @param matched records resolved to exactly one existing row
   * @param uuidBackfilled matched rows whose missing canonical UUID was filled from the P4K GUID
   * @param uuidConflicts matched rows whose existing canonical UUID differs from the P4K GUID
   * @param enriched matched rows on which at least one fill-if-null field was written
   * @param created records inserted, or in a preview insertable, as new {@code source = P4K} rows
   * @param unmatched records resolving to no row or to several, neither enriched nor seeded
   */
  public record Counts(
      int matched,
      int uuidBackfilled,
      int uuidConflicts,
      int enriched,
      int created,
      int unmatched) {}
}
