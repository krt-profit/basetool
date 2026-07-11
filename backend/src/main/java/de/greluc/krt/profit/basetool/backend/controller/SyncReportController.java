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

package de.greluc.krt.profit.basetool.backend.controller;

import de.greluc.krt.profit.basetool.backend.mapper.SyncReportMapper;
import de.greluc.krt.profit.basetool.backend.model.ExternalSyncReport;
import de.greluc.krt.profit.basetool.backend.model.SyncSourceSystem;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.SyncReportDto;
import de.greluc.krt.profit.basetool.backend.model.dto.SyncReportPurgeResultDto;
import de.greluc.krt.profit.basetool.backend.service.SyncReportService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only read surface for the {@code external_sync_report} audit log (SC_WIKI_SYNC_PLAN.md
 * §8.8). Backs the {@code /admin/sync-reports} frontend pages.
 *
 * <p>Class-level {@code @PreAuthorize("hasRole('ADMIN')")} — sync diagnostics are an administration
 * concern. The endpoint is read-only; the sync services are the only writers, via {@link
 * SyncReportService}. Ordering is fixed to newest-first in the repository, so the endpoint exposes
 * only page / size, not a sort parameter (no user-controlled sort field to whitelist).
 */
@RestController
@RequestMapping("/api/v1/sync-reports")
@RequiredArgsConstructor
@Transactional(readOnly = true)
@PreAuthorize(Roles.HAS_ROLE_ADMIN)
public class SyncReportController {

  private static final int DEFAULT_PAGE_SIZE = 50;
  private static final int MAX_PAGE_SIZE = 200;

  private final SyncReportService syncReportService;
  private final SyncReportMapper syncReportMapper;

  /**
   * Returns one page of sync-report events, newest-first. The optional {@code source} filter
   * narrows to {@code UEX} or {@code SCWIKI}; an absent / unrecognised value returns the combined
   * view across both catalogues.
   *
   * @param source optional catalogue filter ({@code "UEX"} / {@code "SCWIKI"}, case-insensitive)
   * @param page zero-based page index (negative coerced to 0)
   * @param size page size (clamped to 1..200, default 50)
   * @return a page of sync-report DTOs
   */
  @Operation(
      summary = "List sync-report events",
      description =
          "Admin-only. Returns the external sync-report audit log newest-first, optionally "
              + "filtered to one source system (UEX / SCWIKI).")
  @ApiResponses({@ApiResponse(responseCode = "200", description = "Sync-report page returned")})
  @GetMapping
  public PageResponse<SyncReportDto> listEvents(
      @RequestParam(required = false) String source,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    int safePage = page == null || page < 0 ? 0 : page;
    int safeSize = size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
    SyncSourceSystem sourceFilter = parseSource(source);

    Page<ExternalSyncReport> events =
        syncReportService.findEvents(sourceFilter, PageRequest.of(safePage, safeSize));
    return PageResponse.of(events.map(syncReportMapper::toDto));
  }

  /**
   * Deletes sync-report events older than {@code olderThanDays} days, optionally scoped to one
   * source. Backs the admin "delete reports older than X days" maintenance action. An absent /
   * unrecognised {@code source} purges both catalogues; a recognised one confines the purge to it.
   *
   * <p>Method-level {@code @Transactional} (read-write) overrides the class-level {@code readOnly =
   * true} so the {@code @Modifying} delete runs in a writable transaction.
   *
   * @param source optional catalogue filter ({@code "UEX"} / {@code "SCWIKI"}, case-insensitive)
   * @param olderThanDays minimum age in days a report must exceed to be deleted (must be at least
   *     1)
   * @return the number of rows deleted
   */
  @Operation(
      summary = "Delete old sync-report events",
      description =
          "Admin-only. Deletes sync-report events older than the given number of days, optionally "
              + "confined to one source system (UEX / SCWIKI). Returns the number of rows deleted.")
  @ApiResponses({@ApiResponse(responseCode = "200", description = "Old sync-report rows deleted")})
  @DeleteMapping
  @Transactional
  public SyncReportPurgeResultDto deleteOldEvents(
      @RequestParam(required = false) String source, @RequestParam int olderThanDays) {
    SyncSourceSystem sourceFilter = parseSource(source);
    int deleted = syncReportService.deleteOlderThan(sourceFilter, olderThanDays);
    return new SyncReportPurgeResultDto(deleted);
  }

  /**
   * Parses the optional {@code source} request parameter into a {@link SyncSourceSystem}, or {@code
   * null} for the combined view. Unrecognised values map to {@code null} rather than a 400 — the
   * combined view is the natural fallback for a typo'd filter.
   *
   * @param source raw request parameter
   * @return the parsed source, or {@code null} for combined / unrecognised
   */
  private static SyncSourceSystem parseSource(String source) {
    if (source == null || source.isBlank()) {
      return null;
    }
    try {
      return SyncSourceSystem.valueOf(source.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
