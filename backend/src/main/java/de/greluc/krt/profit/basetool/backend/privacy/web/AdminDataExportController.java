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

package de.greluc.krt.profit.basetool.backend.privacy.web;

import de.greluc.krt.profit.basetool.backend.privacy.api.DataExportDocuments;
import de.greluc.krt.profit.basetool.backend.privacy.api.DataExportReportService;
import de.greluc.krt.profit.basetool.backend.privacy.api.DataExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * The admin variant of the Art. 15 / Art. 20 data export (REQ-SEC-058), for members who cannot
 * export their own data.
 *
 * <p>Uses the same projections and anonymisation as the self-service export, and is audited with
 * {@code bySelf = false}.
 */
@RestController
@RequestMapping("/api/v1/users/admin/{userId}/export")
@RequiredArgsConstructor
public class AdminDataExportController {

  private final DataExportService dataExportService;
  private final DataExportReportService dataExportReportService;
  private final ObjectMapper objectMapper;

  /**
   * The member's complete export as indented JSON, named {@code Datenexport <name> <yy-MM-dd>}.
   *
   * @param userId the member the export is about
   * @return the export as an attachment
   */
  @GetMapping
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(
      summary = "Export another member's data (Art. 15 / Art. 20)",
      description =
          "For an access request from somebody who cannot sign in - a locked-out member, or a "
              + "disabled account. Identical projections and identical third-party anonymisation "
              + "as the self-service export: an admin export is not a fuller one.")
  @ApiResponses(
      @ApiResponse(
          responseCode = "200",
          description = "The export",
          content =
              @Content(
                  mediaType = MediaType.APPLICATION_JSON_VALUE,
                  schema = @Schema(implementation = DataExportService.DataExport.class))))
  public ResponseEntity<byte[]> exportJson(@PathVariable UUID userId) {
    DataExportService.DataExport export = dataExportService.export(userId);
    byte[] json = DataExportDocuments.json(objectMapper, export);
    dataExportService.recordExport(userId, "json", export.totalRows(), false);
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION, DataExportDocuments.contentDisposition(export, "json"))
        .contentType(MediaType.APPLICATION_JSON)
        .body(json);
  }

  /**
   * The member's export as a readable PDF.
   *
   * @param userId the member the export is about
   * @return the PDF
   */
  @GetMapping("/pdf")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Export another member's data as a PDF")
  public ResponseEntity<byte[]> exportPdf(@PathVariable UUID userId) {
    DataExportService.DataExport export = dataExportService.export(userId);
    byte[] pdf = dataExportReportService.renderPdf(export);
    dataExportService.recordExport(userId, "pdf", export.totalRows(), false);
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION, DataExportDocuments.contentDisposition(export, "pdf"))
        .contentType(MediaType.APPLICATION_PDF)
        .body(pdf);
  }
}
