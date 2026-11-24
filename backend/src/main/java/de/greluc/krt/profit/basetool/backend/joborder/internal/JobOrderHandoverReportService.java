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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderHandover;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.ReportGenerationException;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.KrtPdfDocument;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.KrtPdfDocuments;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.PdfDataTable;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.PdfTableCell;
import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generates handover report PDFs in KRT corporate design, for persisted handovers or as a preview
 * from raw DTO data, as tagged documents opened through {@link KrtPdfDocuments}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class JobOrderHandoverReportService {

  private static final DateTimeFormatter DATE_PATTERN = DateTimeFormatter.ofPattern("dd.MM.yyyy");
  private static final DateTimeFormatter TIME_PATTERN = DateTimeFormatter.ofPattern("HH:mm");

  /** Relative widths of the material, location, amount and quality columns. */
  private static final float[] MATERIAL_WIDTHS = {3f, 2.5f, 1.5f, 1.5f};

  private final JobOrderHandoverRepository jobOrderHandoverRepository;
  private final HandoverReportLabels labels;
  private final KrtPdfDocuments pdfDocuments;

  /**
   * Generates the report PDF for a persisted handover, rendering the handover time in {@code
   * userZone}.
   *
   * @param jobOrderId the job order ID
   * @param handoverId the handover ID
   * @param userZone the display time zone; {@code null} means UTC
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
            .map(item ->
                new ItemRow(
                    item.getMaterial() != null ? item.getMaterial().getName() : "-",
                    item.getLocationName() != null ? item.getLocationName() : "-",
                    item.getAmount(),
                    Objects.requireNonNull(item.getQuality()),
                    item.getMaterial() != null && item.getMaterial().getQuantityType() != null
                        ? item.getMaterial().getQuantityType().name()
                        : null))
            .sorted(ITEM_COMPARATOR)
            .toList();

    return buildPdf(
        jobOrderNumber,
        handoverDate,
        handoverTime,
        handover.getHandingOverHandle(),
        recipientHandle,
        rows);
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

    String handoverDate = DATE_PATTERN.format(dto.handoverTime());
    String handoverTime = TIME_PATTERN.format(dto.handoverTime());

    List<ItemRow> rows =
        dto.items().stream()
            .map(item ->
                new ItemRow(
                    item.materialName(),
                    item.locationName() != null ? item.locationName() : "-",
                    item.amount(),
                    item.quality(),
                    item.quantityType()))
            .sorted(ITEM_COMPARATOR)
            .toList();

    return buildPdf(
        dto.jobOrderNumber(),
        handoverDate,
        handoverTime,
        dto.handingOverHandle(),
        dto.recipientHandle(),
        rows);
  }

  private static final Comparator<ItemRow> ITEM_COMPARATOR =
      Comparator.comparing(ItemRow::materialName, String.CASE_INSENSITIVE_ORDER)
          .thenComparing(Comparator.comparingInt(ItemRow::quality).reversed())
          .thenComparing(Comparator.comparingDouble(ItemRow::amount).reversed());

  private byte @NotNull [] buildPdf(
      @NotNull String jobOrderNumber,
      @NotNull String handoverDate,
      @NotNull String handoverTime,
      @Nullable String handingOverHandle,
      @NotNull String recipientHandle,
      @NotNull List<ItemRow> rows) {
    try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
      try (KrtPdfDocument pdf = pdfDocuments.open(baos, labels.title())) {
        pdf.keyValueTable(
            labels.header(
                jobOrderNumber, handoverDate, handoverTime, handingOverHandle, recipientHandle));
        pdf.heading(labels.resolve("pdf.handover.materials"));
        try (PdfDataTable table =
            pdf.dataTable(
                MATERIAL_WIDTHS,
                List.of(
                    labels.resolve("pdf.handover.materials.col.material"),
                    labels.resolve("pdf.handover.materials.col.location"),
                    labels.resolve("pdf.handover.col.amount"),
                    labels.resolve("pdf.handover.materials.col.quality")))) {
          for (ItemRow row : rows) {
            table.row(
                List.of(
                    PdfTableCell.text(row.materialName()),
                    PdfTableCell.text(row.locationName()),
                    PdfTableCell.centered(formatAmount(row.amount(), row.quantityType())),
                    PdfTableCell.centered(String.valueOf(row.quality()))));
          }
          if (rows.isEmpty()) {
            table.emptyRow(labels.resolve("pdf.handover.materials.empty"));
          }
        }
        pdf.footer();
      }
      return baos.toByteArray();
    } catch (Exception e) {
      throw new ReportGenerationException("PDF generation failed", e);
    }
  }

  private @NotNull String formatAmount(double amount, @Nullable String quantityType) {
    if ("PIECE".equalsIgnoreCase(quantityType)) {
      return String.valueOf((long) amount);
    }
    return String.format("%.3f SCU", amount);
  }

  /** Internal value record for a single material row in the PDF. */
  private record ItemRow(
      String materialName,
      String locationName,
      double amount,
      int quality,
      @Nullable String quantityType) {}
}
