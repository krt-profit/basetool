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

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Puts two catalogue items that a Wiki/UEX duplicate merge would join but that carry different P4K
 * GUIDs on the P4K review list (REQ-P4K-007): one {@code GUID_CONFLICT} case per GUID, about the
 * row holding the GUID and naming the other row as the one it collides with. Neither row is
 * changed; the administrator decides.
 */
@Component
@RequiredArgsConstructor
public class P4kMergeConflictRecorder {

  private final P4kReviewCaseRepository reviewCases;
  private final P4kImportRunRepository runs;

  /**
   * Opens, or keeps open, the review cases of a refused merge. A case needs a P4K run; the rows'
   * last matching run is used, else the latest run, and without any run no case is written.
   *
   * @param retired the row the merge would have archived
   * @param retiredGuid the retired row's P4K GUID
   * @param survivor the row the merge would have kept
   * @param survivorGuid the survivor's P4K GUID, different from {@code retiredGuid}
   * @return whether the cases are recorded
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean record(
      @NotNull GameItem retired,
      @NotNull UUID retiredGuid,
      @NotNull GameItem survivor,
      @NotNull UUID survivorGuid) {
    Optional<UUID> runId = runOf(retired, survivor);
    if (runId.isEmpty()) {
      return false;
    }
    open(retiredGuid, retired.getId(), survivor.getId(), runId.get());
    open(survivorGuid, survivor.getId(), retired.getId(), runId.get());
    return true;
  }

  /**
   * Picks the P4K run the cases are filed under.
   *
   * @param retired the retired-candidate row
   * @param survivor the survivor-candidate row
   * @return the run id, empty before the first P4K run
   */
  @NotNull
  private Optional<UUID> runOf(@NotNull GameItem retired, @NotNull GameItem survivor) {
    if (survivor.getP4kSeenRunId() != null) {
      return Optional.of(survivor.getP4kSeenRunId());
    }
    if (retired.getP4kSeenRunId() != null) {
      return Optional.of(retired.getP4kSeenRunId());
    }
    return runs.findFirstByOrderByAppliedAtDesc().map(P4kImportRun::getId);
  }

  /**
   * Creates the case of one GUID unless it exists; an existing case keeps its runs.
   *
   * @param guid the P4K GUID under review
   * @param rowId the row holding the GUID
   * @param collidingRowId the other row the GUID would be merged into
   * @param runId the run a new case is filed under
   */
  private void open(
      @NotNull UUID guid, @NotNull UUID rowId, @NotNull UUID collidingRowId, @NotNull UUID runId) {
    P4kReviewCase reviewCase =
        reviewCases
            .findByKindAndGuidAndReason(P4kKind.ITEM, guid, P4kReviewReason.GUID_CONFLICT)
            .orElseGet(() -> {
              P4kReviewCase created = new P4kReviewCase();
              created.setKind(P4kKind.ITEM);
              created.setGuid(guid);
              created.setReason(P4kReviewReason.GUID_CONFLICT);
              created.setFirstRunId(runId);
              created.setLastRunId(runId);
              return created;
            });
    if (reviewCase.getRowId() == null) {
      reviewCase.setRowId(rowId);
    }
    if (reviewCase.getOtherRowId() == null) {
      reviewCase.setOtherRowId(collidingRowId);
    }
    reviewCases.save(reviewCase);
  }
}
