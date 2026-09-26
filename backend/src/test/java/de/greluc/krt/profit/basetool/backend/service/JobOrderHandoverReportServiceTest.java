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
import org.springframework.context.MessageSource;

@ExtendWith(MockitoExtension.class)
class JobOrderHandoverReportServiceTest {

  @Mock private JobOrderHandoverRepository jobOrderHandoverRepository;
  @Mock private MessageSource messageSource;

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

  @Test
  void generateHandoverReport_shouldReturnNonEmptyPdf_whenHandoverExists() {
    Material material = buildMaterial("Laranite", QuantityType.SCU);
    JobOrderHandoverItem item = buildItem(material, 5.0, 100, "Port Olisar");
    handover.setItems(Set.of(item));

    when(jobOrderHandoverRepository.findById(handoverId)).thenReturn(Optional.of(handover));

    byte[] pdf = service.generateHandoverReport(jobOrderId, handoverId, null);

    assertNotNull(pdf, "PDF must not be null");
    assertTrue(pdf.length > 0, "PDF must not be empty");
  }

  @Test
  void generateHandoverReport_shouldThrowNotFound_whenHandoverDoesNotExist() {
    when(jobOrderHandoverRepository.findById(handoverId)).thenReturn(Optional.empty());

    NotFoundException ex =
        assertThrows(
            NotFoundException.class,
            () -> service.generateHandoverReport(jobOrderId, handoverId, null));
  }

  @Test
  void generateHandoverReport_shouldThrowNotFound_whenHandoverBelongsToDifferentJobOrder() {
    UUID otherJobOrderId = UUID.randomUUID();
    JobOrder otherJobOrder = new JobOrder();
    otherJobOrder.setId(otherJobOrderId);
    otherJobOrder.setDisplayId(99);
    handover.setJobOrder(otherJobOrder);

    when(jobOrderHandoverRepository.findById(handoverId)).thenReturn(Optional.of(handover));

    NotFoundException ex =
        assertThrows(
            NotFoundException.class,
            () -> service.generateHandoverReport(jobOrderId, handoverId, null));
  }

  @Test
  void generateHandoverReport_shouldSortMaterialsCorrectly() {
    Material laranite = buildMaterial("Laranite", QuantityType.SCU);
    Material agricium = buildMaterial("Agricium", QuantityType.SCU);

    JobOrderHandoverItem item1 = buildItem(laranite, 3.0, 80, "Port Olisar");
    JobOrderHandoverItem item2 = buildItem(laranite, 1.0, 100, "Lorville");
    JobOrderHandoverItem item3 = buildItem(laranite, 5.0, 100, "Lorville");
    JobOrderHandoverItem item4 = buildItem(agricium, 2.0, 90, "ArcCorp");

    handover.setItems(Set.of(item1, item2, item3, item4));
    when(jobOrderHandoverRepository.findById(handoverId)).thenReturn(Optional.of(handover));

    byte[] pdf = service.generateHandoverReport(jobOrderId, handoverId, null);

    assertNotNull(pdf);
    assertTrue(pdf.length > 0);
  }

