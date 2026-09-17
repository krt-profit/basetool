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

import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.exception.ReportGenerationException;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItemHandover;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderItemHandoverRepository;
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
 * Generates the item-handover delivery-note PDF in KRT Corporate Design — the item-order
 * counterpart of {@link JobOrderHandoverReportService}. Renders the persisted handover (by id) with
 * the produced items and their whole-unit quantities. The persisted {@code handoverTime} is UTC and
 * is rendered in the caller's zone (from {@code X-User-Time-Zone}), falling back to UTC. The page
 * background, the embedded Lato fonts and the cell helpers come from the shared {@link
 * KrtPdfSupport} layer (epic #556 Phase 3) — the rendered content is unchanged.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class JobOrderItemHandoverReportService {

  private static final DateTimeFormatter DATE_PATTERN = DateTimeFormatter.ofPattern("dd.MM.yyyy");
  private static final DateTimeFormatter TIME_PATTERN = DateTimeFormatter.ofPattern("HH:mm");

  private static final Comparator<ItemRow> ITEM_COMPARATOR =
      Comparator.comparing(ItemRow::itemName, String.CASE_INSENSITIVE_ORDER)
          .thenComparing(Comparator.comparingInt(ItemRow::amount).reversed());

  private final JobOrderItemHandoverRepository jobOrderItemHandoverRepository;
  private final MessageSource messageSource;

  /**
   * Generates the delivery-note PDF for a persisted item handover.
   *
   * @param jobOrderId the item order id (validated against the handover's parent)
   * @param handoverId the item-handover id
   * @param userZone the zone to render the handover time in; {@code null} falls back to UTC
   * @return the PDF as a byte array
   * @throws NotFoundException when the handover is unknown or belongs to a different order
   */
  public byte @NotNull [] generateItemHandoverReport(
      @NotNull UUID jobOrderId, @NotNull UUID handoverId, ZoneId userZone) {
    JobOrderItemHandover handover =
        jobOrderItemHandoverRepository
            .findById(handoverId)
            .orElseThrow(() -> new NotFoundException("Item handover not found"));

    if (!handover.getJobOrder().getId().equals(jobOrderId)) {
      throw new NotFoundException("Item handover does not belong to this job order");
    }

    ZoneId effectiveZone = userZone != null ? userZone : ZoneOffset.UTC;
    DateTimeFormatter dateFormatter = DATE_PATTERN.withZone(effectiveZone);
    DateTimeFormatter timeFormatter = TIME_PATTERN.withZone(effectiveZone);

    String jobOrderNumber = "#" + handover.getJobOrder().getDisplayId();
    String handoverDate = dateFormatter.format(handover.getHandoverTime());
    String handoverTime = timeFormatter.format(handover.getHandoverTime());

    List<ItemRow> rows =
        handover.getEntries().stream()
            .map(
                entry ->
                    new ItemRow(
                        entry.getJobOrderItem() != null
                                && entry.getJobOrderItem().getGameItem() != null
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

      KrtPdfSupport.addSectionHeader(krt, "ÜBERGEBENE GÜTER");

      PdfPTable itemTable = new PdfPTable(2);
      itemTable.setWidthPercentage(100);
      itemTable.setWidths(new float[] {4f, 1.5f});
      KrtPdfSupport.addTableHeader(itemTable, "GUT");
      KrtPdfSupport.addTableHeader(itemTable, "MENGE");

      boolean alt = false;
      for (ItemRow row : rows) {
        Color rowBg = KrtPdfSupport.rowBackground(alt);
        KrtPdfSupport.addTableCell(itemTable, row.itemName(), rowBg, false);
        KrtPdfSupport.addTableCell(itemTable, String.valueOf(row.amount()), rowBg, true);
        alt = !alt;
      }
      if (rows.isEmpty()) {
        KrtPdfSupport.addEmptyRow(itemTable, "Keine Güter vorhanden.", 2);
      }
      krt.document().add(itemTable);

      KrtPdfSupport.addFooter(krt);

      krt.document().close();
      return baos.toByteArray();
    } catch (Exception e) {
      throw new ReportGenerationException("PDF generation failed", e);
    }
  }

  /** Internal value record for one delivered-item row in the PDF. */
  private record ItemRow(String itemName, int amount) {}

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
