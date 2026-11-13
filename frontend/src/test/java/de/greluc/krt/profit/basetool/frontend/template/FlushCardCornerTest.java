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

import static de.greluc.krt.profit.basetool.frontend.template.CssRules.declares;
import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.template.CssRules.Rule;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Build-time check of the flush-card corner contract (REQ-UI-007): a flush card hands its inner
 * corner radius to the child that fills a corner through {@code --flush-corner-top} and {@code
 * --flush-corner-bottom}, a frameless flush card inside a list panel hands on only the corners it
 * actually sits in, and no corner rule inside a flush card bypasses that with a fixed radius.
 */
class FlushCardCornerTest {

  /** A corner-radius longhand and its value. */
  private static final Pattern CORNER_RADIUS =
      Pattern.compile("(?<![-\\w])border-(top|bottom)-(?:left|right)-radius\\s*:\\s*([^;]+);");

  /** The inner radius of a box with a 1 px frame. */
  private static final String INNER_RADIUS = "calc(var(--radius-box) - 1px)";

  /** The kernel stylesheet's innermost rules, read once. */
  private static List<Rule> rules;

  /**
   * Reads and splits the kernel stylesheet.
   *
   * @throws IOException if the stylesheet cannot be read
   * @throws URISyntaxException if the classpath root cannot be resolved
   */
  @BeforeAll
  static void readStylesheet() throws IOException, URISyntaxException {
    rules = CssRules.read("static/css/kernel/styles.css");
    assertThat(rules).as("the kernel stylesheet still has its rules").hasSizeGreaterThan(500);
  }

  /** Asserts that a flush card hands its inner corner radius to both edges. */
  @Test
  void aFlushCardHandsItsInnerRadiusToBothEdges() {
    assertThat(declarations(".card--flush"))
        .as("REQ-UI-007: `.card--flush` sets both corner properties to its inner radius")
        .anyMatch(body ->
            declares(body, "--flush-corner-top", INNER_RADIUS)
                && declares(body, "--flush-corner-bottom", INNER_RADIUS));
  }

  /**
   * Asserts that a frameless flush card inside a list panel hands on no corner, except the top and
   * bottom corner of the panel it sits in as the first or last visible child.
   */
  @Test
  void aFramelessFlushCardHandsOnOnlyThePanelCornersItSitsIn() {
    assertThat(declarations(".list-panel .card--flush"))
        .as("REQ-UI-007: the frameless flush card in a list panel resets both corner properties")
        .anyMatch(body ->
            declares(body, "--flush-corner-top", "0")
                && declares(body, "--flush-corner-bottom", "0"));
    assertThat(declarations(".list-panel > .card--flush:nth-child(1 of :not([hidden]))"))
        .as("REQ-UI-007: a flush card that opens its list panel takes the panel's top corners")
        .anyMatch(body -> declares(body, "--flush-corner-top", INNER_RADIUS));
    assertThat(declarations(".list-panel > .card--flush:nth-last-child(1 of :not([hidden]))"))
        .as("REQ-UI-007: a flush card that closes its list panel takes the panel's bottom corners")
        .anyMatch(body -> declares(body, "--flush-corner-bottom", INNER_RADIUS));
  }

  /**
   * Asserts that every corner radius set on a flush card's content reads the matching corner
   * property, so a frameless card under a toolbar or above a footer cuts no notch.
   */
  @Test
  void everyCornerInsideAFlushCardReadsTheCornerProperty() {
    List<String> offenders = new ArrayList<>();
    int corners = 0;
    for (Rule rule : rules) {
      if (!rule.selector().contains(".card--flush >")) {
        continue;
      }
      Matcher radius = CORNER_RADIUS.matcher(rule.body());
      while (radius.find()) {
        corners++;
        String expected = "var(--flush-corner-" + radius.group(1) + ")";
        if (!expected.equals(radius.group(2).strip())) {
          offenders.add(rule.selector() + " -> " + radius.group(0).strip());
        }
      }
    }
    assertThat(corners)
        .as("the flush-card corner rules are still there")
        .isGreaterThanOrEqualTo(10);
    assertThat(offenders)
        .as("REQ-UI-007: a corner inside a flush card reads --flush-corner-top/-bottom")
        .isEmpty();
  }

  /**
   * Lists the declaration blocks of the kernel rules whose selector list names a selector exactly.
   *
   * @param selector the selector, with single spaces
   * @return the declaration blocks
   */
  private static @NotNull List<String> declarations(@NotNull String selector) {
    return CssRules.declarations(rules, selector);
  }
}
