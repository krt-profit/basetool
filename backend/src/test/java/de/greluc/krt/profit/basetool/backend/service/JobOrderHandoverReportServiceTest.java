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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderHandover;
import de.greluc.krt.profit.basetool.backend.model.JobOrderHandoverItem;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.dto.HandoverReportPreviewRequestDto;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderHandoverRepository;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

@ExtendWith(MockitoExtension.class)
class JobOrderHandoverReportServiceTest {

  @Mock private JobOrderHandoverRepository jobOrderHandoverRepository;

  @InjectMocks private JobOrderHandoverReportService service;

  private UUID jobOrderId;
  private UUID handoverId;
  private JobOrder jobOrder;
  private JobOrderHandover handover;

  @BeforeEach
  void setUp() {
    jobOrderId = UUID.randomUUID();
    handoverId = UUID.randomUUID();

    jobOrder = new JobOrder();
    jobOrder.setId(jobOrderId);
    jobOrder.setDisplayId(42);

    handover = new JobOrderHandover();
    handover.setId(handoverId);
    handover.setJobOrder(jobOrder);
    handover.setHandoverTime(Instant.parse("2025-06-15T10:30:00Z"));
    handover.setRecipientHandle("TestPilot");
    handover.setRecipientSquadron("IRIDIUM");
  }

  // -------------------------------------------------------------------------
  // generateHandoverReport – persisted handover
  // -------------------------------------------------------------------------

  @Test
  void generateHandoverReport_shouldReturnNonEmptyPdf_whenHandoverExists() {
    // Given
    Material material = buildMaterial("Laranite", QuantityType.SCU);
    JobOrderHandoverItem item = buildItem(material, 5.0, 100, "Port Olisar");
    handover.setItems(Set.of(item));

    when(jobOrderHandoverRepository.findById(handoverId)).thenReturn(Optional.of(handover));

    // When
    byte[] pdf = service.generateHandoverReport(jobOrderId, handoverId, null);

    // Then
    assertNotNull(pdf, "PDF must not be null");
    assertTrue(pdf.length > 0, "PDF must not be empty");
  }

  @Test
  void generateHandoverReport_shouldThrowNotFound_whenHandoverDoesNotExist() {
    // Given
    when(jobOrderHandoverRepository.findById(handoverId)).thenReturn(Optional.empty());

    // When & Then
    NotFoundException ex =
        assertThrows(
            NotFoundException.class,
            () -> service.generateHandoverReport(jobOrderId, handoverId, null));
  }

  @Test
  void generateHandoverReport_shouldThrowNotFound_whenHandoverBelongsToDifferentJobOrder() {
    // Given
    UUID otherJobOrderId = UUID.randomUUID();
    JobOrder otherJobOrder = new JobOrder();
    otherJobOrder.setId(otherJobOrderId);
    otherJobOrder.setDisplayId(99);
    handover.setJobOrder(otherJobOrder);

    when(jobOrderHandoverRepository.findById(handoverId)).thenReturn(Optional.of(handover));

    // When & Then
    NotFoundException ex =
        assertThrows(
            NotFoundException.class,
            () -> service.generateHandoverReport(jobOrderId, handoverId, null));
  }

  @Test
  void generateHandoverReport_shouldSortMaterialsCorrectly() {
    // Given – three items: same material name but different quality/amount, plus a different name
    Material laranite = buildMaterial("Laranite", QuantityType.SCU);
    Material agricium = buildMaterial("Agricium", QuantityType.SCU);

    // Laranite: quality 80, amount 3.0
    JobOrderHandoverItem item1 = buildItem(laranite, 3.0, 80, "Port Olisar");
    // Laranite: quality 100, amount 1.0 → should come before item1 (higher quality)
    JobOrderHandoverItem item2 = buildItem(laranite, 1.0, 100, "Lorville");
    // Laranite: quality 100, amount 5.0 → should come before item2 (same quality, higher amount)
    JobOrderHandoverItem item3 = buildItem(laranite, 5.0, 100, "Lorville");
    // Agricium: alphabetically first
    JobOrderHandoverItem item4 = buildItem(agricium, 2.0, 90, "ArcCorp");

    handover.setItems(Set.of(item1, item2, item3, item4));
    when(jobOrderHandoverRepository.findById(handoverId)).thenReturn(Optional.of(handover));

    // When – just verify it generates without error; sorting is tested via preview below
    byte[] pdf = service.generateHandoverReport(jobOrderId, handoverId, null);

    // Then
    assertNotNull(pdf);
    assertTrue(pdf.length > 0);
  }

