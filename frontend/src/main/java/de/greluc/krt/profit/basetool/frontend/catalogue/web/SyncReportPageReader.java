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

package de.greluc.krt.profit.basetool.frontend.catalogue.web;

import de.greluc.krt.profit.basetool.frontend.catalogue.client.SyncReportBackendClient;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.SyncReportDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.SyncRunSummaryDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.web.SectionFallback;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Reads anomaly events and durable attempt history with independent availability. */
@Component
@RequiredArgsConstructor
@Slf4j
public class SyncReportPageReader {
  private static final int HISTORY_RANGE_DAYS = 30;
  private final SyncReportBackendClient client;

  /**
   * Reads the page sections without hiding healthy attempts behind an empty anomaly list.
   *
   * @param page event page index
   * @param source selected source, absent for the combined view
   * @return both sections and their independent availability
   */
  @NotNull
  public Data read(int page, @Nullable String source) {
    EventSection emptyEvents =
        new EventSection(new PageResponse<>(List.of(), page, 100, 0L, 0, List.of()), false);
    EventSection events =
        SectionFallback.load(
            log,
            "sync-report-events",
            () -> {
              PageResponse<SyncReportDto> response = client.syncReportPage(page, source);
              return response == null
                  ? emptyEvents
                  : new EventSection(
                      new PageResponse<>(
                          PageResponse.contentOf(response),
                          response.page(),
                          response.size(),
                          response.totalElements(),
                          response.totalPages(),
                          response.sort()),
                      true);
            },
            emptyEvents);
    AttemptSection attempts =
        SectionFallback.load(
            log,
            "sync-report-attempts",
            () -> {
              List<SyncRunSummaryDto> response = client.syncRunSummaries(HISTORY_RANGE_DAYS);
              return response == null
                  ? new AttemptSection(List.of(), false)
                  : new AttemptSection(
                      response.stream()
                          .filter(
                              summary -> source == null || source.equals(summary.sourceSystem()))
                          .toList(),
                      true);
            },
            new AttemptSection(List.of(), false));
    return new Data(events.page(), attempts.summaries(), events.available(), attempts.available());
  }

  /**
   * Page data without presentation or managed backend state.
   *
   * @param events event page
   * @param summaries recent attempts per selected source
   * @param eventsAvailable whether events were read successfully
   * @param attemptsAvailable whether durable attempts were read successfully
   */
  public record Data(
      PageResponse<SyncReportDto> events,
      List<SyncRunSummaryDto> summaries,
      boolean eventsAvailable,
      boolean attemptsAvailable) {}

  /** Available event page or its empty fallback. */
  private record EventSection(PageResponse<SyncReportDto> page, boolean available) {}

  /** Available source histories or their empty fallback. */
  private record AttemptSection(List<SyncRunSummaryDto> summaries, boolean available) {}
}
