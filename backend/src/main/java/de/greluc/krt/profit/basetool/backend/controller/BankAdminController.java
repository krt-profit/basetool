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

import de.greluc.krt.profit.basetool.backend.model.BankAuditEventType;
import de.greluc.krt.profit.basetool.backend.model.dto.AuditPurgeResultDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankAuditEventDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankWipeResetResultDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.service.BankAuditReportService;
import de.greluc.krt.profit.basetool.backend.service.BankAuditService;
import de.greluc.krt.profit.basetool.backend.service.BankLedgerService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import de.greluc.krt.profit.basetool.backend.web.PaginationUtil;
import de.greluc.krt.profit.basetool.backend.web.PdfResponses;
import de.greluc.krt.profit.basetool.backend.web.UserZone;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only bank surface (epic #556, REQ-BANK-012/-013): the wipe reset and the audit-log viewer.
 * Double-gated — the {@code /api/v1/bank/admin/**} URL matcher requires {@code ADMIN} before these
 * method gates even run; bank management explicitly does NOT see the audit log (REQ-BANK-010).
 */
@RestController
@RequestMapping("/api/v1/bank/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class BankAdminController {

  private static final Set<String> AUDIT_SORT_FIELDS = Set.of("occurredAt", "id");

  private final BankLedgerService bankLedgerService;
  private final BankAuditService bankAuditService;
  private final BankAuditReportService bankAuditReportService;

  /**
   * Resets all account balances and holder sub-balances to zero after a Star Citizen wipe
   * (REQ-BANK-013). Idempotent — the result reports zero accounts on an all-zero bank.
   *
   * @return counts and total for the admin notice
   */
  @Operation(summary = "Reset all bank balances to zero after an SC wipe (admin)")
  @PostMapping("/wipe-reset")
  @Transactional
  public BankWipeResetResultDto wipeReset() {
    return bankLedgerService.resetAllBalances();
  }

  /**
   * Pages over the filtered audit log (REQ-BANK-012), newest first by default.
   *
   * @param from period start (inclusive, ISO instant), or absent
   * @param to period end (inclusive, ISO instant), or absent
   * @param actorUserId filter on the acting user, or absent
   * @param accountId filter on the affected account, or absent
   * @param eventType filter on the event type, or absent
   * @param page zero-based page index
   * @param size page size
   * @param sort whitelisted sort spec
   * @return one page of audit events
   */
  @Operation(summary = "Read the bank audit log (admin; paged, filterable)")
  @GetMapping("/audit")
  @Transactional(readOnly = true)
  public PageResponse<BankAuditEventDto> getAuditLog(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant to,
      @RequestParam(required = false) UUID actorUserId,
      @RequestParam(required = false) UUID accountId,
      @RequestParam(required = false) BankAuditEventType eventType,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    String effectiveSort = sort == null || sort.isBlank() ? "occurredAt,desc" : sort;
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page, size, effectiveSort, AUDIT_SORT_FIELDS, "occurredAt");
    Page<BankAuditEventDto> result =
        bankAuditService.getEvents(from, to, actorUserId, accountId, eventType, pageable);
    return PageResponse.of(result);
  }

  /**
   * Exports the bank audit log as a KRT-design PDF for a chosen period (REQ-AUDIT-001 unified
   * viewer; mirrors the per-area audit export). The optional {@code X-User-Time-Zone} header
   * localizes the timestamps; an invalid IANA zone is silently dropped. The export is itself
   * audit-logged.
   *
   * @param from period start (inclusive, ISO instant)
   * @param to period end (inclusive, ISO instant); must not be before {@code from}
   * @param userZone the resolved {@code X-User-Time-Zone} zone, or {@code null} for UTC
   * @return PDF body with {@code application/pdf} and attachment Content-Disposition
   */
  @Operation(summary = "Export the bank audit log as a PDF for a period (admin)")
  @GetMapping("/audit/export")
  @Parameter(
      name = "X-User-Time-Zone",
      in = ParameterIn.HEADER,
      required = false,
      schema = @Schema(type = "string"))
  public ResponseEntity<byte[]> exportAuditLog(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
      @UserZone ZoneId userZone) {
    byte[] pdf = bankAuditReportService.generateAuditLogPdf(from, to, userZone);
    return PdfResponses.pdfAttachment(pdf, "audit-bank.pdf");
  }

  /**
   * Exports the bank audit log as a downloadable JSON document for a chosen period (REQ-AUDIT-003).
   * The export is itself audit-logged.
   *
   * @param from period start (inclusive, ISO instant)
   * @param to period end (inclusive, ISO instant); must not be before {@code from}
   * @return the period's bank audit events as JSON with attachment headers
   */
  @Operation(summary = "Export the bank audit log as JSON for a period (admin)")
  @GetMapping("/audit/export.json")
  public ResponseEntity<java.util.List<BankAuditEventDto>> exportAuditLogJson(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
    java.util.List<BankAuditEventDto> events =
        bankAuditReportService.generateAuditLogJson(from, to);
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setContentDispositionFormData("attachment", "audit-bank.json");
    return ResponseEntity.ok().headers(headers).body(events);
  }

  /**
   * Purges bank audit-log entries older than a cutoff — the admin retention delete (REQ-AUDIT-004).
   * The deletion is itself audit-logged ({@code AUDIT_LOG_PURGED}). The UI warns the admin to
   * export a PDF/JSON backup beforehand; the backend does not enforce a prior export.
   *
   * @param before the exclusive cutoff (ISO instant); entries older than this are removed
   * @return the number of bank audit rows deleted
   */
  @Operation(summary = "Purge the bank audit log's entries older than a cutoff (admin)")
  @DeleteMapping("/audit")
  public AuditPurgeResultDto purgeAuditLog(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant before) {
    return new AuditPurgeResultDto(bankAuditService.purgeBefore(before));
  }
}
