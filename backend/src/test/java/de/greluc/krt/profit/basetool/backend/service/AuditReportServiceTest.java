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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.mapper.AuditEventMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditDomain;
import de.greluc.krt.profit.basetool.backend.model.AuditEvent;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.dto.AuditEventDto;
import de.greluc.krt.profit.basetool.backend.repository.AuditEventRepository;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;
import org.springframework.context.MessageSource;

/**
 * Unit tests for {@link AuditReportService}: the period PDF carries the area's events (raw event
 * code + subject label, asserted via {@code PdfTextExtractor}), each export records the matching
 * {@code *_AUDIT_EXPORTED} audit event, and an inverted period is rejected (REQ-AUDIT-003). The PDF
 * render is static (no Spring context needed); the message bundle is stubbed to return the key.
 */
@ExtendWith(MockitoExtension.class)
class AuditReportServiceTest {

  @Mock private AuditEventRepository auditEventRepository;
  @Mock private AuditService auditService;
  @Mock private AuditEventMapper auditEventMapper;
  @Mock private MessageSource messageSource;

  @InjectMocks private AuditReportService auditReportService;

  /**
   * The independent source of truth for the {@code AuditDomain -> *_AUDIT_EXPORTED} mapping, kept
   * separate from the production {@code AuditReportService.exportEventType} switch so a copy-pasted
   * or reused arm (e.g. {@code MISSION -> OPERATION_AUDIT_EXPORTED}) is caught. A future domain
   * added without an entry here surfaces as a missing key in {@link
   * #export_recordsDomainSpecificExportEventType(AuditDomain)}.
   */
  private static final Map<AuditDomain, AuditEventType> EXPECTED_EXPORT_TYPE =
      Map.ofEntries(
          Map.entry(AuditDomain.INVENTORY, AuditEventType.INVENTORY_AUDIT_EXPORTED),
          Map.entry(AuditDomain.JOB_ORDER, AuditEventType.JOB_ORDER_AUDIT_EXPORTED),
          Map.entry(AuditDomain.REFINERY, AuditEventType.REFINERY_AUDIT_EXPORTED),
          Map.entry(
              AuditDomain.PERSONAL_INVENTORY, AuditEventType.PERSONAL_INVENTORY_AUDIT_EXPORTED),
          Map.entry(AuditDomain.MISSION, AuditEventType.MISSION_AUDIT_EXPORTED),
          Map.entry(AuditDomain.OPERATION, AuditEventType.OPERATION_AUDIT_EXPORTED),
          Map.entry(AuditDomain.ROLE, AuditEventType.ROLE_AUDIT_EXPORTED),
          Map.entry(AuditDomain.PROMOTION, AuditEventType.PROMOTION_AUDIT_EXPORTED),
          Map.entry(AuditDomain.MARKET, AuditEventType.MARKET_AUDIT_EXPORTED));

