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

import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.JobOrderHandover;
import de.greluc.krt.profit.basetool.backend.model.dto.HandoverReportPreviewRequestDto;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderHandoverRepository;
import de.greluc.krt.profit.basetool.backend.service.pdf.KrtPdfSupport;
import de.greluc.krt.profit.basetool.backend.support.HandleAnonymisation;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.openpdf.text.pdf.PdfPTable;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service responsible for generating handover report PDFs in KRT Corporate Design. Supports both
 * persisted handovers (by ID) and preview generation from raw DTO data. The page background, the
 * embedded Lato fonts and every cell helper come from the shared {@link KrtPdfSupport} layer (epic
 * #556 Phase 3) — the rendered content is unchanged.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class JobOrderHandoverReportService {

  // Patterns are intentionally NOT bound to a fixed zone here. The persisted-handover path
  // binds the zone per request (from the X-User-Time-Zone header), and the preview path
  // formats LocalDateTime directly without any zone conversion.
  private static final DateTimeFormatter DATE_PATTERN = DateTimeFormatter.ofPattern("dd.MM.yyyy");
  private static final DateTimeFormatter TIME_PATTERN = DateTimeFormatter.ofPattern("HH:mm");

  private final JobOrderHandoverRepository jobOrderHandoverRepository;
  private final MessageSource messageSource;

  /**
   * Generates a handover report PDF for a persisted handover.
   *
   * <p>The persisted {@code handoverTime} is a UTC {@link java.time.Instant}; for display in the
   * PDF it must be rendered in the user's actual time zone (passed in via {@code userZone}, e.g.
   * from the {@code X-User-Time-Zone} request header). If {@code userZone} is {@code null}, UTC is
   * used as a safe fallback.
   *
   * @param jobOrderId the job order ID
   * @param handoverId the handover ID
   * @param userZone the time zone to render the handover time in; may be {@code null}
   * @return PDF as byte array
   */
  public byte @NotNull [] generateHandoverReport(
      @NotNull UUID jobOrderId, @NotNull UUID handoverId, ZoneId userZone) {
    log.debug(
        "Generating handover report for jobOrderId={}, handoverId={}, userZone={}",
        jobOrderId,
        handoverId,
        userZone);

    JobOrderHandover handover =
        Entities.require(jobOrderHandoverRepository.findById(handoverId), "Handover not found");

    if (!handover.getJobOrder().getId().equals(jobOrderId)) {
      throw new NotFoundException("Handover does not belong to this job order");
    }

    ZoneId effectiveZone = userZone != null ? userZone : ZoneOffset.UTC;
    DateTimeFormatter dateFormatter = DATE_PATTERN.withZone(effectiveZone);
    DateTimeFormatter timeFormatter = TIME_PATTERN.withZone(effectiveZone);

    String jobOrderNumber = "#" + handover.getJobOrder().getDisplayId();
    String recipientHandle = handover.getRecipientHandle();
    String handoverDate = dateFormatter.format(handover.getHandoverTime());
    String handoverTime = timeFormatter.format(handover.getHandoverTime());

    List<ItemRow> rows =
        handover.getItems().stream()
            .map(
                item ->
                    new ItemRow(
                        item.getMaterial() != null ? item.getMaterial().getName() : "-",
                        item.getLocationName() != null ? item.getLocationName() : "-",
                        item.getAmount(),
                        item.getQuality(),
                        item.getMaterial() != null && item.getMaterial().getQuantityType() != null
                            ? item.getMaterial().getQuantityType().name()
                            : null))
            .sorted(ITEM_COMPARATOR)
            .toList();

    return buildPdf(jobOrderNumber, handoverDate, handoverTime, recipientHandle, rows);
  }

  /**
   * Generates a handover report PDF preview from raw DTO data (before the handover is persisted).
   *
   * @param dto the preview request DTO
   * @return PDF as byte array
   */
  public byte @NotNull [] generateHandoverReportPreview(
      @NotNull HandoverReportPreviewRequestDto dto) {
    log.debug("Generating handover report preview for jobOrderNumber={}", dto.jobOrderNumber());

    // dto.handoverTime() is a LocalDateTime — exactly what the user typed in the modal.
    // No zone conversion is applied so the PDF shows the same date/time the user entered.
    String handoverDate = DATE_PATTERN.format(dto.handoverTime());
    String handoverTime = TIME_PATTERN.format(dto.handoverTime());

    List<ItemRow> rows =
        dto.items().stream()
            .map(
                item ->
                    new ItemRow(
                        item.materialName(),
                        item.locationName() != null ? item.locationName() : "-",
                        item.amount(),
                        item.quality(),
                        item.quantityType()))
            .sorted(ITEM_COMPARATOR)
            .toList();

    return buildPdf(dto.jobOrderNumber(), handoverDate, handoverTime, dto.recipientHandle(), rows);
  }

  private static final Comparator<ItemRow> ITEM_COMPARATOR =
      Comparator.comparing(ItemRow::materialName, String.CASE_INSENSITIVE_ORDER)
          .thenComparing(Comparator.comparingInt(ItemRow::quality).reversed())
          .thenComparing(Comparator.comparingDouble(ItemRow::amount).reversed());

  private byte @NotNull [] buildPdf(
      @NotNull String jobOrderNumber,
      @NotNull String handoverDate,
      @NotNull String handoverTime,
      @NotNull String recipientHandle,
      @NotNull List<ItemRow> rows) {
    try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
      KrtPdfSupport.KrtDocument krt = KrtPdfSupport.open(baos);

      KrtPdfSupport.addTitle(krt, "ÜBERGABEPROTOKOLL");

      PdfPTable metaTable = KrtPdfSupport.newMetaTable();
      KrtPdfSupport.addMetaRow(metaTable, "AUFTRAGSNUMMER", jobOrderNumber);
      KrtPdfSupport.addMetaRow(metaTable, "DATUM DER ÜBERGABE", handoverDate);
      KrtPdfSupport.addMetaRow(metaTable, "UHRZEIT DER ÜBERGABE", handoverTime + " (Lokalzeit)");
      // A recipient whose handle an Art. 17 request erased renders as the
      // placeholder rather than as the raw sentinel (REQ-SEC-062). The handover
      // itself is untouched; only the name is gone.
      KrtPdfSupport.addMetaRow(
          metaTable,
          "EMPFÄNGER (HANDLE)",
          HandleAnonymisation.humanise(recipientHandle, label("general.anonymisedHandle")));
      krt.document().add(metaTable);

      KrtPdfSupport.addSectionHeader(krt, "ÜBERGEBENE MATERIALIEN");

      PdfPTable matTable = new PdfPTable(4);
      matTable.setWidthPercentage(100);
      matTable.setWidths(new float[] {3f, 2.5f, 1.5f, 1.5f});

      KrtPdfSupport.addTableHeader(matTable, "MATERIAL");
      KrtPdfSupport.addTableHeader(matTable, "STANDORT");
      KrtPdfSupport.addTableHeader(matTable, "MENGE");
      KrtPdfSupport.addTableHeader(matTable, "QUALITÄT");

      boolean alt = false;
      for (ItemRow row : rows) {
        Color rowBg = KrtPdfSupport.rowBackground(alt);
        String amountStr = formatAmount(row.amount(), row.quantityType());
        KrtPdfSupport.addTableCell(matTable, row.materialName(), rowBg, false);
        KrtPdfSupport.addTableCell(matTable, row.locationName(), rowBg, false);
        KrtPdfSupport.addTableCell(matTable, amountStr, rowBg, true);
        KrtPdfSupport.addTableCell(matTable, String.valueOf(row.quality()), rowBg, true);
        alt = !alt;
      }

      if (rows.isEmpty()) {
        KrtPdfSupport.addEmptyRow(matTable, "Keine Materialien vorhanden.", 4);
      }

      krt.document().add(matTable);

      KrtPdfSupport.addFooter(krt);

      krt.document().close();
      return baos.toByteArray();
    } catch (Exception e) {
      // Caught by GlobalExceptionHandler.handleReportGeneration which produces a 500
      // RFC 7807 response with the stable code REPORT_GENERATION_FAILED and a localised
      // generic detail. The cause is preserved on the exception so the ERROR log line
      // emitted by the handler carries the full PDF-library stacktrace; the exception
      // message itself is server-internal and never leaks to the API client.
      throw new de.greluc.krt.profit.basetool.backend.exception.ReportGenerationException(
          "PDF generation failed", e);
    }
  }

  private @NotNull String formatAmount(double amount, String quantityType) {
    if ("PIECE".equalsIgnoreCase(quantityType)) {
      return String.valueOf((long) amount);
    }
    return String.format("%.3f SCU", amount);
  }

  /** Internal value record for a single material row in the PDF. */
  private record ItemRow(
      String materialName, String locationName, double amount, int quality, String quantityType) {}

  /**
   * Resolves one German PDF label from the backend message bundle.
   *
   * <p>This document is German by construction (its headers are literals), so the locale is fixed.
   * The one label that is resolved rather than written inline is the erased-handle placeholder: it
   * is shared with every other surface that renders a handle snapshot, and a second spelling of it
   * here would be a second thing to keep in step (REQ-SEC-062).
   *
   * @param key the message key
   * @return the resolved label
   */
  private @NotNull String label(@NotNull String key) {
    return messageSource.getMessage(key, null, Locale.GERMAN);
  }
}
