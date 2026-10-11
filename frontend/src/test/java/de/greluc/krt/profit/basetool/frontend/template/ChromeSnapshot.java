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

package de.greluc.krt.profit.basetool.frontend.template;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Reduces one rendered, neutralised page to the golden lines of the page chrome snapshot: the
 * {@code <html>} tag, the head split into its fixed part and the page's own title and links, the
 * {@code <body>} tag, and the ordered parts of the body.
 *
 * <p>Whitespace between tags is normalised away and the values that change between requests or
 * builds (CSP nonce, CSRF token, the application version) are masked, so only a real difference of
 * markup changes a line.
 */
final class ChromeSnapshot {

  /** Length of a hash as written to the golden file. */
  private static final int HASH_LENGTH = 16;

  private static final Pattern HTML_OPEN = Pattern.compile("<html\\b[^>]*>");

  private static final Pattern HEAD = Pattern.compile("<head\\b[^>]*>(.*?)</head>", Pattern.DOTALL);

  private static final Pattern BODY = Pattern.compile("<body\\b[^>]*>(.*)</body>", Pattern.DOTALL);

  private static final Pattern BODY_OPEN = Pattern.compile("<body\\b[^>]*>");

  private static final Pattern PART_MARKER =
      Pattern.compile(
          Pattern.quote(ChromeNeutraliser.CONTENT) + "|" + Pattern.quote(ChromeNeutraliser.TAIL));

  private static final Pattern NONCE = Pattern.compile("nonce=\"[^\"]*\"");

  private static final Pattern CSRF_META =
      Pattern.compile("(<meta name=\"_csrf(?:_header)?\" content=)\"[^\"]*\"");

  private static final Pattern CSRF_INPUT = Pattern.compile("(name=\"_csrf\"\\s+value=)\"[^\"]*\"");

  private static final Pattern VERSION = Pattern.compile("(class=\"[a-z-]*version\"[^>]*>)[^<]*");

  private static final Pattern GAP_BETWEEN_TAGS = Pattern.compile(">\\s+<");

  private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

  /** Not instantiable. */
  private ChromeSnapshot() {}

  /**
   * Builds the golden lines of one page.
   *
   * @param page the page's template path below the template root, e.g. {@code admin/bank.html}
   * @param html the rendered, neutralised page
   * @return the lines in a fixed order, each starting with the page path
   */
  static @NotNull @Unmodifiable List<String> lines(@NotNull String page, @NotNull String html) {
    String normalised = normalise(html);
    List<String> lines = new ArrayList<>();
    lines.add(page + " html " + firstMatch(HTML_OPEN, normalised));
    lines.addAll(headLines(page, normalised));
    lines.add(page + " body " + firstMatch(BODY_OPEN, normalised));
    lines.add(page + " parts " + parts(normalised));
    return List.copyOf(lines);
  }

  /**
   * Collapses whitespace between tags and masks the values that change on every request.
   *
   * @param html the rendered page
   * @return the normalised markup
   */
  static @NotNull String normalise(@NotNull String html) {
    String masked = NONCE.matcher(html).replaceAll("nonce=\"N\"");
    masked = CSRF_META.matcher(masked).replaceAll("$1\"T\"");
    masked = CSRF_INPUT.matcher(masked).replaceAll("$1\"T\"");
    masked = VERSION.matcher(masked).replaceAll("$1V");
    masked = GAP_BETWEEN_TAGS.matcher(masked).replaceAll("><");
    return WHITESPACE_RUN.matcher(masked).replaceAll(" ").strip();
  }

  private static @NotNull List<String> headLines(@NotNull String page, @NotNull String html) {
    Matcher head = HEAD.matcher(html);
    if (!head.find()) {
      return List.of(page + " head missing");
    }
    String inner = head.group(1);
    int title = inner.indexOf("<title");
    String fixed = title < 0 ? inner : inner.substring(0, title);
    String own = title < 0 ? "" : inner.substring(title);
    return List.of(page + " head-fixed " + hash(fixed), page + " head-own " + own);
  }

  private static @NotNull String parts(@NotNull String html) {
    Matcher body = BODY.matcher(html);
    if (!body.find()) {
      return "no body";
    }
    String inner = body.group(1);
    List<String> parts = new ArrayList<>();
    Matcher marker = PART_MARKER.matcher(inner);
    int cursor = 0;
    while (marker.find()) {
      addSegment(parts, inner.substring(cursor, marker.start()));
      parts.add(marker.group().substring(1, marker.group().indexOf('>')));
      cursor = marker.end();
    }
    addSegment(parts, inner.substring(cursor));
    return String.join(" ", parts);
  }

  private static void addSegment(@NotNull List<String> parts, @NotNull String segment) {
    if (!segment.isBlank()) {
      parts.add(label(segment) + ":" + hash(segment));
    }
  }

  private static @NotNull String label(@NotNull String segment) {
    if (segment.contains("id=\"sidebar\"")) {
      return segment.contains("<header>") ? "chrome" : "sidebar";
    }
    if (segment.contains("<header>")) {
      return "header";
    }
    return segment.contains("krtToastI18n") ? "toast" : "other";
  }

  private static @NotNull String firstMatch(@NotNull Pattern pattern, @NotNull String html) {
    Matcher matcher = pattern.matcher(html);
    return matcher.find() ? matcher.group() : "missing";
  }

  private static @NotNull String hash(@NotNull String text) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest).substring(0, HASH_LENGTH);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
