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

package de.greluc.krt.profit.basetool.frontend.catalogue.client;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.ManufacturerWikiDuplicateDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.ManufacturerWikiPrimaryRequest;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.SyncReportDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.SyncReportPurgeResultDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.SyncRunSummaryDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Typed backend client of the sync-report audit log: its pages, the purge of old events and the
 * count of runs inside a range (REQ-UI-034, ADR-0032).
 */
@Service
@RequiredArgsConstructor
public class SyncReportBackendClient {

  private static final String SYNC_REPORTS_URI = "/api/v1/catalog/admin/sync-reports";

  private static final int SYNC_REPORT_PAGE_SIZE = 100;

  private static final ParameterizedTypeReference<PageResponse<SyncReportDto>> SYNC_REPORT_PAGE =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<SyncRunSummaryDto>> SYNC_RUN_SUMMARIES =
      new ParameterizedTypeReference<>() {};

  private static final String WIKI_DUPLICATES_URI =
      "/api/v1/catalog/admin/manufacturer-wiki-duplicates";

  private static final ParameterizedTypeReference<List<ManufacturerWikiDuplicateDto>>
      WIKI_DUPLICATES = new ParameterizedTypeReference<>() {};

  /** The backend seam every sync-report call goes through. */
  private final BackendApiClient backendApiClient;

  /**
   * Reads one page of sync-report events.
   *
   * @param page zero-based page index, never negative
   * @param source {@code SCWIKI} or {@code UEX}, or {@code null} for both catalogues
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<SyncReportDto> syncReportPage(int page, @Nullable String source) {
    UriComponentsBuilder uriBuilder =
        UriComponentsBuilder.fromPath(SYNC_REPORTS_URI)
            .queryParam("page", page)
            .queryParam("size", SYNC_REPORT_PAGE_SIZE);
    if (source != null) {
      uriBuilder.queryParam("source", source);
    }
    String uri = uriBuilder.toUriString();
    return backendApiClient.get(uri, SYNC_REPORT_PAGE);
  }

  /**
   * Counts the runs of each synchronisation and import that finished inside the last days.
   *
   * @param days the range in days, at least 1
   * @return one summary per source, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<SyncRunSummaryDto> syncRunSummaries(int days) {
    return backendApiClient.get(SYNC_REPORTS_URI + "/runs?days={days}", SYNC_RUN_SUMMARIES, days);
  }

  /**
   * Deletes the sync-report events older than a number of days.
   *
   * @param source {@code SCWIKI} or {@code UEX}, or {@code null} for both catalogues
   * @param days the minimum age in days of a deleted event
   * @return the deleted count, or {@code null} when the backend sent no body
   */
  @Nullable
  public SyncReportPurgeResultDto purgeSyncReports(@Nullable String source, int days) {
    UriComponentsBuilder uri =
        UriComponentsBuilder.fromPath(SYNC_REPORTS_URI).queryParam("olderThanDays", days);
    if (source != null) {
      uri.queryParam("source", source);
    }
    return backendApiClient.delete(uri.toUriString(), SyncReportPurgeResultDto.class);
  }

  /**
   * Reads every manufacturer the SC Wiki lists more than once (ADR-0361).
   *
   * @return the duplicated manufacturers, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<ManufacturerWikiDuplicateDto> manufacturerWikiDuplicates() {
    return backendApiClient.get(WIKI_DUPLICATES_URI, WIKI_DUPLICATES);
  }

  /**
   * Makes one SC Wiki entry the manufacturer's primary link; the others stay aliases.
   *
   * @param manufacturerId the local manufacturer id
   * @param request the chosen Wiki entry and the version the admin last saw
   * @return the manufacturer after the choice, or {@code null} when the backend sent no body
   */
  @Nullable
  public ManufacturerWikiDuplicateDto chooseManufacturerWikiPrimary(
      UUID manufacturerId, ManufacturerWikiPrimaryRequest request) {
    return backendApiClient.put(
        WIKI_DUPLICATES_URI + "/{manufacturerId}/primary",
        request,
        ManufacturerWikiDuplicateDto.class,
        manufacturerId);
  }
}
