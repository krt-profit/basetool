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
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the P4K run list and the change reports for the admin page (REQ-P4K-014, REQ-P4K-015): an
 * applied run's report from {@code p4k_import_change}, a preview's from its job.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class P4kImportReportService {

  private final P4kImportRunRepository runRepository;
  private final P4kImportChangeRepository changeRepository;
  private final P4kImportJobService jobService;
  private final P4kPreviewReportCodec reportCodec;

  /**
   * Pages the applied runs, newest first.
   *
   * @param pageable the page
   * @return the page of runs
   */
  @NotNull
  public Page<P4kImportRunDto> runs(@NotNull Pageable pageable) {
    return runRepository.findAllByOrderByAppliedAtDesc(pageable).map(P4kImportRunDto::of);
  }

  /**
   * Pages an applied run's report in the order the run found its rows.
   *
   * @param runId the run
   * @param kind the kind to show, or {@code null} for all
   * @param changeType the change type to show, or {@code null} for all
   * @param pageable the page
   * @return the page of report rows
   * @throws NotFoundException when no run has the id
   */
  @NotNull
  public Page<P4kImportChangeDto> runChanges(
      @NotNull UUID runId,
      @Nullable P4kKind kind,
      @Nullable P4kChangeType changeType,
      @NotNull Pageable pageable) {
    if (!runRepository.existsById(runId)) {
      throw new NotFoundException("P4K import run " + runId + " was not found.");
    }
    return changeRepository.findPage(runId, kind, changeType, pageable).map(P4kImportChangeDto::of);
  }

  /**
   * Pages the report a preview job computed, in the order it found its rows.
   *
   * @param jobId the preview job
   * @param kind the kind to show, or {@code null} for all
   * @param changeType the change type to show, or {@code null} for all
   * @param pageable the page
   * @return the page of report rows
   * @throws NotFoundException when the job is gone or holds no preview report
   */
  @NotNull
  public Page<P4kImportChangeDto> previewChanges(
      @NotNull UUID jobId,
      @Nullable P4kKind kind,
      @Nullable P4kChangeType changeType,
      @NotNull Pageable pageable) {
    byte[] stored =
        Entities.require(
            jobService.loadPreviewReport(jobId),
            () -> "P4K import job " + jobId + " holds no preview report.");
    List<P4kImportChangeDto> rows =
        reportCodec.decode(stored).stream().filter(row -> row.matches(kind, changeType)).toList();
    int from = (int) Math.min(pageable.getOffset(), rows.size());
    int to = Math.min(from + pageable.getPageSize(), rows.size());
    return new PageImpl<>(rows.subList(from, to), pageable, rows.size());
  }
}
