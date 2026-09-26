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

import de.greluc.krt.profit.basetool.backend.service.DataExportReportService;
import de.greluc.krt.profit.basetool.backend.service.DataExportService;
import de.greluc.krt.profit.basetool.backend.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own Art. 15 / Art. 20 data export as JSON (full, portable) or PDF (readable summary
 * with a per-section inventory) (REQ-SEC-058).
 *
 * <p>The subject is always the caller from the token; no endpoint takes a user id. Every export is
 * audit-logged.
 */
@RestController
@RequestMapping("/api/v1/users/me/export")
@RequiredArgsConstructor
public class DataExportController {

  private final DataExportService dataExportService;
  private final DataExportReportService dataExportReportService;
  private final UserService userService;

  /**
   * The caller's complete export as JSON.
   *
   * @param jwt the caller's validated token
   * @return the export
   */
  @GetMapping
  @PreAuthorize("isAuthenticated()")
  @Operation(
      summary = "Export my own data (Art. 15 / Art. 20)",
      description =
          "The complete export as JSON. Every section carries its legal basis: ART_15 for the "
              + "right of access, ART_15_20 where the data is additionally portable under Art. 20 "
              + "(provided by the member, on consent or contract). Third-party data is excluded by "
              + "the projections rather than scrubbed afterwards; free text the member wrote has "
              + "other members' handles replaced.")
  public ResponseEntity<DataExportService.DataExport> exportJson(@AuthenticationPrincipal Jwt jwt) {
    UUID userId = userService.getUserIdFromJwt(jwt);
    DataExportService.DataExport export = dataExportService.export(userId);
    dataExportService.recordExport(userId, "json", export.totalRows(), true);
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(export);
  }

  /**
   * The caller's export as a readable PDF.
   *
   * @param jwt the caller's validated token
   * @return the PDF
   */
  @GetMapping("/pdf")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      summary = "Export my own data as a PDF",
      description =
          "The readable half: master data in full, plus an inventory of every section with its row "
              + "count and legal basis. The long lists are in the JSON export, which the document "
              + "points at - a PDF of thousands of warehouse movements serves the right of access "
              + "worse than a short document that says exactly what exists.")
  @ApiResponses(@ApiResponse(responseCode = "200", description = "The PDF"))
  public ResponseEntity<byte[]> exportPdf(@AuthenticationPrincipal Jwt jwt) {
    UUID userId = userService.getUserIdFromJwt(jwt);
    DataExportService.DataExport export = dataExportService.export(userId);
    byte[] pdf = dataExportReportService.renderPdf(export);
    dataExportService.recordExport(userId, "pdf", export.totalRows(), true);
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename("datenauskunft-" + userId + ".pdf")
                .build()
                .toString())
        .contentType(MediaType.APPLICATION_PDF)
        .body(pdf);
  }
}
