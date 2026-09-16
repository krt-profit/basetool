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
 * The member's own Art. 15 / Art. 20 data export (REQ-SEC-058).
 *
 * <p>Two formats, and they are not alternatives:
 *
 * <ul>
 *   <li><b>JSON</b> is the full disclosure and the Art. 20 portable copy — every section, every
 *       row, each marked with its legal basis so the portable subset is identifiable.
 *   <li><b>PDF</b> is the readable answer: master data in full, then an inventory naming every
 *       section with its row count and basis, so the document is complete about <em>what</em> is
 *       held even where it does not print it.
 * </ul>
 *
 * <p>The subject is always the caller, derived from the token. No endpoint here takes a user id, so
 * there is no parameter with which one member could export another's data — the same property that
 * makes the erasure-request surface safe to open to everybody.
 *
 * <p>Both formats are audit-logged. An export is a read of everything the system holds about a
 * person, and the one operation whose misuse would otherwise leave no trace.
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
    // The content type is pinned on the response rather than left to negotiation, and that is the
    // whole point of returning a ResponseEntity here. Art. 20 asks for a "structured, commonly
    // used and machine-readable format"; the frontend's shared WebClient sends
    // `Accept: application/cbor, application/json` under the default APP_HTTP_CODEC=CBOR, and a
    // negotiated response would therefore be CBOR -- which the proxy then hands to the member as
    // `datenauskunft.json`, a binary blob no JSON tool opens. A preset concrete Content-Type
    // short-circuits ProducesRequestCondition, so the member gets JSON whatever the codec default
    // is. Same reason AuditAdminController#exportAuditLogJson sets it (REQ-SEC-058, ADR-0185).
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(export);
  }

  /**
   * The caller's export as a readable PDF.
   *
   * @param jwt the caller's validated token
   * @return the PDF
   */
  // No `produces` on the mapping. It reads like a declaration of the response type and is in fact a
  // mapping *condition*: the frontend's shared WebClient sends `Accept: application/cbor,
  // application/json`, neither of which is compatible with application/pdf, so
  // ProducesRequestCondition would not match and Spring would answer 406 before the handler ran.
  // The content type belongs on the ResponseEntity below -- the pattern every other PDF endpoint in
  // this codebase already uses (AuditAdminController, BankExportController, JobOrderController).
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
    byte[] pdf = dataExportReportService.renderPdf(userId);
    dataExportService.recordExport(userId, "pdf", -1, true);
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            // The subject's id, not their handle: a filename ends up in shells, logs and mail
            // clients, and a name there is a leak nobody chose.
            ContentDisposition.attachment()
                .filename("datenauskunft-" + userId + ".pdf")
                .build()
                .toString())
        .contentType(MediaType.APPLICATION_PDF)
        .body(pdf);
  }
}
