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

package de.greluc.krt.profit.basetool.frontend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests the {@link MarkdownRenderer} contract the templates rely on for {@code th:utext} safety:
 * Markdown syntax renders to HTML, while raw HTML in the source is escaped and dangerous link
 * protocols are stripped — the rendered output must never carry user-controlled markup.
 */
class MarkdownRendererTest {

  private final MarkdownRenderer renderer = new MarkdownRenderer();

  @Test
  void shouldRenderBasicMarkdownToHtml() {
    String markdown = "**fett** und *kursiv*\n\n- eins\n- zwei";

    String html = renderer.render(markdown);

    assertTrue(html.contains("<strong>fett</strong>"), "bold must render");
    assertTrue(html.contains("<em>kursiv</em>"), "italics must render");
    assertTrue(html.contains("<li>eins</li>"), "list items must render");
  }

  @Test
  void shouldEscapeRawHtmlInSource() {
    String markdown = "Hallo <script>alert('xss')</script> <img src=x onerror=alert(1)>";

    String html = renderer.render(markdown);

    assertFalse(html.contains("<script>"), "raw script tags must be escaped");
    assertFalse(html.contains("<img"), "raw img tags must be escaped");
    assertTrue(html.contains("&lt;script&gt;"), "escaped form must remain visible as text");
  }

  @Test
  void shouldStripDangerousLinkProtocols() {
    String markdown = "[klick mich](javascript:alert('xss')) und [ok](https://example.org)";

    String html = renderer.render(markdown);

    assertFalse(html.contains("javascript:"), "javascript: URLs must be stripped");
    assertTrue(html.contains("href=\"https://example.org\""), "https links must survive");
  }

  @Test
  void shouldRenderSoftBreaksAsLineBreaks() {
    String markdown = "Zeile eins\nZeile zwei";

    String html = renderer.render(markdown);

    assertTrue(html.contains("<br />"), "soft breaks must become visible line breaks");
  }

  @Test
  void shouldReturnEmptyStringForNullAndBlank() {
    assertEquals("", renderer.render(null), "null input must yield an empty string");
    assertEquals("", renderer.render("   "), "blank input must yield an empty string");
  }
}
