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

package de.greluc.krt.profit.basetool.backend.service.pdf;

import de.greluc.krt.profit.basetool.backend.exception.ReportGenerationException;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.UnaryOperator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.openpdf.text.pdf.PdfPTable;

/**
 * Shared renderer for the activity audit-log PDFs (REQ-AUDIT-001) and the bank audit-log export,
 * printing a title, a period and pre-localized {@link Row}s in the KRT design via {@link
 * KrtPdfSupport}.
 */
public final class AuditLogPdfFormat {

  /** Timestamp pattern for the meta block and table rows; zone bound per request. */
  private static final DateTimeFormatter DATE_TIME_PATTERN =
      DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

  private AuditLogPdfFormat() {}

  /**
   * One rendered audit-log line: all strings are already resolved/localized by the caller.
   *
   * @param occurredAt the mutation instant (UTC)
   * @param actor the actor handle snapshot
   * @param event the localized event-type label
   * @param subject the affected subject label (account number / inventory label / order title)
   * @param details the compact details payload
   */
  public record Row(
      @NotNull Instant occurredAt,
      @NotNull String actor,
      @NotNull String event,
      @NotNull String subject,
      @NotNull String details) {}

  /**
   * Renders the audit-log PDF for one area/period.
   *
   * @param title the (uppercase) document title, already resolved for the area
   * @param from period start (inclusive)
   * @param to period end (inclusive)
   * @param userZone the zone to render timestamps in; {@code null} falls back to UTC
   * @param rows the chronological events to print (oldest first)
   * @param label the message-bundle resolver of the calling service (for column/meta labels)
   * @return the PDF bytes
   */
  public static byte @NotNull [] render(
      @NotNull String title,
      @NotNull Instant from,
      @NotNull Instant to,
      @Nullable ZoneId userZone,
      @NotNull List<Row> rows,
      @NotNull UnaryOperator<String> label) {
    ZoneId zone = userZone != null ? userZone : ZoneOffset.UTC;
    DateTimeFormatter stamp = DATE_TIME_PATTERN.withZone(zone);

    try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
      KrtPdfSupport.KrtDocument krt = KrtPdfSupport.open(baos);

      KrtPdfSupport.addTitle(krt, title);

      PdfPTable meta = KrtPdfSupport.newMetaTable();
      KrtPdfSupport.addMetaRow(
          meta, label.apply("pdf.audit.period"), stamp.format(from) + " – " + stamp.format(to));
      KrtPdfSupport.addMetaRow(
          meta, label.apply("pdf.audit.generated"), stamp.format(Instant.now()));
      KrtPdfSupport.addMetaRow(meta, label.apply("pdf.audit.count"), String.valueOf(rows.size()));
      krt.document().add(meta);

      KrtPdfSupport.addSectionHeader(krt, label.apply("pdf.audit.events"));

      PdfPTable table = new PdfPTable(5);
      table.setWidthPercentage(100);
      table.setWidths(new float[] {1.6f, 1.3f, 1.7f, 1.7f, 2.4f});
      KrtPdfSupport.addTableHeader(table, label.apply("pdf.audit.col.time"));
      KrtPdfSupport.addTableHeader(table, label.apply("pdf.audit.col.actor"));
      KrtPdfSupport.addTableHeader(table, label.apply("pdf.audit.col.event"));
      KrtPdfSupport.addTableHeader(table, label.apply("pdf.audit.col.subject"));
      KrtPdfSupport.addTableHeader(table, label.apply("pdf.audit.col.details"));

      boolean alt = false;
      for (Row row : rows) {
        Color bg = KrtPdfSupport.rowBackground(alt);
        KrtPdfSupport.addTableCell(table, stamp.format(row.occurredAt()), bg, false);
        KrtPdfSupport.addTableCell(table, row.actor(), bg, false);
        KrtPdfSupport.addTableCell(table, row.event(), bg, false);
        KrtPdfSupport.addTableCell(table, row.subject(), bg, false);
        KrtPdfSupport.addTableCell(table, row.details(), bg, false);
        alt = !alt;
      }
      if (rows.isEmpty()) {
        KrtPdfSupport.addEmptyRow(table, label.apply("pdf.audit.empty"), 5);
      }
      krt.document().add(table);

      KrtPdfSupport.addFooter(krt);
      krt.document().close();
      return baos.toByteArray();
    } catch (Exception e) {
      throw new ReportGenerationException("PDF generation failed", e);
    }
  }
}
