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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditLogPdfFormat;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.KrtPdfDocument;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.KrtPdfDocuments;
import de.greluc.krt.profit.basetool.backend.platform.internal.pdf.TaggedKrtPdfDocuments;
import de.greluc.krt.profit.basetool.backend.support.BackendMessages;
import de.greluc.krt.profit.basetool.backend.support.PdfConformance;
import de.greluc.krt.profit.basetool.backend.support.TaggedStructure;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

/**
 * Unit tests for {@link AuditLogPdfFormat} (REQ-AUDIT-001): row and period timestamps render in the
 * supplied {@code userZone} (UTC when {@code null}), while the {@link KrtPdfDocument#footer}
 * generation stamp stays UTC. The PDF is read back via {@code PdfTextExtractor}, with an identity
 * {@link UnaryOperator} as message resolver.
 */
class AuditLogPdfFormatTest {

  /** The production PDF layer, which opens the tagged documents. */
  private static final KrtPdfDocuments PDF_DOCUMENTS =
      new TaggedKrtPdfDocuments(BackendMessages.bundle());

  /** The most rows an audit-log export may hold (REQ-AUDIT-001). */
  private static final int ROW_CAP = 100_000;

  /** The size the full row cap must stay below: compressed it is about 26 MB. */
  private static final int MAX_CAP_BYTES = 40 * 1024 * 1024;

  /** The row/period timestamp pattern the renderer binds to a zone (mirrors production). */
  private static final DateTimeFormatter PATTERN = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

  /** A fixed winter instant so Europe/Berlin is unambiguously CET (+01:00), never DST. */
  private static final Instant OCCURRED_AT = Instant.parse("2026-01-15T10:30:00Z");

  /** Period start, well clear of the event instant so it cannot collide in the text. */
  private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");

  /** Period end, well clear of the event instant so it cannot collide in the text. */
  private static final Instant TO = Instant.parse("2026-01-31T23:00:00Z");

  /**
   * Verifies that a non-UTC {@code userZone} shifts the event-row time (10:30 UTC renders as 11:30
   * in Europe/Berlin in January) while the footer generation stamp stays in UTC.
   *
   * @throws IOException when the rendered PDF cannot be parsed
   */
  @Test
  void render_userZone_shiftsRowTimeButNotFooter() throws IOException {
    AuditLogPdfFormat.Row row =
        new AuditLogPdfFormat.Row(
            OCCURRED_AT, "actorhandle", "eventlabel", "subjectlabel", "detailspayload");

    Instant before = Instant.now();
    byte[] pdf =
        AuditLogPdfFormat.render(
            PDF_DOCUMENTS,
            "AUDIT LOG TITLE",
            FROM,
            TO,
            ZoneId.of("Europe/Berlin"),
            List.of(row),
            UnaryOperator.identity());
    Instant after = Instant.now();

    String text = extractText(pdf);

    assertTrue(
        text.contains("15.01.2026 11:30"), "event row time is shifted into the user zone (CET)");
    assertFalse(
        text.contains("15.01.2026 10:30"),
        "the raw UTC wall-clock is not printed when a user zone is supplied");

    String footer = footerLine(text);
    assertTrue(footer.contains(" UTC"), "footer keeps its UTC marker");
    Set<String> utcNow =
        Set.copyOf(
            List.of(
                PATTERN.withZone(ZoneOffset.UTC).format(before),
                PATTERN.withZone(ZoneOffset.UTC).format(after)));
    Set<String> berlinNow =
        Set.copyOf(
            List.of(
                PATTERN.withZone(ZoneId.of("Europe/Berlin")).format(before),
                PATTERN.withZone(ZoneId.of("Europe/Berlin")).format(after)));
    assertTrue(
        utcNow.stream().anyMatch(footer::contains),
        "footer stamps the generation time in UTC (" + footer + ")");
    assertTrue(
        berlinNow.stream().noneMatch(footer::contains),
        "footer is NOT re-zoned into the user zone (" + footer + ")");
  }

  /**
   * The audit-log PDF, empty state included, conforms to PDF/A-4, PDF/UA-2 and both WTPDF levels
   * (REQ-API-026, REQ-API-029).
   *
   * @throws Exception when veraPDF cannot validate the rendered PDF
   */
  @Test
  void render_conformsToEveryProfileWithAndWithoutRows() throws Exception {
    AuditLogPdfFormat.Row row =
        new AuditLogPdfFormat.Row(OCCURRED_AT, "Jörg", "Übergabe", "Konto – 1", "k=v €");

    PdfConformance.assertConformant(
        AuditLogPdfFormat.render(
            PDF_DOCUMENTS,
            "AUDIT-LOG: BANK",
            FROM,
            TO,
            null,
            List.of(row),
            UnaryOperator.identity()));
    PdfConformance.assertConformant(
        AuditLogPdfFormat.render(
            PDF_DOCUMENTS, "AUDIT-LOG: BANK", FROM, TO, null, List.of(), UnaryOperator.identity()));
  }

