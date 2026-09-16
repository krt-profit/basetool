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
 * The admin variant of the Art. 15 / Art. 20 data export (REQ-SEC-058).
 *
 * <p><b>Why it exists at all</b>, given that every member can export their own: an access request
 * does not always come from somebody who can log in. A member who has been locked out, or an
 * account already disabled in Keycloak, still has the right — and serving it by hand across ~25
 * tables is precisely the manual, error-prone work this feature was built to remove.
 *
 * <p>It shares the <b>same projections and the same anonymisation</b> as the self-service path. An
 * admin export is not a fuller one: a third party's data is no more disclosable to an admin acting
 * on somebody's Art. 15 request than it is to the member.
 *
 * <p>Audited with {@code bySelf = false}, which is the distinction that matters when reviewing the
 * trail: a member reading their own record is unremarkable, an admin reading somebody else's is the
 * thing an audit exists to make answerable.
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
    // Pinned, not negotiated -- see DataExportController#exportJson. An admin serving an access
    // request has to be able to hand the member a file they can actually open.
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(export);
  }

  /**
   * The member's export as a readable PDF.
   *
   * @param userId the member the export is about
   * @return the PDF
   */
  // No `produces` -- it is a mapping condition, and the frontend's Accept header does not include
  // application/pdf, so it would answer 406. See DataExportController#exportPdf.
  @GetMapping("/pdf")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Export another member's data as a PDF")
  public ResponseEntity<byte[]> exportPdf(@PathVariable UUID userId) {
    byte[] pdf = dataExportReportService.renderPdf(userId);
    dataExportService.recordExport(userId, "pdf", -1, false);
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
