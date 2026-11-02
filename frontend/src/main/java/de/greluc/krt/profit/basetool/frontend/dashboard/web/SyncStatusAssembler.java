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

package de.greluc.krt.profit.basetool.frontend.dashboard.web;

import de.greluc.krt.profit.basetool.frontend.catalogue.client.CatalogueBackendClient;
import de.greluc.krt.profit.basetool.frontend.catalogue.client.SyncReportBackendClient;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kImportJobDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.SyncRunSummaryDto;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.SyncSource;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.SyncStatusRow;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Reads the runs of each synchronisation and import for the sync status widget (REQ-UI-034): the
 * count of runs inside the range and the latest run of each, and the newest P4K import job with its
 * status. A backend failure propagates, so the widget shows its own error state.
 */
@Component
@RequiredArgsConstructor
class SyncStatusAssembler {

  private final CatalogueBackendClient catalogue;
  private final SyncReportBackendClient syncReports;

  /**
   * Builds the widget's rows.
   *
   * @param rangeDays the range the runs are counted in, in days
   * @param now the instant the range ends at
   * @return one row per synchronisation and import, in display order
   */
  @NotNull
  List<SyncStatusRow> rows(int rangeDays, Instant now) {
    Instant since = now.minus(Duration.ofDays(rangeDays));
    List<SyncRunSummaryDto> summaries = syncReports.syncRunSummaries(rangeDays);
    return List.of(
        syncRow(SyncSource.UEX, summaryOf(summaries, SyncSource.UEX)),
        syncRow(SyncSource.SCWIKI, summaryOf(summaries, SyncSource.SCWIKI)),
        importRow(summaryOf(summaries, SyncSource.P4K), since));
  }

  @Nullable
  private static SyncRunSummaryDto summaryOf(
      @Nullable List<SyncRunSummaryDto> summaries, SyncSource source) {
    return summaries == null
        ? null
        : summaries.stream()
            .filter(summary -> source.name().equals(summary.sourceSystem()))
            .findFirst()
            .orElse(null);
  }

  private static SyncStatusRow syncRow(SyncSource source, @Nullable SyncRunSummaryDto summary) {
    long runs = summary == null ? 0 : summary.runs();
    return new SyncStatusRow(
        source, summary == null ? null : summary.lastRanAt(), null, runs > 0, runs);
  }

  private SyncStatusRow importRow(@Nullable SyncRunSummaryDto summary, Instant since) {
    List<P4kImportJobDto> jobs = catalogue.p4kImportJobs();
    P4kImportJobDto latest = jobs == null || jobs.isEmpty() ? null : jobs.getFirst();
    Instant last = latest == null ? null : instantOf(latest);
    return new SyncStatusRow(
        SyncSource.P4K,
        last,
        latest == null ? null : latest.status(),
        last != null && !last.isBefore(since),
        summary == null ? 0 : summary.runs());
  }

  @Nullable
  private static Instant instantOf(P4kImportJobDto job) {
    String stamp = job.finishedAt() != null ? job.finishedAt() : job.createdAt();
    return stamp == null ? null : Instant.parse(stamp);
  }
}