  @Test
  void generateHandoverReport_shouldNotContainPreviousOwnerName() {
    Material material = buildMaterial("Titanium", QuantityType.SCU);
    JobOrderHandoverItem item = buildItem(material, 10.0, 75, "New Babbage");
    handover.setItems(Set.of(item));
    handover.setRecipientHandle("RecipientOnly");

    when(jobOrderHandoverRepository.findById(handoverId)).thenReturn(Optional.of(handover));

    byte[] pdf = service.generateHandoverReport(jobOrderId, handoverId, null);

    assertNotNull(pdf);
    String pdfContent = new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1);
    assertTrue(pdfContent.contains("RecipientOnly"), "Recipient handle must be present in PDF");
    assertFalse(pdfContent.contains("BESITZER"), "Previous owner must NOT appear in PDF");
    assertFalse(pdfContent.contains("OWNER"), "Previous owner must NOT appear in PDF");
  }

  @Test
  void generateHandoverReportPreview_shouldReturnNonEmptyPdf() {
    HandoverReportPreviewRequestDto dto =
        new HandoverReportPreviewRequestDto(
            "#42",
            LocalDateTime.parse("2025-06-15T10:30:00"),
            "PreviewPilot",
            List.of(
                new HandoverReportPreviewRequestDto.HandoverReportItemDto(
                    "Laranite", "Port Olisar", 3.5, 90, "SCU")));

    byte[] pdf = service.generateHandoverReportPreview(dto);

    assertNotNull(pdf, "Preview PDF must not be null");
    assertTrue(pdf.length > 0, "Preview PDF must not be empty");
  }

  @Test
  void generateHandoverReportPreview_shouldSortItemsAlphabeticallyThenQualityDescThenAmountDesc() {
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

    byte[] pdf = service.generateHandoverReportPreview(dto);

    assertNotNull(pdf);
    assertTrue(pdf.length > 0);
    String pdfContent = new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1);
    assertTrue(pdfContent.contains("Agricium"));
    assertTrue(pdfContent.contains("Laranite"));
    assertTrue(pdfContent.contains("Titanium"));
  }

  @Test
  void generateHandoverReportPreview_shouldHandleEmptyItemList() {
    HandoverReportPreviewRequestDto dto =
        new HandoverReportPreviewRequestDto("#0", LocalDateTime.now(), "EmptyPilot", List.of());

    byte[] pdf = service.generateHandoverReportPreview(dto);

    assertNotNull(pdf);
    assertTrue(pdf.length > 0);
  }

  @Test
  void generateHandoverReportPreview_shouldNotContainOwnerField() {
    HandoverReportPreviewRequestDto dto =
        new HandoverReportPreviewRequestDto(
            "#5",
            LocalDateTime.now(),
            "RecipientHandle",
            List.of(
                new HandoverReportPreviewRequestDto.HandoverReportItemDto(
                    "Copper", "Lorville", 10.0, 60, "SCU")));

    byte[] pdf = service.generateHandoverReportPreview(dto);

    assertNotNull(pdf);
    String pdfContent = new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1);
    assertFalse(pdfContent.contains("BESITZER"), "Owner field must NOT appear in preview PDF");
    assertFalse(pdfContent.contains("OWNER"), "Owner field must NOT appear in preview PDF");
  }

  @Test
  void generateHandoverReport_pdfContainsTitleAndMetaLabels() throws IOException {
    Material material = buildMaterial("Laranite", QuantityType.SCU);
    JobOrderHandoverItem item = buildItem(material, 5.0, 100, "Port Olisar");
    handover.setItems(Set.of(item));
    when(jobOrderHandoverRepository.findById(handoverId)).thenReturn(Optional.of(handover));

    String text = extractAllText(service.generateHandoverReport(jobOrderId, handoverId, null));

    assertTrue(text.contains("ÜBERGABEPROTOKOLL"), "Title must appear in PDF");
    assertTrue(text.contains("AUFTRAGSNUMMER"));
    assertTrue(text.contains("DATUM DER ÜBERGABE"));
    assertTrue(text.contains("UHRZEIT DER ÜBERGABE"));
    assertTrue(text.contains("EMPFÄNGER (HANDLE)"));
  }

  @Test
  void generateHandoverReport_pdfContainsMetaValues() throws IOException {
    Material material = buildMaterial("Laranite", QuantityType.SCU);
    JobOrderHandoverItem item = buildItem(material, 5.0, 100, "Port Olisar");
    handover.setItems(Set.of(item));
    when(jobOrderHandoverRepository.findById(handoverId)).thenReturn(Optional.of(handover));

    String text = extractAllText(service.generateHandoverReport(jobOrderId, handoverId, null));

    assertTrue(text.contains("#42"), "Job order number '#42' must appear");
    assertTrue(text.contains("15.06.2025"), "Handover date must appear in dd.MM.yyyy");
    assertTrue(text.contains("10:30"), "Handover time must appear in HH:mm");
    assertTrue(text.contains("(Lokalzeit)"), "Local-time annotation must appear");
    assertTrue(text.contains("TestPilot"), "Recipient handle must appear");
  }

  @Test
  void generateHandoverReport_pdfContainsMaterialSectionAndColumnHeaders() throws IOException {
    Material material = buildMaterial("Laranite", QuantityType.SCU);
    JobOrderHandoverItem item = buildItem(material, 5.0, 100, "Port Olisar");
    handover.setItems(Set.of(item));
    when(jobOrderHandoverRepository.findById(handoverId)).thenReturn(Optional.of(handover));

    String text = extractAllText(service.generateHandoverReport(jobOrderId, handoverId, null));

    assertTrue(text.contains("ÜBERGEBENE MATERIALIEN"), "Section header must appear");
    assertTrue(text.contains("MATERIAL"));
    assertTrue(text.contains("STANDORT"));
    assertTrue(text.contains("MENGE"));
    assertTrue(text.contains("QUALITÄT"));
  }

  @Test
  void generateHandoverReport_pdfContainsItemRowData() throws IOException {
    Material material = buildMaterial("Laranite", QuantityType.SCU);
    JobOrderHandoverItem item = buildItem(material, 5.0, 100, "Port Olisar");
    handover.setItems(Set.of(item));
    when(jobOrderHandoverRepository.findById(handoverId)).thenReturn(Optional.of(handover));

    String text = extractAllText(service.generateHandoverReport(jobOrderId, handoverId, null));

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
    Material material = buildMaterial("Laranite", QuantityType.SCU);
    JobOrderHandoverItem item = buildItem(material, 5.0, 100, "Port Olisar");
    handover.setItems(Set.of(item));
    when(jobOrderHandoverRepository.findById(handoverId)).thenReturn(Optional.of(handover));

    String text = extractAllText(service.generateHandoverReport(jobOrderId, handoverId, null));

    assertTrue(
        text.matches(
            "(?s).*Generiert von Profit Basetool am \\d{2}\\.\\d{2}\\.\\d{4} \\d{2}:\\d{2} UTC.*"),
        "Footer must show the generation timestamp in UTC");
  }

  @Test
  void generateHandoverReport_emptyItemList_showsKeineMaterialienText() throws IOException {
    handover.setItems(Set.of());
    when(jobOrderHandoverRepository.findById(handoverId)).thenReturn(Optional.of(handover));

    String text = extractAllText(service.generateHandoverReport(jobOrderId, handoverId, null));

    assertTrue(
        text.contains("Keine Materialien vorhanden."),
        "Empty-state text must appear when no items");
  }

  @Test
  void generateHandoverReport_userZoneShiftsHandoverTime() throws IOException {
    Material material = buildMaterial("Laranite", QuantityType.SCU);
    JobOrderHandoverItem item = buildItem(material, 5.0, 100, "Port Olisar");
    handover.setItems(Set.of(item));
    when(jobOrderHandoverRepository.findById(handoverId)).thenReturn(Optional.of(handover));

    String text =
        extractAllText(
            service.generateHandoverReport(jobOrderId, handoverId, ZoneId.of("Europe/Berlin")));

    assertTrue(
        text.contains("12:30 (Lokalzeit)"), "Time must be shifted to Europe/Berlin CEST (12:30)");
    assertFalse(
        text.contains("10:30 (Lokalzeit)"),
        "UTC 10:30 must NOT appear as the handover time when zone is Europe/Berlin");
  }

  @Test
  void generateHandoverReport_pieceQuantityType_rendersAsIntegerNotScuFormat() throws IOException {
    Material material = buildMaterial("MedPen", QuantityType.PIECE);
    JobOrderHandoverItem item = buildItem(material, 25.0, 100, "Lorville");
    handover.setItems(Set.of(item));
    when(jobOrderHandoverRepository.findById(handoverId)).thenReturn(Optional.of(handover));

    String text = extractAllText(service.generateHandoverReport(jobOrderId, handoverId, null));

    assertTrue(text.contains("25"), "PIECE amount must render as integer '25'");
    String scuFormatted = String.format("%.3f SCU", 25.0);
    assertFalse(text.contains(scuFormatted), "PIECE amount must NOT be formatted as SCU");
  }

  @Test
  void generateHandoverReport_itemsAppearInTextInSortedOrder() throws IOException {
    Material laranite = buildMaterial("Laranite", QuantityType.SCU);
    Material agricium = buildMaterial("Agricium", QuantityType.SCU);

    JobOrderHandoverItem laraniteLowQ = buildItem(laranite, 3.0, 80, "LocLowQ");
    JobOrderHandoverItem laraniteHighQLowA = buildItem(laranite, 1.0, 100, "LocHighQLowA");
    JobOrderHandoverItem laraniteHighQHighA = buildItem(laranite, 5.0, 100, "LocHighQHighA");
    JobOrderHandoverItem agriciumItem = buildItem(agricium, 2.0, 90, "LocAgricium");

    handover.setItems(Set.of(laraniteLowQ, laraniteHighQLowA, laraniteHighQHighA, agriciumItem));
    when(jobOrderHandoverRepository.findById(handoverId)).thenReturn(Optional.of(handover));

    String text = extractAllText(service.generateHandoverReport(jobOrderId, handoverId, null));

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
    Material material = buildMaterial("Laranite", QuantityType.SCU);
    JobOrderHandoverItem item = buildItem(material, 5.0, 100, "Port Olisar");
    handover.setItems(Set.of(item));
    when(jobOrderHandoverRepository.findById(handoverId)).thenReturn(Optional.of(handover));

    byte[] pdf = service.generateHandoverReport(jobOrderId, handoverId, null);

    assertEquals(1, countPages(pdf), "Small handover content must fit on a single page");
  }

  @Test
  void generateHandoverReportPreview_pdfContainsTitleMetaItemDataAndFooter() throws IOException {
    HandoverReportPreviewRequestDto dto =
        new HandoverReportPreviewRequestDto(
            "#777",
            LocalDateTime.parse("2025-12-01T14:45:00"),
            "PreviewPilot",
            List.of(
                new HandoverReportPreviewRequestDto.HandoverReportItemDto(
                    "Laranite", "Port Olisar", 3.5, 90, "SCU")));

    String text = extractAllText(service.generateHandoverReportPreview(dto));

    assertTrue(text.contains("ÜBERGABEPROTOKOLL"));
    assertTrue(text.contains("#777"));
    assertTrue(text.contains("01.12.2025"));
    assertTrue(text.contains("14:45"));
    assertTrue(text.contains("PreviewPilot"));
    assertTrue(text.contains("Laranite"));
    assertTrue(text.contains("Port Olisar"));
    assertTrue(text.contains(String.format("%.3f SCU", 3.5)));
    assertTrue(text.contains("90"));
    assertTrue(text.contains("Generiert von Profit Basetool"));
  }

  @Test
  void generateHandoverReportPreview_localDateTimeRenderedWithoutZoneConversion()
      throws IOException {
    HandoverReportPreviewRequestDto dto =
        new HandoverReportPreviewRequestDto(
            "#1",
            LocalDateTime.parse("2025-06-15T14:45:00"),
            "Pilot",
            List.of(
                new HandoverReportPreviewRequestDto.HandoverReportItemDto(
                    "X", "Y", 1.0, 50, "SCU")));

    String text = extractAllText(service.generateHandoverReportPreview(dto));

    assertTrue(
        text.contains("14:45"),
        "Time must render as entered (no zone conversion in preview branch)");
    assertTrue(text.contains("15.06.2025"));
  }

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
