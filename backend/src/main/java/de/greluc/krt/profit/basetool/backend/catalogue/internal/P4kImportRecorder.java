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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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

  /** The most GUIDs one read of the stored cases names. */
  private static final int READ_CHUNK = 1_000;

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
    List<P4kImportReport.ReviewFinding> findings = report.reviews();
    Map<CaseKey, P4kReviewCase> stored = storedCases(findings);
    List<P4kReviewCase> cases = new ArrayList<>(findings.size());
    for (P4kImportReport.ReviewFinding finding : findings) {
      P4kReviewCase reviewCase =
          stored.computeIfAbsent(CaseKey.of(finding), _ -> newCase(finding, runId));
      reviewCase.setLastRunId(runId);
      if (reviewCase.getRowId() == null) {
        reviewCase.setRowId(finding.rowId());
      }
      cases.add(reviewCase);
    }
    reviewCaseRepository.saveAll(cases);
  }

  /**
   * Reads the stored cases of the findings' kinds and GUIDs in chunks of {@value #READ_CHUNK}
   * GUIDs, which the unique index on kind, GUID and reason serves.
   *
   * @param findings the run's findings
   * @return the stored cases by kind, GUID and reason
   */
  @NotNull
  private Map<CaseKey, P4kReviewCase> storedCases(
      @NotNull List<P4kImportReport.ReviewFinding> findings) {
    Set<P4kKind> kinds =
        findings.stream().map(P4kImportReport.ReviewFinding::kind).collect(Collectors.toSet());
    List<UUID> guids =
        findings.stream().map(P4kImportReport.ReviewFinding::guid).distinct().toList();
    Map<CaseKey, P4kReviewCase> stored = new HashMap<>();
    for (int from = 0; from < guids.size(); from += READ_CHUNK) {
      for (P4kReviewCase reviewCase :
          reviewCaseRepository.findByKindInAndGuidIn(
              kinds, guids.subList(from, Math.min(guids.size(), from + READ_CHUNK)))) {
        stored.put(CaseKey.of(reviewCase), reviewCase);
      }
    }
    return stored;
  }

  /**
   * The identity of a case: one per kind, GUID and reason (REQ-P4K-016).
   *
   * @param kind the kind
   * @param guid the P4K GUID
   * @param reason the reason
   */
  private record CaseKey(
      @NotNull P4kKind kind, @NotNull UUID guid, @NotNull P4kReviewReason reason) {

    /**
     * Keys a finding.
     *
     * @param finding the finding
     * @return its key
     */
    @NotNull
    static CaseKey of(@NotNull P4kImportReport.ReviewFinding finding) {
      return new CaseKey(finding.kind(), finding.guid(), finding.reason());
    }

    /**
     * Keys a stored case.
     *
     * @param reviewCase the case
     * @return its key
     */
    @NotNull
    static CaseKey of(@NotNull P4kReviewCase reviewCase) {
      return new CaseKey(reviewCase.getKind(), reviewCase.getGuid(), reviewCase.getReason());
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