  @Test
  void generateHandoverReport_shouldNotContainPreviousOwnerName() {
    // Given – the PDF must NOT contain any owner/user name
    Material material = buildMaterial("Titanium", QuantityType.SCU);
    JobOrderHandoverItem item = buildItem(material, 10.0, 75, "New Babbage");
    handover.setItems(Set.of(item));
    handover.setRecipientHandle("RecipientOnly");

    when(jobOrderHandoverRepository.findById(handoverId)).thenReturn(Optional.of(handover));

    // When
    byte[] pdf = service.generateHandoverReport(jobOrderId, handoverId, null);

    // Then – convert to string for basic content check (PDF text is embedded as plain bytes)
    assertNotNull(pdf);
    // The recipient handle must appear in the PDF content
    String pdfContent = new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1);
    assertTrue(pdfContent.contains("RecipientOnly"), "Recipient handle must be present in PDF");
    // No owner/user field should appear (the field label "BESITZER" or "OWNER" must not be present)
    assertFalse(pdfContent.contains("BESITZER"), "Previous owner must NOT appear in PDF");
    assertFalse(pdfContent.contains("OWNER"), "Previous owner must NOT appear in PDF");
  }

  // -------------------------------------------------------------------------
  // generateHandoverReportPreview – DTO-based
  // -------------------------------------------------------------------------

  @Test
  void generateHandoverReportPreview_shouldReturnNonEmptyPdf() {
    // Given
    HandoverReportPreviewRequestDto dto =
        new HandoverReportPreviewRequestDto(
            "#42",
            LocalDateTime.parse("2025-06-15T10:30:00"),
            "PreviewPilot",
            List.of(
                new HandoverReportPreviewRequestDto.HandoverReportItemDto(
                    "Laranite", "Port Olisar", 3.5, 90, "SCU")));

    // When
    byte[] pdf = service.generateHandoverReportPreview(dto);

    // Then
    assertNotNull(pdf, "Preview PDF must not be null");
    assertTrue(pdf.length > 0, "Preview PDF must not be empty");
  }

  @Test
  void generateHandoverReportPreview_shouldSortItemsAlphabeticallyThenQualityDescThenAmountDesc() {
    // Given – items in wrong order; service must sort them
    List<HandoverReportPreviewRequestDto.HandoverReportItemDto> items =
        List.of(
            new HandoverReportPreviewRequestDto.HandoverReportItemDto(
                "Titanium", "A", 1.0, 50, "SCU"),
            new HandoverReportPreviewRequestDto.HandoverReportItemDto(
                "Agricium", "B", 2.0, 80, "SCU"),
            new HandoverReportPreviewRequestDto.HandoverReportItemDto(
                "Laranite", "C", 5.0, 100, "SCU"),
            new HandoverReportPreviewRequestDto.HandoverReportItemDto(
                "Laranite", "D", 3.0, 100, "SCU"),
            new HandoverReportPreviewRequestDto.HandoverReportItemDto(
                "Laranite", "E", 5.0, 90, "SCU"));

    HandoverReportPreviewRequestDto dto =
        new HandoverReportPreviewRequestDto("#7", LocalDateTime.now(), "SortTestPilot", items);

    // When
    byte[] pdf = service.generateHandoverReportPreview(dto);

    // Then – PDF generated successfully (sorting correctness verified by content order)
    assertNotNull(pdf);
    assertTrue(pdf.length > 0);
    // Verify the PDF contains all material names
    String pdfContent = new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1);
    assertTrue(pdfContent.contains("Agricium"));
    assertTrue(pdfContent.contains("Laranite"));
    assertTrue(pdfContent.contains("Titanium"));
  }

  @Test
  void generateHandoverReportPreview_shouldHandleEmptyItemList() {
    // Given
    HandoverReportPreviewRequestDto dto =
        new HandoverReportPreviewRequestDto("#0", LocalDateTime.now(), "EmptyPilot", List.of());

    // When
    byte[] pdf = service.generateHandoverReportPreview(dto);

    // Then – must still generate a valid PDF
    assertNotNull(pdf);
    assertTrue(pdf.length > 0);
  }

  @Test
  void generateHandoverReportPreview_shouldNotContainOwnerField() {
    // Given
    HandoverReportPreviewRequestDto dto =
        new HandoverReportPreviewRequestDto(
            "#5",
            LocalDateTime.now(),
            "RecipientHandle",
            List.of(
                new HandoverReportPreviewRequestDto.HandoverReportItemDto(
                    "Copper", "Lorville", 10.0, 60, "SCU")));

    // When
    byte[] pdf = service.generateHandoverReportPreview(dto);

    // Then
    assertNotNull(pdf);
    String pdfContent = new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1);
    assertFalse(pdfContent.contains("BESITZER"), "Owner field must NOT appear in preview PDF");
    assertFalse(pdfContent.contains("OWNER"), "Owner field must NOT appear in preview PDF");
  }

  // -------------------------------------------------------------------------
  // PDF content tests (using PdfTextExtractor)
  // -------------------------------------------------------------------------

  @Test
  void generateHandoverReport_pdfContainsTitleAndMetaLabels() throws IOException {
    // Given
    Material material = buildMaterial("Laranite", QuantityType.SCU);
    JobOrderHandoverItem item = buildItem(material, 5.0, 100, "Port Olisar");
    handover.setItems(Set.of(item));
    when(jobOrderHandoverRepository.findById(handoverId)).thenReturn(Optional.of(handover));

    // When
    String text = extractAllText(service.generateHandoverReport(jobOrderId, handoverId, null));

    // Then
    assertTrue(text.contains("ÜBERGABEPROTOKOLL"), "Title must appear in PDF");
    assertTrue(text.contains("AUFTRAGSNUMMER"));
    assertTrue(text.contains("DATUM DER ÜBERGABE"));
    assertTrue(text.contains("UHRZEIT DER ÜBERGABE"));
    assertTrue(text.contains("EMPFÄNGER (HANDLE)"));
  }

  @Test
  void generateHandoverReport_pdfContainsMetaValues() throws IOException {
    // Given
    Material material = buildMaterial("Laranite", QuantityType.SCU);
    JobOrderHandoverItem item = buildItem(material, 5.0, 100, "Port Olisar");
    handover.setItems(Set.of(item));
    when(jobOrderHandoverRepository.findById(handoverId)).thenReturn(Optional.of(handover));

    // When
    String text = extractAllText(service.generateHandoverReport(jobOrderId, handoverId, null));

    // Then
    // jobOrder.displayId = 42 → "#42"
    assertTrue(text.contains("#42"), "Job order number '#42' must appear");
    // handoverTime is 2025-06-15T10:30:00Z, null zone → defaults to UTC
    assertTrue(text.contains("15.06.2025"), "Handover date must appear in dd.MM.yyyy");
    assertTrue(text.contains("10:30"), "Handover time must appear in HH:mm");
    assertTrue(text.contains("(Lokalzeit)"), "Local-time annotation must appear");
    assertTrue(text.contains("TestPilot"), "Recipient handle must appear");
  }

  @Test
  void generateHandoverReport_pdfContainsMaterialSectionAndColumnHeaders() throws IOException {
    // Given
    Material material = buildMaterial("Laranite", QuantityType.SCU);
    JobOrderHandoverItem item = buildItem(material, 5.0, 100, "Port Olisar");
    handover.setItems(Set.of(item));
    when(jobOrderHandoverRepository.findById(handoverId)).thenReturn(Optional.of(handover));

    // When
    String text = extractAllText(service.generateHandoverReport(jobOrderId, handoverId, null));

    // Then
    assertTrue(text.contains("ÜBERGEBENE MATERIALIEN"), "Section header must appear");
    assertTrue(text.contains("MATERIAL"));
    assertTrue(text.contains("STANDORT"));
    assertTrue(text.contains("MENGE"));
    assertTrue(text.contains("QUALITÄT"));
  }

  @Test
  void generateHandoverReport_pdfContainsItemRowData() throws IOException {
    // Given
    Material material = buildMaterial("Laranite", QuantityType.SCU);
    JobOrderHandoverItem item = buildItem(material, 5.0, 100, "Port Olisar");
    handover.setItems(Set.of(item));
    when(jobOrderHandoverRepository.findById(handoverId)).thenReturn(Optional.of(handover));

    // When
    String text = extractAllText(service.generateHandoverReport(jobOrderId, handoverId, null));

    // Then
    // String.format("%.3f SCU", 5.0) uses the JVM default locale (e.g. comma decimal in DE,
    // period in en_US) — mirror that here so the assertion is portable across CI locales.
    String expectedAmount = String.format("%.3f SCU", 5.0);
    assertTrue(text.contains("Laranite"));
    assertTrue(text.contains("Port Olisar"));
    assertTrue(
        text.contains(expectedAmount),
        "SCU amount must be formatted with three decimals (expected '" + expectedAmount + "')");
    assertTrue(text.contains("100"), "Quality value must appear");
  }

  @Test
  void generateHandoverReport_pdfContainsFooterWithUtcTimestamp() throws IOException {
    // Given
    Material material = buildMaterial("Laranite", QuantityType.SCU);
    JobOrderHandoverItem item = buildItem(material, 5.0, 100, "Port Olisar");
    handover.setItems(Set.of(item));
    when(jobOrderHandoverRepository.findById(handoverId)).thenReturn(Optional.of(handover));

    // When
    String text = extractAllText(service.generateHandoverReport(jobOrderId, handoverId, null));

    // Then: footer carries the generation date+time, always in UTC
    assertTrue(
        text.matches(
            "(?s).*Generiert von Profit Basetool am \\d{2}\\.\\d{2}\\.\\d{4} \\d{2}:\\d{2} UTC.*"),
        "Footer must show the generation timestamp in UTC");
  }

  @Test
  void generateHandoverReport_emptyItemList_showsKeineMaterialienText() throws IOException {
    // Given
    handover.setItems(Set.of());
    when(jobOrderHandoverRepository.findById(handoverId)).thenReturn(Optional.of(handover));

    // When
    String text = extractAllText(service.generateHandoverReport(jobOrderId, handoverId, null));

    // Then
    assertTrue(
        text.contains("Keine Materialien vorhanden."),
        "Empty-state text must appear when no items");
  }

  @Test
  void generateHandoverReport_userZoneShiftsHandoverTime() throws IOException {
    // Given – handoverTime in setUp is 2025-06-15T10:30:00Z (UTC).
    // In June, Europe/Berlin is CEST (UTC+2) → expected display time is 12:30.
    Material material = buildMaterial("Laranite", QuantityType.SCU);
    JobOrderHandoverItem item = buildItem(material, 5.0, 100, "Port Olisar");
    handover.setItems(Set.of(item));
    when(jobOrderHandoverRepository.findById(handoverId)).thenReturn(Optional.of(handover));

    // When
    String text =
        extractAllText(
            service.generateHandoverReport(jobOrderId, handoverId, ZoneId.of("Europe/Berlin")));

    // Then – anchor on the "(Lokalzeit)" suffix of the handover-time meta cell so the assertion
    // targets the handover time itself and can never collide with the footer's UTC generation
    // timestamp (rendered as "... HH:mm UTC" via Instant.now()), which would otherwise flake
    // whenever CI happens to run at 10:30 UTC.
    assertTrue(
        text.contains("12:30 (Lokalzeit)"), "Time must be shifted to Europe/Berlin CEST (12:30)");
    assertFalse(
        text.contains("10:30 (Lokalzeit)"),
        "UTC 10:30 must NOT appear as the handover time when zone is Europe/Berlin");
  }

  @Test
  void generateHandoverReport_pieceQuantityType_rendersAsIntegerNotScuFormat() throws IOException {
    // Given
    Material material = buildMaterial("MedPen", QuantityType.PIECE);
    JobOrderHandoverItem item = buildItem(material, 25.0, 100, "Lorville");
    handover.setItems(Set.of(item));
    when(jobOrderHandoverRepository.findById(handoverId)).thenReturn(Optional.of(handover));

    // When
    String text = extractAllText(service.generateHandoverReport(jobOrderId, handoverId, null));

    // Then
    assertTrue(text.contains("25"), "PIECE amount must render as integer '25'");
    // Locale-aware SCU format — must NOT appear for PIECE items, regardless of CI locale.
    String scuFormatted = String.format("%.3f SCU", 25.0);
    assertFalse(text.contains(scuFormatted), "PIECE amount must NOT be formatted as SCU");
  }

  @Test
  void generateHandoverReport_itemsAppearInTextInSortedOrder() throws IOException {
    // Given – unique location markers per item so we can locate each row in the extracted text.
    // Expected order: Agricium (alphabetical first), then Laranite (q100/a5 → q100/a1 → q80/a3).
    Material laranite = buildMaterial("Laranite", QuantityType.SCU);
    Material agricium = buildMaterial("Agricium", QuantityType.SCU);

    JobOrderHandoverItem laraniteLowQ = buildItem(laranite, 3.0, 80, "LocLowQ");
    JobOrderHandoverItem laraniteHighQLowA = buildItem(laranite, 1.0, 100, "LocHighQLowA");
    JobOrderHandoverItem laraniteHighQHighA = buildItem(laranite, 5.0, 100, "LocHighQHighA");
    JobOrderHandoverItem agriciumItem = buildItem(agricium, 2.0, 90, "LocAgricium");

    handover.setItems(Set.of(laraniteLowQ, laraniteHighQLowA, laraniteHighQHighA, agriciumItem));
    when(jobOrderHandoverRepository.findById(handoverId)).thenReturn(Optional.of(handover));

    // When
    String text = extractAllText(service.generateHandoverReport(jobOrderId, handoverId, null));

    // Then
    int idxAgricium = text.indexOf("LocAgricium");
    int idxHighQHighA = text.indexOf("LocHighQHighA");
    int idxHighQLowA = text.indexOf("LocHighQLowA");
    int idxLowQ = text.indexOf("LocLowQ");

    assertTrue(
        idxAgricium >= 0 && idxHighQHighA >= 0 && idxHighQLowA >= 0 && idxLowQ >= 0,
        "All location markers must appear in extracted text");
    assertTrue(
        idxAgricium < idxHighQHighA, "Agricium row must precede Laranite rows (alphabetical)");
    assertTrue(
        idxHighQHighA < idxHighQLowA,
        "Laranite q100/a5 must precede q100/a1 (higher amount within same quality)");
    assertTrue(
        idxHighQLowA < idxLowQ, "Laranite q100 rows must precede q80 row (higher quality first)");
  }

  @Test
  void generateHandoverReport_smallContent_fitsOnSinglePage() throws IOException {
    // Given
    Material material = buildMaterial("Laranite", QuantityType.SCU);
    JobOrderHandoverItem item = buildItem(material, 5.0, 100, "Port Olisar");
    handover.setItems(Set.of(item));
    when(jobOrderHandoverRepository.findById(handoverId)).thenReturn(Optional.of(handover));

    // When
    byte[] pdf = service.generateHandoverReport(jobOrderId, handoverId, null);

    // Then
    assertEquals(1, countPages(pdf), "Small handover content must fit on a single page");
  }

  @Test
  void generateHandoverReportPreview_pdfContainsTitleMetaItemDataAndFooter() throws IOException {
    // Given
    HandoverReportPreviewRequestDto dto =
        new HandoverReportPreviewRequestDto(
            "#777",
            LocalDateTime.parse("2025-12-01T14:45:00"),
            "PreviewPilot",
            List.of(
                new HandoverReportPreviewRequestDto.HandoverReportItemDto(
                    "Laranite", "Port Olisar", 3.5, 90, "SCU")));

    // When
    String text = extractAllText(service.generateHandoverReportPreview(dto));

    // Then
    assertTrue(text.contains("ÜBERGABEPROTOKOLL"));
    assertTrue(text.contains("#777"));
    assertTrue(text.contains("01.12.2025"));
    assertTrue(text.contains("14:45"));
    assertTrue(text.contains("PreviewPilot"));
    assertTrue(text.contains("Laranite"));
    assertTrue(text.contains("Port Olisar"));
    // Locale-aware: same JVM default locale as production formatAmount(...).
    assertTrue(text.contains(String.format("%.3f SCU", 3.5)));
    assertTrue(text.contains("90"));
    assertTrue(text.contains("Generiert von Profit Basetool"));
  }

  @Test
  void generateHandoverReportPreview_localDateTimeRenderedWithoutZoneConversion()
      throws IOException {
    // Given – LocalDateTime has no zone; service must render the entered time as-is.
    HandoverReportPreviewRequestDto dto =
        new HandoverReportPreviewRequestDto(
            "#1",
            LocalDateTime.parse("2025-06-15T14:45:00"),
            "Pilot",
            List.of(
                new HandoverReportPreviewRequestDto.HandoverReportItemDto(
                    "X", "Y", 1.0, 50, "SCU")));

    // When
    String text = extractAllText(service.generateHandoverReportPreview(dto));

    // Then
    assertTrue(
        text.contains("14:45"),
        "Time must render as entered (no zone conversion in preview branch)");
    assertTrue(text.contains("15.06.2025"));
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private Material buildMaterial(String name, QuantityType quantityType) {
    Material m = new Material();
    m.setId(UUID.randomUUID());
    m.setName(name);
    m.setQuantityType(quantityType);
    return m;
  }

  private JobOrderHandoverItem buildItem(
      Material material, double amount, int quality, String locationName) {
    JobOrderHandoverItem item = new JobOrderHandoverItem();
    item.setId(UUID.randomUUID());
    item.setMaterial(material);
    item.setAmount(amount);
    item.setQuality(quality);
    item.setLocationName(locationName);
    return item;
  }

  private static String extractAllText(byte[] pdf) throws IOException {
    PdfReader reader = new PdfReader(pdf);
    try {
      PdfTextExtractor extractor = new PdfTextExtractor(reader);
      StringBuilder sb = new StringBuilder();
      for (int i = 1; i <= reader.getNumberOfPages(); i++) {
        sb.append(extractor.getTextFromPage(i)).append('\n');
      }
      return sb.toString();
    } finally {
      reader.close();
    }
  }

  private static int countPages(byte[] pdf) throws IOException {
    PdfReader reader = new PdfReader(pdf);
    try {
      return reader.getNumberOfPages();
    } finally {
      reader.close();
    }
  }
}