  /**
   * The audit log is tagged as title, meta table, events heading, events table and footer, and the
   * events table heads its five columns (REQ-API-029).
   *
   * @throws IOException when the rendered PDF cannot be read
   */
  @Test
  void render_tagsTitleMetaHeadingTableAndFooter() throws IOException {
    AuditLogPdfFormat.Row row =
        new AuditLogPdfFormat.Row(OCCURRED_AT, "actor", "event", "subject", "details");

    TaggedStructure structure =
        TaggedStructure.read(
            AuditLogPdfFormat.render(
                PDF_DOCUMENTS,
                "AUDIT-LOG",
                FROM,
                TO,
                null,
                List.of(row),
                UnaryOperator.identity()));

    assertEquals(List.of("H1", "Table", "H2", "Table", "P"), structure.kidTypes());
    TaggedStructure header = structure.kids().get(3).kids().getFirst();
    assertEquals(List.of("TH", "TH", "TH", "TH", "TH"), header.kidTypes());
    assertTrue(
        header.kids().stream()
            .allMatch(cell -> "/Column".equals(cell.attributes().get("Table.Scope"))),
        "every column header heads its column");
  }

  /**
   * The full audit-log cap of 100 000 rows renders, and the compressed file stays a size a member
   * can download (REQ-AUDIT-001, REQ-API-029).
   */
  @Test
  void render_theFullRowCapStaysCompact() {
    List<AuditLogPdfFormat.Row> rows =
        IntStream.range(0, ROW_CAP)
            .mapToObj(index ->
                new AuditLogPdfFormat.Row(
                    OCCURRED_AT, "actor" + index, "event", "KB-" + index, "amount=" + index))
            .toList();

    byte[] pdf =
        AuditLogPdfFormat.render(
            PDF_DOCUMENTS, "AUDIT-LOG", FROM, TO, null, rows, UnaryOperator.identity());

    assertEquals("%PDF-2.0", new String(pdf, 0, 8, StandardCharsets.US_ASCII));
    assertTrue(pdf.length < MAX_CAP_BYTES, "100 000 rows render to " + pdf.length + " bytes");
  }

  /**
   * Gap 1 (fallback branch): a {@code null} {@code userZone} falls back to UTC, so the same 10:30
   * UTC event instant renders as 10:30 (never 11:30) and the footer remains the UTC audit stamp.
   * This guards the {@code userZone != null ? userZone : ZoneOffset.UTC} default.
   *
   * @throws IOException when the rendered PDF cannot be parsed
   */
  @Test
  void render_nullZone_fallsBackToUtc() throws IOException {
    AuditLogPdfFormat.Row row =
        new AuditLogPdfFormat.Row(
            OCCURRED_AT, "actorhandle", "eventlabel", "subjectlabel", "detailspayload");

    byte[] pdf =
        AuditLogPdfFormat.render(
            PDF_DOCUMENTS,
            "AUDIT LOG TITLE",
            FROM,
            TO,
            null,
            List.of(row),
            UnaryOperator.identity());

    String text = extractText(pdf);

    assertTrue(text.contains("15.01.2026 10:30"), "event row time falls back to UTC");
    assertFalse(text.contains("15.01.2026 11:30"), "no zone shift happens when userZone is null");
    assertTrue(footerLine(text).contains(" UTC"), "footer stays the UTC audit stamp");
  }

  /**
   * Extracts the concatenated page text of a rendered PDF via {@code PdfTextExtractor}, the same
   * read-back channel the report integration tests use.
   *
   * @param pdf the rendered PDF bytes
   * @return the concatenated page text
   * @throws IOException when the rendered PDF cannot be parsed
   */
  private static String extractText(byte[] pdf) throws IOException {
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

  /**
   * Isolates the single footer line so the UTC generation stamp can be asserted without the meta
   * 'generated' row (which IS rendered in the user zone) bleeding into the check. {@code
   * PdfTextExtractor} emits the footer line ahead of the meta/table rows in the content stream, so
   * the substring is bounded to the fixed prefix up to the next newline, yielding exactly the
   * footer line rather than the remainder of the page.
   *
   * @param text the extracted PDF text
   * @return the footer line (the 'Generiert von Profit Basetool am ...' line, newline-bounded)
   */
  private static String footerLine(String text) {
    int idx = text.indexOf("Generiert von Profit Basetool am ");
    assertTrue(idx >= 0, "the footer line is present in the rendered PDF");
    int end = text.indexOf('\n', idx);
    return end >= 0 ? text.substring(idx, end) : text.substring(idx);
  }
}
