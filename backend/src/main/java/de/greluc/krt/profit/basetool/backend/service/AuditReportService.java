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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.mapper.AuditEventMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditDomain;
import de.greluc.krt.profit.basetool.backend.model.AuditEvent;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.dto.AuditEventDto;
import de.greluc.krt.profit.basetool.backend.repository.AuditEventRepository;
import de.greluc.krt.profit.basetool.backend.service.pdf.AuditLogPdfFormat;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Renders one area's activity audit log for a chosen period as a KRT-design PDF or as JSON
 * (REQ-AUDIT-001/-003) and records each export itself in the audit trail. Write transaction on
 * purpose: the audit insert runs {@code MANDATORY} inside it. Labels are German from the backend
 * message bundle; the PDF visual layer is the shared {@link AuditLogPdfFormat} / {@code
 * KrtPdfSupport}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditReportService {

  /**
   * Hard ceiling on a single export's row count. A larger period is rejected (not truncated) so the
   * document stays renderable and one request cannot OOM; admins narrow the period or purge older
   * entries (REQ-AUDIT-004). Generous on purpose — only a pathological span trips it.
   */
  private static final long MAX_EXPORT_ROWS = 100_000;

  private final AuditEventRepository auditEventRepository;
  private final AuditService auditService;
  private final AuditEventMapper auditEventMapper;
  private final MessageSource messageSource;

  /**
   * Generates the audit-log PDF for one area and period and records the export in the audit log.
   *
   * @param domain the area to export
   * @param from period start (inclusive)
   * @param to period end (inclusive); must not be before {@code from}
   * @param userZone the zone to render timestamps in; {@code null} falls back to UTC
   * @return the PDF bytes
   * @throws BadRequestException when the period is inverted
   */
  @Transactional
  public byte @NotNull [] generateAuditLogPdf(
      @NotNull AuditDomain domain,
      @NotNull Instant from,
      @NotNull Instant to,
      @Nullable ZoneId userZone) {
    if (from.isAfter(to)) {
      throw new BadRequestException("Audit export period start must not be after its end");
    }
    ensureWithinExportCap(auditEventRepository.countForExport(domain, from, to), domain.name());
    List<AuditEvent> events = auditEventRepository.findForExport(domain, from, to);
    List<AuditLogPdfFormat.Row> rows =
        events.stream()
            .map(
                e ->
                    new AuditLogPdfFormat.Row(
                        e.getOccurredAt(),
                        e.getActorHandle(),
                        // The audit document prints the raw, language-neutral event code (the
                        // on-screen viewer shows the localized label); this keeps the trail
                        // unambiguous and avoids duplicating ~50 labels into the backend bundle.
                        e.getEventType().name(),
                        e.getSubjectLabel() != null ? e.getSubjectLabel() : "—",
                        e.getDetails() != null ? e.getDetails() : ""))
            .toList();

    byte[] pdf =
        AuditLogPdfFormat.render(
            label("pdf.audit.title." + domain.name()), from, to, userZone, rows, this::label);

    auditService.record(
        exportEventType(domain),
        null,
        null,
        null,
        AuditDetails.of("format", "pdf").with("period", from + ".." + to));
    log.info("Audit log exported as PDF for domain {} ({} events)", domain, events.size());
    return pdf;
  }

  /**
   * Returns one area's audit events for a period as DTOs (the JSON export, REQ-AUDIT-003) and
   * records the export. Write transaction on purpose: the export audit insert runs {@code
   * MANDATORY} inside it.
   *
   * @param domain the area to export
   * @param from period start (inclusive)
   * @param to period end (inclusive); must not be before {@code from}
   * @return the period's events as DTOs, oldest first
   * @throws BadRequestException when the period is inverted
   */
  @Transactional
  public List<AuditEventDto> generateAuditLogJson(
      @NotNull AuditDomain domain, @NotNull Instant from, @NotNull Instant to) {
    if (from.isAfter(to)) {
      throw new BadRequestException("Audit export period start must not be after its end");
    }
    ensureWithinExportCap(auditEventRepository.countForExport(domain, from, to), domain.name());
    List<AuditEventDto> dtos =
        auditEventRepository.findForExport(domain, from, to).stream()
            .map(auditEventMapper::toDto)
            .toList();
    auditService.record(
        exportEventType(domain),
        null,
        null,
        null,
        AuditDetails.of("format", "json").with("period", from + ".." + to));
    log.info("Audit log exported as JSON for domain {} ({} events)", domain, dtos.size());
    return dtos;
  }

  /**
   * Rejects an export whose period would load more than {@link #MAX_EXPORT_ROWS} rows, guarding the
   * unpaged export query against OOM / a pathologically large document.
   *
   * @param count the number of rows the period would load
   * @param label the area label for the log/error message
   * @throws BadRequestException when the period exceeds the cap
   */
  private void ensureWithinExportCap(long count, @NotNull String label) {
    if (count > MAX_EXPORT_ROWS) {
      log.debug(
          "Audit export for {} would load {} rows (> cap {}); rejecting",
          label,
          count,
          MAX_EXPORT_ROWS);
      throw new BadRequestException(
          "Audit export period is too large ("
              + count
              + " entries, max "
              + MAX_EXPORT_ROWS
              + "). Narrow the period or purge older entries.");
    }
  }

  /**
   * The {@code *_AUDIT_EXPORTED} event type for an area.
   *
   * @param domain the exported area
   * @return its export event type
   */
  private static @NotNull AuditEventType exportEventType(@NotNull AuditDomain domain) {
    return switch (domain) {
      case INVENTORY -> AuditEventType.INVENTORY_AUDIT_EXPORTED;
      case JOB_ORDER -> AuditEventType.JOB_ORDER_AUDIT_EXPORTED;
      case REFINERY -> AuditEventType.REFINERY_AUDIT_EXPORTED;
      case PERSONAL_INVENTORY -> AuditEventType.PERSONAL_INVENTORY_AUDIT_EXPORTED;
      case MISSION -> AuditEventType.MISSION_AUDIT_EXPORTED;
      case OPERATION -> AuditEventType.OPERATION_AUDIT_EXPORTED;
      case ROLE -> AuditEventType.ROLE_AUDIT_EXPORTED;
      case PROMOTION -> AuditEventType.PROMOTION_AUDIT_EXPORTED;
      case MARKET -> AuditEventType.MARKET_AUDIT_EXPORTED;
    };
  }

  /**
   * Resolves one German PDF/audit label from the backend message bundle.
   *
   * @param key the message key
   * @return the resolved label
   */
  private @NotNull String label(@NotNull String key) {
    return messageSource.getMessage(key, null, Locale.GERMAN);
  }
}
