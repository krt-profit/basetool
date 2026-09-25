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
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin variant of the Art. 15 / Art. 20 data export (REQ-SEC-058), for members who cannot
 * export their own data.
 *
 * <p>Uses the same projections and anonymisation as the self-service export, and is audited with
 * {@code bySelf = false}.
 */
@RestController
@RequestMapping("/api/v1/admin/users/{userId}/export")
@RequiredArgsConstructor
public class AdminDataExportController {

  private final DataExportService dataExportService;
  private final DataExportReportService dataExportReportService;

  /**
   * The member's complete export as JSON.
   *
   * @param userId the member the export is about
   * @return the export
   */
  @GetMapping
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(
      summary = "Export another member's data (Art. 15 / Art. 20)",
      description =
          "For an access request from somebody who cannot sign in - a locked-out member, or a "
              + "disabled account. Identical projections and identical third-party anonymisation "
              + "as the self-service export: an admin export is not a fuller one.")
  public ResponseEntity<DataExportService.DataExport> exportJson(@PathVariable UUID userId) {
    DataExportService.DataExport export = dataExportService.export(userId);
    dataExportService.recordExport(userId, "json", export.totalRows(), false);
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(export);
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
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename("datenauskunft-" + userId + ".pdf")
                .build()
                .toString())
        .contentType(MediaType.APPLICATION_PDF)
        .body(pdf);
  }
}
