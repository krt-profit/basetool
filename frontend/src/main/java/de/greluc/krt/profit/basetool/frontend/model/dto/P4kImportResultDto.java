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

package de.greluc.krt.profit.basetool.frontend.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Frontend mirror of the backend {@code P4kImportResultDto}, shared by a P4K import preview and
 * apply and rendered as a per-type count table; unknown fields are ignored.
 *
 * @param dryRun {@code true} for a preview (nothing written), {@code false} for an applied import
 * @param seedingEnabled whether seeding of brand-new game rows was enabled for this run
 * @param manufacturers per-type tallies for manufacturers
 * @param items per-type tallies for items
 * @param ships per-type tallies for ships
 * @param commodities per-type tallies for commodities
 * @param blueprints per-type tallies for blueprints
 * @param ingredientsResolved number of blueprint ingredient links repaired
 * @param runId id of the persisted import run, or {@code null} for a preview
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record P4kImportResultDto(
    boolean dryRun,
    boolean seedingEnabled,
    Counts manufacturers,
    Counts items,
    Counts ships,
    Counts commodities,
    Counts blueprints,
    long ingredientsResolved,
    String runId) {

  /**
   * Per-type tallies for one catalog entity kind. Every game record is classified as exactly one of
   * {@code matched} (an existing row enriched), {@code created} (a brand-new row seeded from the
   * game) or {@code unmatched} (no match, not seeded). {@code uuidBackfilled}, {@code
   * uuidConflicts} and {@code enriched} are sub-tallies over the matched set.
   *
   * @param matched existing rows matched and enriched
   * @param uuidBackfilled matched rows that received a previously missing game UUID
   * @param uuidConflicts matched rows whose game UUID disagreed with the stored one
   * @param enriched matched rows whose fields were updated from the game data
   * @param created brand-new rows seeded from the game (preview: would-be seeds; apply: opt-in
   *     dependent)
   * @param unmatched game records with no match that were not seeded
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Counts(
      long matched,
      long uuidBackfilled,
      long uuidConflicts,
      long enriched,
      long created,
      long unmatched) {}
}