  @Test
  void export_rendersEventsAndRecordsExportEvent() throws IOException {
    // Given
    lenient()
        .when(messageSource.getMessage(any(String.class), isNull(), eq(Locale.GERMAN)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    Instant from = Instant.now().minus(1, ChronoUnit.HOURS);
    Instant to = Instant.now().plus(1, ChronoUnit.HOURS);
    AuditEvent event =
        AuditEvent.builder()
            .occurredAt(Instant.now())
            .domain(AuditDomain.INVENTORY)
            .eventType(AuditEventType.INVENTORY_ITEM_CREATED)
            .actorHandle("logi_jo")
            .subjectLabel("Quantanium @ Port Olisar")
            .details("qty=5.0")
            .build();
    when(auditEventRepository.findForExport(AuditDomain.INVENTORY, from, to))
        .thenReturn(List.of(event));

    // When
    byte[] pdf = auditReportService.generateAuditLogPdf(AuditDomain.INVENTORY, from, to, null);

    // Then
    // Strip whitespace: a narrow table cell wraps a long token across lines in the rendered PDF,
    // so the raw extracted text breaks mid-word (e.g. "INVENTORY_ITEM_C\nREATED").
    String compact = extractText(pdf).replaceAll("\\s+", "");
    assertTrue(compact.contains("INVENTORY_ITEM_CREATED"), "raw event code present");
    assertTrue(compact.contains("logi_jo"), "actor handle present");
    assertTrue(compact.contains("Quantanium@PortOlisar"), "subject label present");
    verify(auditService)
        .record(eq(AuditEventType.INVENTORY_AUDIT_EXPORTED), isNull(), isNull(), isNull(), any());
  }

  @Test
  void exportJson_mapsEventsAndRecordsExportEvent() {
    // Given
    Instant from = Instant.now().minus(1, ChronoUnit.HOURS);
    Instant to = Instant.now().plus(1, ChronoUnit.HOURS);
    AuditEvent event =
        AuditEvent.builder()
            .domain(AuditDomain.JOB_ORDER)
            .eventType(AuditEventType.JOB_ORDER_CREATED)
            .build();
    AuditEventDto dto =
        new AuditEventDto(
            null,
            Instant.now(),
            AuditDomain.JOB_ORDER,
            AuditEventType.JOB_ORDER_CREATED,
            "logi_jo",
            null,
            "#7 'X'",
            null,
            "d");
    when(auditEventRepository.findForExport(AuditDomain.JOB_ORDER, from, to))
        .thenReturn(List.of(event));
    when(auditEventMapper.toDto(event)).thenReturn(dto);

    // When
    List<AuditEventDto> result =
        auditReportService.generateAuditLogJson(AuditDomain.JOB_ORDER, from, to);

    // Then
    org.junit.jupiter.api.Assertions.assertEquals(List.of(dto), result);
    verify(auditService)
        .record(eq(AuditEventType.JOB_ORDER_AUDIT_EXPORTED), isNull(), isNull(), isNull(), any());
  }

  @Test
  void export_rejectsInvertedPeriod() {
    Instant from = Instant.now();
    Instant to = from.minus(1, ChronoUnit.HOURS);
    assertThrows(
        BadRequestException.class,
        () -> auditReportService.generateAuditLogPdf(AuditDomain.REFINERY, from, to, null));
    assertThrows(
        BadRequestException.class,
        () -> auditReportService.generateAuditLogJson(AuditDomain.REFINERY, from, to));
  }

  @Test
  void export_rejectsOversizedPeriod() {
    // Given a period that would load more than the export cap (100k) into memory.
    Instant from = Instant.now().minus(1, ChronoUnit.HOURS);
    Instant to = Instant.now().plus(1, ChronoUnit.HOURS);

    // Then both formats reject it before the unpaged fetch (guards against OOM).
    when(auditEventRepository.countForExport(AuditDomain.INVENTORY, from, to)).thenReturn(100_001L);
    assertThrows(
        BadRequestException.class,
        () -> auditReportService.generateAuditLogPdf(AuditDomain.INVENTORY, from, to, null));

    when(auditEventRepository.countForExport(AuditDomain.JOB_ORDER, from, to)).thenReturn(100_001L);
    assertThrows(
        BadRequestException.class,
        () -> auditReportService.generateAuditLogJson(AuditDomain.JOB_ORDER, from, to));
  }

  @Test
  void export_acceptsExactlyTheCapRowCount() {
    // Boundary: the guard is count > MAX_EXPORT_ROWS, so EXACTLY the cap (100_000) must be
    // ACCEPTED.
    // A >→>= regression would reject it; this pins the inclusive edge.
    lenient()
        .when(messageSource.getMessage(any(String.class), isNull(), eq(Locale.GERMAN)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    Instant from = Instant.now().minus(1, ChronoUnit.HOURS);
    Instant to = Instant.now().plus(1, ChronoUnit.HOURS);
    when(auditEventRepository.countForExport(AuditDomain.REFINERY, from, to)).thenReturn(100_000L);
    when(auditEventRepository.findForExport(AuditDomain.REFINERY, from, to)).thenReturn(List.of());

    // Does not throw — it renders the (empty) document and records the export.
    byte[] pdf = auditReportService.generateAuditLogPdf(AuditDomain.REFINERY, from, to, null);
    org.junit.jupiter.api.Assertions.assertNotNull(pdf);
    verify(auditService)
        .record(eq(AuditEventType.REFINERY_AUDIT_EXPORTED), isNull(), isNull(), isNull(), any());
  }

  @ParameterizedTest
  @EnumSource(AuditDomain.class)
  void export_recordsDomainSpecificExportEventType(AuditDomain domain) {
    // Given an empty period so every arm renders without needing per-domain fixtures.
    lenient()
        .when(messageSource.getMessage(any(String.class), isNull(), eq(Locale.GERMAN)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    Instant from = Instant.now().minus(1, ChronoUnit.HOURS);
    Instant to = Instant.now().plus(1, ChronoUnit.HOURS);
    when(auditEventRepository.countForExport(domain, from, to)).thenReturn(0L);
    when(auditEventRepository.findForExport(domain, from, to)).thenReturn(List.of());

    // When
    auditReportService.generateAuditLogPdf(domain, from, to, null);

    // Then the export is recorded under this domain's own *_AUDIT_EXPORTED type — pins all 9 arms
    // and forces a new enum constant to gain both a production arm and a test mapping.
    AuditEventType expected = EXPECTED_EXPORT_TYPE.get(domain);
    org.junit.jupiter.api.Assertions.assertNotNull(
        expected, "no expected export event type mapped for domain " + domain);
    verify(auditService).record(eq(expected), isNull(), isNull(), isNull(), any());
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
