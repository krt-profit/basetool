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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemHandover;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.HandleAnonymisation;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.ReportGenerationException;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.KrtPdfDocument;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.KrtPdfDocuments;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.PdfDataTable;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.PdfKeyValue;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.PdfTableCell;
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
import org.jetbrains.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generates the item-handover delivery-note PDF in KRT Corporate Design, the item-order counterpart
 * of {@link JobOrderHandoverReportService}. The handover time is rendered in the caller's zone,
 * falling back to UTC and labelled as such.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class JobOrderItemHandoverReportService {

  private static final DateTimeFormatter DATE_PATTERN = DateTimeFormatter.ofPattern("dd.MM.yyyy");
  private static final DateTimeFormatter TIME_PATTERN = DateTimeFormatter.ofPattern("HH:mm");
  private static final String LOCAL_TIME_SUFFIX = " (Lokalzeit)";
  private static final String UTC_SUFFIX = " (UTC)";

  /** Relative widths of the item and amount columns. */
  private static final float[] ITEM_WIDTHS = {4f, 1.5f};

  private static final Comparator<ItemRow> ITEM_COMPARATOR =
      Comparator.comparing(ItemRow::itemName, String.CASE_INSENSITIVE_ORDER)
          .thenComparing(Comparator.comparingInt(ItemRow::amount).reversed());

  private final JobOrderItemHandoverRepository jobOrderItemHandoverRepository;
  private final MessageSource messageSource;
  private final KrtPdfDocuments pdfDocuments;

  /**
   * Generates the delivery-note PDF for a persisted item handover.
   *
   * @param jobOrderId the item order id (validated against the handover's parent)
   * @param handoverId the item-handover id
   * @param userZone the zone to render the handover time in; {@code null} renders and labels it as
   *     UTC
   * @return the PDF as a byte array
   * @throws NotFoundException when the handover is unknown or belongs to a different order
   */
  public byte @NotNull [] generateItemHandoverReport(
      @NotNull UUID jobOrderId, @NotNull UUID handoverId, @Nullable ZoneId userZone) {
    JobOrderItemHandover handover =
        Entities.require(
            jobOrderItemHandoverRepository.findById(handoverId), "Item handover not found");

    if (!handover.getJobOrder().getId().equals(jobOrderId)) {
      throw new NotFoundException("Item handover does not belong to this job order");
    }

    ZoneId effectiveZone = userZone != null ? userZone : ZoneOffset.UTC;
    DateTimeFormatter dateFormatter = DATE_PATTERN.withZone(effectiveZone);
    DateTimeFormatter timeFormatter = TIME_PATTERN.withZone(effectiveZone);

    String jobOrderNumber = "#" + handover.getJobOrder().getDisplayId();
    String handoverDate = dateFormatter.format(handover.getHandoverTime());
    String handoverTime =
        timeFormatter.format(handover.getHandoverTime())
            + (userZone != null ? LOCAL_TIME_SUFFIX : UTC_SUFFIX);

    List<ItemRow> rows =
        handover.getEntries().stream()
            .map(entry ->
                new ItemRow(
                    entry.getJobOrderItem() != null && entry.getJobOrderItem().getGameItem() != null
                        ? entry.getJobOrderItem().getGameItem().getName()
                        : "-",
                    entry.getAmount() == null ? 0 : entry.getAmount()))
            .sorted(ITEM_COMPARATOR)
            .toList();

    return buildPdf(
        jobOrderNumber, handoverDate, handoverTime, handover.getRecipientHandle(), rows);
  }

  private byte @NotNull [] buildPdf(
      @NotNull String jobOrderNumber,
      @NotNull String handoverDate,
      @NotNull String handoverTime,
      @NotNull String recipientHandle,
      @NotNull List<ItemRow> rows) {
    try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
      try (KrtPdfDocument pdf = pdfDocuments.open(baos, "ÜBERGABEPROTOKOLL")) {
        pdf.keyValueTable(
            List.of(
                new PdfKeyValue("AUFTRAGSNUMMER", jobOrderNumber),
                new PdfKeyValue("DATUM DER ÜBERGABE", handoverDate),
                new PdfKeyValue("UHRZEIT DER ÜBERGABE", handoverTime),
                new PdfKeyValue(
                    "EMPFÄNGER (HANDLE)",
                    HandleAnonymisation.humaniseRequired(
                        recipientHandle, label("general.anonymisedHandle")))));
        pdf.heading("ÜBERGEBENE GÜTER");
        try (PdfDataTable table = pdf.dataTable(ITEM_WIDTHS, List.of("GUT", "MENGE"))) {
          for (ItemRow row : rows) {
            table.row(
                List.of(
                    PdfTableCell.text(row.itemName()),
                    PdfTableCell.centered(String.valueOf(row.amount()))));
          }
          if (rows.isEmpty()) {
            table.emptyRow("Keine Güter vorhanden.");
          }
        }
        pdf.footer();
      }
      return baos.toByteArray();
    } catch (Exception e) {
      throw new ReportGenerationException("PDF generation failed", e);
    }
  }

  /** Internal value record for one delivered-item row in the PDF. */
  private record ItemRow(String itemName, int amount) {}

  /**
   * Resolves one German PDF label from the backend message bundle; the locale is fixed because the
   * document is German.
   *
   * @param key the message key
   * @return the resolved label
   */
  private @NotNull String label(@NotNull String key) {
    return messageSource.getMessage(key, null, Locale.GERMAN);
  }
}
