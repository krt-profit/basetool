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
import de.greluc.krt.profit.basetool.backend.mapper.BankAuditEventMapper;
import de.greluc.krt.profit.basetool.backend.model.BankAccount;
import de.greluc.krt.profit.basetool.backend.model.BankAuditEvent;
import de.greluc.krt.profit.basetool.backend.model.BankAuditEventType;
import de.greluc.krt.profit.basetool.backend.model.dto.BankAuditEventDto;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankAuditEventRepository;
import de.greluc.krt.profit.basetool.backend.service.pdf.AuditLogPdfFormat;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Renders the bank audit trail as a KRT-design PDF for a chosen period (REQ-AUDIT-001 unified
 * viewer) and records the export in the bank audit log. The bank keeps its own {@code
 * bank_audit_event} table, so this is a thin sibling of {@link AuditReportService} that feeds the
 * shared {@link AuditLogPdfFormat} renderer — the bank tab on the admin audit page exports through
 * here. Write transaction on purpose: {@link BankAuditService#record} runs {@code MANDATORY}
 * inside.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BankAuditReportService {

  /**
   * Hard ceiling on a single bank-audit export's row count. A larger period is rejected (not
   * truncated) so the document stays renderable and one request cannot OOM; admins narrow the
   * period or purge older entries (REQ-AUDIT-004). Generous on purpose — only a pathological span
   * trips it.
   */
  private static final long MAX_EXPORT_ROWS = 100_000;

  private final BankAuditEventRepository bankAuditEventRepository;
  private final BankAccountRepository bankAccountRepository;
  private final BankAuditService bankAuditService;
  private final BankAuditEventMapper bankAuditEventMapper;
  private final MessageSource messageSource;

  /**
   * Generates the bank audit-log PDF for a period and records the export as a bank audit event.
   *
   * @param from period start (inclusive)
   * @param to period end (inclusive); must not be before {@code from}
   * @param userZone the zone to render timestamps in; {@code null} falls back to UTC
   * @return the PDF bytes
   * @throws BadRequestException when the period is inverted
   */
  @Transactional
  public byte @NotNull [] generateAuditLogPdf(
      @NotNull Instant from, @NotNull Instant to, @Nullable ZoneId userZone) {
    if (from.isAfter(to)) {
      throw new BadRequestException("Audit export period start must not be after its end");
    }
    ensureWithinExportCap(bankAuditEventRepository.countForExport(from, to));
    List<BankAuditEvent> events = bankAuditEventRepository.findForExport(from, to);
    Map<java.util.UUID, String> accountNos = resolveAccountNos(events);

    List<AuditLogPdfFormat.Row> rows =
        events.stream()
            .map(
                e ->
                    new AuditLogPdfFormat.Row(
                        e.getOccurredAt(),
                        e.getActorHandle(),
                        // Raw event code (the on-screen viewer shows the localized label).
                        e.getEventType().name(),
                        e.getAccountId() != null
                            ? accountNos.getOrDefault(e.getAccountId(), "—")
                            : "—",
                        e.getDetails() != null ? e.getDetails() : ""))
            .toList();

    byte[] pdf =
        AuditLogPdfFormat.render(
            label("pdf.audit.title.BANK"), from, to, userZone, rows, this::label);

    bankAuditService.record(
        BankAuditEventType.AUDIT_LOG_EXPORTED,
        null,
        null,
        null,
        AuditDetails.of("format", "pdf").with("period", from + ".." + to));
    log.info("Bank audit log exported as PDF ({} events)", events.size());
    return pdf;
  }

  /**
   * Returns the bank audit events for a period as DTOs (the JSON export, REQ-AUDIT-003) and records
   * the export as a bank audit event. The affected accounts' display numbers are resolved
   * batch-wise, exactly as the on-screen viewer does.
   *
   * @param from period start (inclusive)
   * @param to period end (inclusive); must not be before {@code from}
   * @return the period's bank audit events as DTOs, oldest first
   * @throws BadRequestException when the period is inverted
   */
  @Transactional
  public List<BankAuditEventDto> generateAuditLogJson(@NotNull Instant from, @NotNull Instant to) {
    if (from.isAfter(to)) {
      throw new BadRequestException("Audit export period start must not be after its end");
    }
    ensureWithinExportCap(bankAuditEventRepository.countForExport(from, to));
    List<BankAuditEvent> events = bankAuditEventRepository.findForExport(from, to);
    Map<java.util.UUID, String> accountNos = resolveAccountNos(events);
    List<BankAuditEventDto> dtos =
        events.stream()
            .map(
                e ->
                    bankAuditEventMapper.toDto(
                        e, e.getAccountId() == null ? null : accountNos.get(e.getAccountId())))
            .toList();
    bankAuditService.record(
        BankAuditEventType.AUDIT_LOG_EXPORTED,
        null,
        null,
        null,
        AuditDetails.of("format", "json").with("period", from + ".." + to));
    log.info("Bank audit log exported as JSON ({} events)", events.size());
    return dtos;
  }

  /**
   * Rejects an export whose period would load more than {@link #MAX_EXPORT_ROWS} rows, guarding the
   * unpaged bank-audit export query against OOM / a pathologically large document.
   *
   * @param count the number of rows the period would load
   * @throws BadRequestException when the period exceeds the cap
   */
  private void ensureWithinExportCap(long count) {
    if (count > MAX_EXPORT_ROWS) {
      log.debug(
          "Bank audit export would load {} rows (> cap {}); rejecting", count, MAX_EXPORT_ROWS);
      throw new BadRequestException(
          "Audit export period is too large ("
              + count
              + " entries, max "
              + MAX_EXPORT_ROWS
              + "). Narrow the period or purge older entries.");
    }
  }

  /**
   * Resolves the display numbers of the accounts referenced by a batch of bank audit events in one
   * lookup (audit rows keep plain UUID references so they outlive aggregates).
   *
   * @param events the audit events
   * @return account id → display number, for the referenced accounts that still exist
   */
  private Map<java.util.UUID, String> resolveAccountNos(List<BankAuditEvent> events) {
    List<java.util.UUID> accountIds =
        events.stream()
            .map(BankAuditEvent::getAccountId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    return accountIds.isEmpty()
        ? Map.of()
        : bankAccountRepository.findAllById(accountIds).stream()
            .collect(Collectors.toMap(BankAccount::getId, BankAccount::getAccountNo));
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
