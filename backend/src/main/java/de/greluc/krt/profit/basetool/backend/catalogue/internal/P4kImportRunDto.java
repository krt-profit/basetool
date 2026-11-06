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
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One applied P4K run as the admin run list shows it (REQ-P4K-015): the Build, the time, the
 * uploading admin and the counts per kind.
 *
 * @param id the run
 * @param appliedAt when the APPLY ran
 * @param channel the Channel, always {@code LIVE}
 * @param buildBranch the Build branch
 * @param buildVersion the Build version
 * @param changeNumber the change number
 * @param patchVersion the patch version, or {@code null} when unknown
 * @param patchVersionOrigin where the patch version comes from, or {@code null}
 * @param createdBy the uploading admin's user id
 * @param kinds the completeness, counts and sweep outcome per kind, in the order of the kinds
 */
public record P4kImportRunDto(
    @NotNull UUID id,
    @NotNull Instant appliedAt,
    @NotNull String channel,
    @NotNull String buildBranch,
    @NotNull String buildVersion,
    long changeNumber,
    @Nullable String patchVersion,
    @Nullable P4kPatchVersionOrigin patchVersionOrigin,
    @NotNull UUID createdBy,
    @NotNull List<Kind> kinds) {

  /**
   * The counts of one kind in one run.
   *
   * @param kind the kind
   * @param completeness whether the catalogue listed the kind completely
   * @param matched records matched to a row
   * @param unmatched records without a row
   * @param added rows created
   * @param changed matched rows the run wrote
   * @param vanished rows the sweep stamped
   * @param reappeared stamped rows matched again
   * @param sweep what the sweep did, {@code null} on a run recorded before the sweep existed
   */
  public record Kind(
      @NotNull P4kKind kind,
      @NotNull P4kCompleteness completeness,
      int matched,
      int unmatched,
      int added,
      int changed,
      int vanished,
      int reappeared,
      @Nullable P4kSweepOutcome sweep) {}

  /**
   * Maps a run.
   *
   * @param run the run
   * @return its DTO
   */
  @NotNull
  public static P4kImportRunDto of(@NotNull P4kImportRun run) {
    List<Kind> kinds =
        run.getKinds().stream()
            .sorted(Comparator.comparing(P4kImportRunKind::getKind))
            .map(k ->
                new Kind(
                    k.getKind(),
                    k.getCompleteness(),
                    k.getMatched(),
                    k.getUnmatched(),
                    k.getAdded(),
                    k.getChanged(),
                    k.getVanished(),
                    k.getReappeared(),
                    k.getSweep()))
            .toList();
    return new P4kImportRunDto(
        run.getId(),
        run.getAppliedAt(),
        run.getChannel(),
        run.getBuildBranch(),
        run.getBuildVersion(),
        run.getChangeNumber(),
        run.getPatchVersion(),
        run.getPatchVersionOrigin(),
        run.getCreatedBy(),
        kinds);
  }
}
