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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

/**
 * Unit tests for {@link AuditLogPdfFormat} (REQ-AUDIT-001): row and period timestamps render in the
 * supplied {@code userZone} (UTC when {@code null}), while the {@link KrtPdfSupport#addFooter}
 * generation stamp stays UTC. The PDF is read back via {@code PdfTextExtractor}, with an identity
 * {@link UnaryOperator} as message resolver.
 */
class AuditLogPdfFormatTest {

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
            "AUDIT LOG TITLE", FROM, TO, null, List.of(row), UnaryOperator.identity());

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
