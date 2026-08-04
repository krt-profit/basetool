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

import de.greluc.krt.profit.basetool.backend.model.dto.RefineryExtractDto;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryImportDraftDto;
import de.greluc.krt.profit.basetool.backend.service.RefineryImportService;
import de.greluc.krt.profit.basetool.backend.service.UserService;
import de.greluc.krt.profit.basetool.backend.web.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface of the refinery screenshot import (#434, epic #439): accepts the desktop extractor's
 * {@code RefineryExtract} JSON and returns a non-persisted {@link RefineryImportDraftDto} the
 * frontend pours into the existing create form. Deliberately separate from {@link
 * RefineryOrderController} — this controller never persists anything; saving the reviewed draft
 * still goes through the untouched {@code POST /api/v1/refinery-orders}.
 */
@RestController
@RequestMapping("/api/v1/refinery-orders")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RefineryImportController {

  private final RefineryImportService refineryImportService;
  private final UserService userService;

  /**
   * Builds a best-effort draft from an uploaded {@code RefineryExtract} (frozen contract v1, plan
   * §5). Envelope-level problems (wrong {@code schemaVersion}, non-SETUP panel, empty orders)
   * reject with 400 problem+json; content-level problems (unmatched names, skipped or un-quoted
   * rows, checksum mismatches) always return 200 with a draft plus issues.
   *
   * @param owner the acting member; for an ingest-gateway call this is the member it acts for
   * @param extract the validated extract payload
   * @return the draft order with issues and match counters — never persisted
   */
  @PostMapping(value = "/import-extract", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("isAuthenticated()")
  @Operation(
      summary =
          "Match a RefineryExtract JSON (desktop screenshot extraction) against master data and"
              + " return a non-persisted draft for the create form.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Draft built; issues list any review flags."),
    @ApiResponse(
        responseCode = "400",
        description = "Unsupported schemaVersion or panel type, or payload validation failed."),
    @ApiResponse(responseCode = "401", description = "Caller is not authenticated.")
  })
  public RefineryImportDraftDto importExtract(
      @CurrentUserId UUID owner, @RequestBody @Valid @NotNull RefineryExtractDto extract) {
    // Plain @CurrentUserId again. When the ingest gateway calls this, ActingMemberFilter has
    // already
    // replaced the security identity with the member it acts for (ADR-0129), so there is nothing
    // special to do here — which is exactly the point of doing it in the filter rather than at the
    // call site: EVERY identity consumer sees the member, not just the two that were remembered.
    return refineryImportService.buildDraft(extract, owner);
  }
}
