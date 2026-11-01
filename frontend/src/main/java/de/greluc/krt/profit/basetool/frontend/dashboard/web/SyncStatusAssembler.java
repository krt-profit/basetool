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
import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kImportJobDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.SyncReportDto;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.SyncSource;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.SyncStatusRow;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Reads the latest run of each synchronisation and import for the sync status widget (REQ-UI-034):
 * the newest sync-report event per catalogue and the newest P4K import job. A backend failure
 * propagates, so the widget shows its own error state.
 */
@Component
@RequiredArgsConstructor
class SyncStatusAssembler {

  private final CatalogueBackendClient catalogue;

  /**
   * Builds the widget's rows.
   *
   * @param rangeDays the range the latest runs are measured against, in days
   * @param now the instant the range ends at
   * @return one row per synchronisation and import, in display order
   */
  @NotNull
  List<SyncStatusRow> rows(int rangeDays, Instant now) {
    Instant since = now.minus(Duration.ofDays(rangeDays));
    return List.of(
        syncRow(SyncSource.UEX, since), syncRow(SyncSource.SCWIKI, since), importRow(since));
  }

  private SyncStatusRow syncRow(SyncSource source, Instant since) {
    PageResponse<SyncReportDto> page = catalogue.syncReportPage(0, source.name());
    Instant last =
        page == null || page.content() == null || page.content().isEmpty()
            ? null
            : page.content().getFirst().ranAt();
    return new SyncStatusRow(source, last, null, last != null && !last.isBefore(since));
  }

  private SyncStatusRow importRow(Instant since) {
    List<P4kImportJobDto> jobs = catalogue.p4kImportJobs();
    P4kImportJobDto latest = jobs == null || jobs.isEmpty() ? null : jobs.getFirst();
    Instant last = latest == null ? null : instantOf(latest);
    return new SyncStatusRow(
        SyncSource.P4K,
        last,
        latest == null ? null : latest.status(),
        last != null && !last.isBefore(since));
  }

  @Nullable
  private static Instant instantOf(P4kImportJobDto job) {
    String stamp = job.finishedAt() != null ? job.finishedAt() : job.createdAt();
    return stamp == null ? null : Instant.parse(stamp);
  }
}
