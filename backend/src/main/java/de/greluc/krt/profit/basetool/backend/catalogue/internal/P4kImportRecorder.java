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

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Persists an APPLY's change report and review cases (REQ-P4K-014, REQ-P4K-016): every report row
 * under the run, and each review case once, keeping a case found by an earlier run and pointing it
 * at the latest run.
 */
@Component
@RequiredArgsConstructor
public class P4kImportRecorder {

  private final P4kImportChangeRepository changeRepository;
  private final P4kReviewCaseRepository reviewCaseRepository;

  /**
   * Writes the report of one run; must run inside the APPLY's transaction.
   *
   * @param runId the run
   * @param report the run's report
   */
  public void record(@NotNull UUID runId, @NotNull P4kImportReport report) {
    List<P4kImportChange> changes = report.changes();
    changes.forEach(change -> change.setRunId(runId));
    changeRepository.saveAll(changes);
    for (P4kImportReport.ReviewFinding finding : report.reviews()) {
      P4kReviewCase reviewCase =
          reviewCaseRepository
              .findByKindAndGuidAndReason(finding.kind(), finding.guid(), finding.reason())
              .orElseGet(() -> newCase(finding, runId));
      reviewCase.setLastRunId(runId);
      if (reviewCase.getRowId() == null) {
        reviewCase.setRowId(finding.rowId());
      }
      reviewCaseRepository.save(reviewCase);
    }
  }

  /**
   * Creates the case of a finding no earlier run made.
   *
   * @param finding the finding
   * @param runId the run that found it first
   * @return the unsaved case
   */
  @NotNull
  private static P4kReviewCase newCase(
      @NotNull P4kImportReport.ReviewFinding finding, @NotNull UUID runId) {
    P4kReviewCase reviewCase = new P4kReviewCase();
    reviewCase.setKind(finding.kind());
    reviewCase.setGuid(finding.guid());
    reviewCase.setReason(finding.reason());
    reviewCase.setFirstRunId(runId);
    return reviewCase;
  }
}
