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

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;

/**
 * Rewrites a page template's source so that rendering it needs no model: everything the page itself
 * supplies is replaced by an inert marker, and what the shared chrome contributes is left to the
 * real fragments.
 *
 * <p>Two source shapes are understood and yield the same markup, which is what makes the page
 * chrome snapshot independent of how a page is composed: the legacy shape, where the page calls the
 * {@code sidebar}, {@code header} and {@code toast} fragments itself, and the layout shape, where
 * it hands its {@code pageContent} and {@code pageTail} blocks to {@code fragments/layout}. In
 * both, the text before the toast belongs to {@link #CONTENT} and the text after it to {@link
 * #TAIL}.
 */
final class ChromeNeutraliser {

  /** Marker standing for everything a page renders between the chrome and the toast. */
  static final String CONTENT = "<x-content></x-content>";

  /** Marker standing for everything a page renders after the toast. */
  static final String TAIL = "<x-tail></x-tail>";

  /** The page's {@code <body>} opening tag. */
  private static final Pattern BODY_OPEN =
      Pattern.compile("<body\\b(?:[^>\"']|\"[^\"]*\"|'[^']*')*>");

  /** A page-level call of the sidebar, header or toast fragment. */
  private static final Pattern CHROME_CALL =
      Pattern.compile(
          "<th:block\\s+th:replace=\"~\\{fragments/(sidebar|header|toast)\\s*::\\s*\\1\\}\"\\s*/?>"
              + "(?:\\s*</th:block>)?");

  /** An opening or closing {@code th:block} tag, attribute values included. */
  private static final Pattern BLOCK_TAG =
      Pattern.compile("<(/?)th:block\\b(?:[^>\"']|\"[^\"]*\"|'[^']*')*>");

  /** The shape that marks a page as composed through the layout fragment. */
  private static final String LAYOUT_CALL = "fragments/layout";

  /** Not instantiable. */
  private ChromeNeutraliser() {}

  /**
   * Neutralises one page template.
   *
   * @param source the template's source text
   * @return the source with the page's own markup replaced by {@link #CONTENT} and {@link #TAIL}
   */
  static @NotNull String neutralise(@NotNull String source) {
    return source.contains(LAYOUT_CALL) ? neutraliseLayoutShape(source) : neutraliseLegacy(source);
  }

  /**
   * Handles a page that calls the chrome fragments itself.
   *
   * @param source the template source
   * @return the source with the body's page-owned text replaced by markers
   */
  private static @NotNull String neutraliseLegacy(@NotNull String source) {
    Matcher bodyOpen = BODY_OPEN.matcher(source);
    int bodyClose = source.lastIndexOf("</body>");
    if (!bodyOpen.find() || bodyClose < 0) {
      throw new IllegalArgumentException("no <body> element in the template");
    }
    String inner = source.substring(bodyOpen.end(), bodyClose);
    StringBuilder out = new StringBuilder();
    boolean afterToast = false;
    int cursor = 0;
    Matcher call = CHROME_CALL.matcher(inner);
    while (call.find()) {
      appendRegion(out, inner.substring(cursor, call.start()), afterToast);
      out.append(call.group());
      if ("toast".equals(call.group(1))) {
        afterToast = true;
      }
      cursor = call.end();
    }
    appendRegion(out, inner.substring(cursor), afterToast);
    return source.substring(0, bodyOpen.end()) + out + source.substring(bodyClose);
  }

  /**
   * Appends the marker for a page-owned region, if the region holds anything.
   *
   * @param out the output
   * @param region the text between two chrome calls
   * @param afterToast whether the toast call precedes the region
   */
  private static void appendRegion(
      @NotNull StringBuilder out, @NotNull String region, boolean afterToast) {
    if (!region.isBlank()) {
      out.append(afterToast ? TAIL : CONTENT);
    }
  }

  /**
   * Handles a page composed through {@code fragments/layout}.
   *
   * @param source the template source
   * @return the source with the {@code pageContent} and {@code pageTail} blocks emptied to markers
   */
  private static @NotNull String neutraliseLayoutShape(@NotNull String source) {
    String result = replaceBlock(source, "pageContent", CONTENT);
    return replaceBlock(result, "pageTail", TAIL);
  }

  /**
   * Replaces the inner markup of the {@code th:block} that declares a fragment name.
   *
   * @param source the template source
   * @param fragment the {@code th:fragment} name of the block
   * @param marker the replacement for the block's inner markup
   * @return the source, unchanged when the block does not exist
   */
  private static @NotNull String replaceBlock(
      @NotNull String source, @NotNull String fragment, @NotNull String marker) {
    Pattern open =
        Pattern.compile("<th:block\\s+th:fragment=\"" + Pattern.quote(fragment) + "\"\\s*>");
    Matcher start = open.matcher(source);
    if (!start.find()) {
      return source;
    }
    Matcher tag = BLOCK_TAG.matcher(source);
    tag.region(start.end(), source.length());
    int depth = 1;
    while (tag.find()) {
      if (!tag.group(1).isEmpty()) {
        depth--;
        if (depth == 0) {
          return source.substring(0, start.end()) + marker + source.substring(tag.start());
        }
      } else if (!tag.group().endsWith("/>")) {
        depth++;
      }
    }
    throw new IllegalArgumentException("unbalanced th:block for fragment " + fragment);
  }
}
