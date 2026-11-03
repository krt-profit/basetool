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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Build-time check that the app header stays sticky on every page (REQ-UI-026): the header rule
 * pins it to the top, and no stylesheet turns {@code html} or {@code body} into a scroll container,
 * which would bind the header's stickiness to a box that never scrolls.
 */
class StickyHeaderTest {

  /** An innermost rule: its selector list and its declarations. */
  private static final Pattern RULE = Pattern.compile("([^{};]+)\\{([^{}]*)}");

  /** An overflow declaration that makes its element a scroll container; {@code clip} does not. */
  private static final Pattern SCROLL_CONTAINER =
      Pattern.compile("(?<![-\\w])overflow(?:-[xy])?\\s*:\\s*[^;]*\\b(?:hidden|auto|scroll)\\b");

  /** Any {@code position} or {@code top} declaration. */
  private static final Pattern POSITION = Pattern.compile("(?<![-\\w])(?:position|top)\\s*:");

  /** The sticky position declaration. */
  private static final Pattern STICKY = Pattern.compile("(?<![-\\w])position\\s*:\\s*sticky\\s*;");

  /** A zero top offset. */
  private static final Pattern TOP_ZERO = Pattern.compile("(?<![-\\w])top\\s*:\\s*0\\s*;");

  /**
   * Asserts that the bare {@code header} rules of {@code styles.css} keep the header sticky at the
   * top edge at every breakpoint.
   *
   * @throws IOException if the stylesheet cannot be read
   * @throws URISyntaxException if the classpath root cannot be resolved
   */
  @Test
  void theHeaderIsStickyAtTheTop() throws IOException, URISyntaxException {
    Path styles = CssSources.mainResources().resolve("static/css/kernel/styles.css");
    String css = CssSources.stripComments(Files.readString(styles, StandardCharsets.UTF_8));
    List<String> headerRules = new ArrayList<>();
    Matcher rule = RULE.matcher(css);
    while (rule.find()) {
      if ("header".equals(rule.group(1).strip())) {
        headerRules.add(rule.group(2));
      }
    }
    assertThat(headerRules)
        .as("REQ-UI-026: a bare `header` rule makes the header sticky at the top edge")
        .anyMatch(body -> STICKY.matcher(body).find() && TOP_ZERO.matcher(body).find());
    assertThat(headerRules)
        .as("REQ-UI-026: no bare `header` rule overrides its position or its top offset")
        .allMatch(body ->
            !POSITION.matcher(body).find()
                || STICKY.matcher(body).find() && TOP_ZERO.matcher(body).find());
  }

  /**
   * Asserts that no stylesheet sets a scroll-container overflow on {@code html} or {@code body}.
   *
   * @throws IOException if a stylesheet cannot be read
   * @throws URISyntaxException if the classpath root cannot be resolved
   */
  @Test
  void noStylesheetMakesTheRootOrBodyAScrollContainer() throws IOException, URISyntaxException {
    List<Path> sheets = CssSources.files(CssSources.mainResources().resolve("static/css"), ".css");
    List<String> offenders = new ArrayList<>();
    for (Path sheet : sheets) {
      String css = CssSources.stripComments(Files.readString(sheet, StandardCharsets.UTF_8));
      Matcher rule = RULE.matcher(css);
      while (rule.find()) {
        boolean namesRootOrBody =
            Arrays.stream(rule.group(1).split(","))
                .map(String::strip)
                .anyMatch(selector -> "html".equals(selector) || "body".equals(selector));
        if (namesRootOrBody && SCROLL_CONTAINER.matcher(rule.group(2)).find()) {
          offenders.add(sheet.getFileName() + ": " + rule.group(1).strip());
        }
      }
    }
    assertThat(sheets).as("the stylesheets are still there to be checked").hasSizeGreaterThan(20);
    assertThat(offenders)
        .as("REQ-UI-026: html and body clip overflow with `clip`, never hidden, auto or scroll")
        .isEmpty();
  }
}
