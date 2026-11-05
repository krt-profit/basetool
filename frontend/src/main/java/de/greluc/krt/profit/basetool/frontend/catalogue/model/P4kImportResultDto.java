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

package de.greluc.krt.profit.basetool.frontend.catalogue.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import de.greluc.krt.profit.basetool.frontend.kernel.model.BackendEnumAsString;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;

/**
 * Frontend mirror of the backend {@code P4kImportResultDto}, shared by a P4K import preview and
 * apply and rendered as the Build line and a per-kind count table; unknown fields are ignored.
 *
 * @param dryRun {@code true} for a preview (nothing written), {@code false} for an applied import
 * @param createItemsAndShips whether the run created unmatched playable items and ships
 * @param build the Build the catalogue was exported from, or {@code null} when the result has none
 * @param manufacturers per-kind tallies for manufacturers
 * @param items per-kind tallies for items
 * @param ships per-kind tallies for ships
 * @param commodities per-kind tallies for commodities
 * @param blueprints per-kind tallies for blueprints
 * @param ingredientsResolved number of blueprint ingredient links repaired
 * @param runId id of the recorded import run, or {@code null} for a preview
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@DtoMirror
public record P4kImportResultDto(
    boolean dryRun,
    boolean createItemsAndShips,
    Build build,
    Counts manufacturers,
    Counts items,
    Counts ships,
    Counts commodities,
    Counts blueprints,
    long ingredientsResolved,
    String runId) {

  /**
   * The Build of the imported catalogue and the patch version derived from it.
   *
   * @param channel the Channel, always {@code LIVE}
   * @param branch the build branch, e.g. {@code sc-alpha-4.10.2}
   * @param version the build version, for display only
   * @param changeNumber the Perforce change number as decimal text
   * @param patchVersion the patch version, or {@code null} when it is unknown
   * @param patchVersionOrigin {@code PUBLIC_PATCH}, {@code BRANCH_LOWER_BOUND}, or {@code null}
   *     when the version is unknown
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Build(
      String channel,
      String branch,
      String version,
      String changeNumber,
      String patchVersion,
      @BackendEnumAsString String patchVersionOrigin) {}

  /**
   * Per-kind tallies of one catalogue kind.
   *
   * @param completeness {@code COMPLETE} or {@code PARTIAL} as the catalogue states it, or {@code
   *     null}
   * @param matched records resolved to an existing row
   * @param enriched matched rows whose fields were updated from the catalogue
   * @param identityMismatches matched rows whose Wiki UUID differs from the P4K GUID
   * @param created rows created from the catalogue (preview: the would-be count with the box
   *     ticked)
   * @param unmatched records with no row that were not created
   * @param review review cases the run found
   * @param unknownValues closed-set values the import did not know and read as {@code null}
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Counts(
      @BackendEnumAsString String completeness,
      long matched,
      long enriched,
      long identityMismatches,
      long created,
      long unmatched,
      long review,
      long unknownValues) {}
}
