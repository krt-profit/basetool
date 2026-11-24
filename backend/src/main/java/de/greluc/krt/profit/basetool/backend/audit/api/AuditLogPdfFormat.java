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

package de.greluc.krt.profit.basetool.backend.audit.api;

import de.greluc.krt.profit.basetool.backend.kernel.ReportGenerationException;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.KrtPdfDocument;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.KrtPdfDocuments;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.PdfDataTable;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.PdfKeyValue;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.PdfTableCell;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.UnaryOperator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Shared renderer for the activity audit-log PDFs (REQ-AUDIT-001) and the bank audit-log export,
 * printing a title, a period and pre-localized {@link Row}s as a tagged KRT document (REQ-API-029).
 */
public final class AuditLogPdfFormat {

  /** Timestamp pattern for the meta block and table rows; zone bound per request. */
  private static final DateTimeFormatter DATE_TIME_PATTERN =
      DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

  /** Relative widths of the time, actor, event, subject and details columns. */
  private static final float[] COLUMN_WIDTHS = {1.6f, 1.3f, 1.7f, 1.7f, 2.4f};

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
   * @param documents opens the tagged PDF
   * @param title the (uppercase) document title, already resolved for the area
   * @param from period start (inclusive)
   * @param to period end (inclusive)
   * @param userZone the zone to render timestamps in; {@code null} falls back to UTC
   * @param rows the chronological events to print (oldest first)
   * @param label the message-bundle resolver of the calling service (for column/meta labels)
   * @return the PDF bytes
   */
  public static byte @NotNull [] render(
      @NotNull KrtPdfDocuments documents,
      @NotNull String title,
      @NotNull Instant from,
      @NotNull Instant to,
      @Nullable ZoneId userZone,
      @NotNull List<Row> rows,
      @NotNull UnaryOperator<String> label) {
    ZoneId zone = userZone != null ? userZone : ZoneOffset.UTC;
    DateTimeFormatter stamp = DATE_TIME_PATTERN.withZone(zone);

    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      try (KrtPdfDocument pdf = documents.open(out, title)) {
        pdf.keyValueTable(
            List.of(
                new PdfKeyValue(
                    label.apply("pdf.audit.period"), stamp.format(from) + " – " + stamp.format(to)),
                new PdfKeyValue(label.apply("pdf.audit.generated"), stamp.format(Instant.now())),
                new PdfKeyValue(label.apply("pdf.audit.count"), String.valueOf(rows.size()))));
        pdf.heading(label.apply("pdf.audit.events"));
        try (PdfDataTable table =
            pdf.dataTable(
                COLUMN_WIDTHS,
                List.of(
                    label.apply("pdf.audit.col.time"),
                    label.apply("pdf.audit.col.actor"),
                    label.apply("pdf.audit.col.event"),
                    label.apply("pdf.audit.col.subject"),
                    label.apply("pdf.audit.col.details")))) {
          for (Row row : rows) {
            table.row(
                List.of(
                    PdfTableCell.text(stamp.format(row.occurredAt())),
                    PdfTableCell.text(row.actor()),
                    PdfTableCell.text(row.event()),
                    PdfTableCell.text(row.subject()),
                    PdfTableCell.text(row.details())));
          }
          if (rows.isEmpty()) {
            table.emptyRow(label.apply("pdf.audit.empty"));
          }
        }
        pdf.footer();
      }
      return out.toByteArray();
    } catch (Exception e) {
      throw new ReportGenerationException("PDF generation failed", e);
    }
  }
}
