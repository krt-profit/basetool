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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * The outcome of a P4K preview or APPLY, stored on its job. A preview counts as if "create new
 * items and ships" were ticked, so its {@code created} is the would-be count (REQ-P4K-009).
 *
 * @param dryRun {@code true} for a preview, which writes nothing
 * @param createItemsAndShips whether unmatched playable items and ships are (or would be) created
 * @param build the catalogue's Build
 * @param manufacturers the manufacturer counts
 * @param items the item counts
 * @param ships the ship counts
 * @param commodities the commodity counts
 * @param blueprints the blueprint counts
 * @param resourceGroups the resource-group counts
 * @param ingredientsResolved blueprint ingredient lines whose material or item the run resolved
 * @param runId the {@code p4k_import_run} of an APPLY, {@code null} for a preview
 * @param sweeps the sweep result per kind (REQ-P4K-011, REQ-P4K-012), in the order of the kinds;
 *     {@code null} in a result stored before the sweep existed
 */
public record P4kImportResultDto(
    boolean dryRun,
    boolean createItemsAndShips,
    @Nullable Build build,
    Counts manufacturers,
    Counts items,
    Counts ships,
    Counts commodities,
    Counts blueprints,
    Counts resourceGroups,
    int ingredientsResolved,
    @Nullable UUID runId,
    @Nullable List<Sweep> sweeps) {

  /**
   * What the sweep did, or in a preview would do, with one kind (REQ-P4K-011, REQ-P4K-012).
   *
   * @param kind the kind
   * @param outcome whether it swept or why it skipped
   * @param known the kind's P4K-known rows, the base of the 5 % mass guard
   * @param toStamp the rows the sweep stamps, or would have stamped
   * @param vanished the rows the sweep stamped; in a preview the rows it would stamp
   * @param reappeared stamped rows the run matched again
   */
  public record Sweep(
      P4kKind kind,
      @Nullable P4kSweepOutcome outcome,
      int known,
      int toStamp,
      int vanished,
      int reappeared) {}

  /**
   * The Build of the catalogue and the patch version derived from it (REQ-P4K-005).
   *
   * @param channel the Channel, always {@code LIVE}
   * @param branch the build branch
   * @param version the build version, for display only
   * @param changeNumber the change number as the catalogue writes it
   * @param patchVersion the patch version, or {@code null} when it is unknown
   * @param patchVersionOrigin {@code PUBLIC_PATCH} or {@code BRANCH_LOWER_BOUND}, {@code null}
   *     exactly when the version is unknown
   */
  public record Build(
      String channel,
      String branch,
      String version,
      String changeNumber,
      @Nullable String patchVersion,
      @Nullable P4kPatchVersionOrigin patchVersionOrigin) {}

  /**
   * The tally of one kind. A record held for review because of a GUID conflict or an earlier
   * seeding is neither matched nor unmatched.
   *
   * @param completeness whether the catalogue listed the kind completely
   * @param matched records matched to a row and stamped
   * @param enriched matched rows on which at least one field was written
   * @param identityMismatches matched rows whose SC Wiki UUID differs from the P4K GUID
   * @param created rows created, or in a preview creatable
   * @param unmatched records neither matched nor created, each an {@code UNMATCHED} report row
   * @param review review cases the run found
   * @param unknownValues closed-set values the import does not know, read as {@code null}
   */
  public record Counts(
      @Nullable P4kCompleteness completeness,
      int matched,
      int enriched,
      int identityMismatches,
      int created,
      int unmatched,
      int review,
      int unknownValues) {}
}
