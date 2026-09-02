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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.mapper.BankAuditEventMapper;
import de.greluc.krt.profit.basetool.backend.model.BankAuditEvent;
import de.greluc.krt.profit.basetool.backend.model.BankAuditEventType;
import de.greluc.krt.profit.basetool.backend.model.dto.BankAuditEventDto;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankAuditEventRepository;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;
import org.springframework.context.MessageSource;

/**
 * Unit tests for {@link BankAuditReportService} (REQ-AUDIT-003 — the bank tab's period export). The
 * bank keeps its own {@code bank_audit_event} table, so this is the bank sibling of {@link
 * AuditReportServiceTest}: the PDF carries the raw event code + actor handle (asserted via {@code
 * PdfTextExtractor}); the JSON maps the events; each export records a {@code AUDIT_LOG_EXPORTED}
 * bank audit event; an inverted period and an over-cap period are both rejected, and exactly the
 * cap is accepted.
 */
@ExtendWith(MockitoExtension.class)
class BankAuditReportServiceTest {

  @Mock private BankAuditEventRepository bankAuditEventRepository;
  @Mock private BankAccountRepository bankAccountRepository;
  @Mock private BankAuditService bankAuditService;
  @Mock private BankAuditEventMapper bankAuditEventMapper;
  @Mock private MessageSource messageSource;

  @InjectMocks private BankAuditReportService bankAuditReportService;

  @Test
  void exportPdf_rendersEventsAndRecordsExportEvent() throws IOException {
    // Given — one account-less bank audit row (account-less avoids a bankAccountRepository lookup).
    lenient()
        .when(messageSource.getMessage(any(String.class), isNull(), eq(Locale.GERMAN)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    Instant from = Instant.now().minus(1, ChronoUnit.HOURS);
    Instant to = Instant.now().plus(1, ChronoUnit.HOURS);
    BankAuditEvent event =
        BankAuditEvent.builder()
            .occurredAt(Instant.now())
            .eventType(BankAuditEventType.DEPOSIT_BOOKED)
            .actorHandle("banker_jo")
            .details("+100 aUEC")
            .build();
    when(bankAuditEventRepository.findForExport(from, to)).thenReturn(List.of(event));

    // When
    byte[] pdf = bankAuditReportService.generateAuditLogPdf(from, to, null);

    // Then — the raw event code + actor handle render; the export is itself audit-logged.
    String compact = extractText(pdf).replaceAll("\\s+", "");
    assertTrue(compact.contains("DEPOSIT_BOOKED"), "raw event code present");
    assertTrue(compact.contains("banker_jo"), "actor handle present");
    verify(bankAuditService)
        .record(eq(BankAuditEventType.AUDIT_LOG_EXPORTED), isNull(), isNull(), isNull(), any());
  }

  @Test
  void exportJson_mapsEventsAndRecordsExportEvent() {
    // Given
    Instant from = Instant.now().minus(1, ChronoUnit.HOURS);
    Instant to = Instant.now().plus(1, ChronoUnit.HOURS);
    BankAuditEvent event =
        BankAuditEvent.builder().eventType(BankAuditEventType.DEPOSIT_BOOKED).build();
    BankAuditEventDto dto =
        new BankAuditEventDto(
            UUID.randomUUID(),
            Instant.now(),
            "banker_jo",
            BankAuditEventType.DEPOSIT_BOOKED,
            null,
            null,
            null,
            null,
            "+100 aUEC",
            "basetool-frontend");
    when(bankAuditEventRepository.findForExport(from, to)).thenReturn(List.of(event));
    when(bankAuditEventMapper.toDto(event, null)).thenReturn(dto);

    // When
    List<BankAuditEventDto> result = bankAuditReportService.generateAuditLogJson(from, to);

    // Then
    assertEquals(List.of(dto), result);
    verify(bankAuditService)
        .record(eq(BankAuditEventType.AUDIT_LOG_EXPORTED), isNull(), isNull(), isNull(), any());
  }

  @Test
  void export_rejectsInvertedPeriod() {
    Instant from = Instant.now();
    Instant to = from.minus(1, ChronoUnit.HOURS);
    assertThrows(
        BadRequestException.class,
        () -> bankAuditReportService.generateAuditLogPdf(from, to, null));
    assertThrows(
        BadRequestException.class, () -> bankAuditReportService.generateAuditLogJson(from, to));
  }

  @Test
  void export_rejectsOversizedPeriod() {
    Instant from = Instant.now().minus(1, ChronoUnit.HOURS);
    Instant to = Instant.now().plus(1, ChronoUnit.HOURS);
    when(bankAuditEventRepository.countForExport(from, to)).thenReturn(100_001L);
    assertThrows(
        BadRequestException.class,
        () -> bankAuditReportService.generateAuditLogPdf(from, to, null));
    assertThrows(
        BadRequestException.class, () -> bankAuditReportService.generateAuditLogJson(from, to));
  }

  @Test
  void export_acceptsExactlyTheCapRowCount() {
    // The guard is count > MAX_EXPORT_ROWS, so exactly the cap (100_000) must be accepted.
    lenient()
        .when(messageSource.getMessage(any(String.class), isNull(), eq(Locale.GERMAN)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    Instant from = Instant.now().minus(1, ChronoUnit.HOURS);
    Instant to = Instant.now().plus(1, ChronoUnit.HOURS);
    when(bankAuditEventRepository.countForExport(from, to)).thenReturn(100_000L);
    when(bankAuditEventRepository.findForExport(from, to)).thenReturn(List.of());

    byte[] pdf = bankAuditReportService.generateAuditLogPdf(from, to, null);
    assertNotNull(pdf);
    verify(bankAuditService)
        .record(eq(BankAuditEventType.AUDIT_LOG_EXPORTED), isNull(), isNull(), isNull(), any());
  }

  private static String extractText(byte[] pdf) throws IOException {
    PdfReader reader = new PdfReader(pdf);
    try {
      StringBuilder text = new StringBuilder();
      PdfTextExtractor extractor = new PdfTextExtractor(reader);
      for (int page = 1; page <= reader.getNumberOfPages(); page++) {
        text.append(extractor.getTextFromPage(page)).append('\n');
      }
      return text.toString();
    } finally {
      reader.close();
    }
  }
}
