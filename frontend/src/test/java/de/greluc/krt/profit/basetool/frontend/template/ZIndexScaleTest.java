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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

/**
 * Build-time check of the z-index scale (REQ-UI-001): a stacking layer that competes across the
 * page is a {@code --z-*} token on {@code :root} in {@code styles.css}, declared in ascending
 * order, and every {@code z-index} is such a token, {@code auto} or a literal below {@link
 * #LOCAL_LIMIT} that only orders the parts of one component.
 */
class ZIndexScaleTest {

  /** A literal z-index must stay below this value; anything higher is a page layer and a token. */
  static final int LOCAL_LIMIT = 50;

  /** The scale from bottom to top; reordering, adding or removing a layer changes this list. */
  private static final List<String> SCALE =
      List.of(
          "--z-popover",
          "--z-popover-raised",
          "--z-dropdown",
          "--z-sticky-head",
          "--z-chart-scrollbar",
          "--z-footer",
          "--z-header",
          "--z-header-control",
          "--z-floating",
          "--z-floating-raised",
          "--z-drawer-scrim",
          "--z-drawer",
          "--z-tabbar",
          "--z-modal",
          "--z-hint",
          "--z-status-pill",
          "--z-toast",
          "--z-confirm");

  private static final Pattern TOKEN = Pattern.compile("(--z-[\\w-]+)\\s*:\\s*(-?\\d+)\\s*;");

  private static final Pattern Z_INDEX = Pattern.compile("(?<![\\w-])z-index\\s*:\\s*([^;}]+)");

  private static final Pattern TOKEN_USE = Pattern.compile("var\\(\\s*(--z-[\\w-]+)\\s*\\)");

  private static final Pattern LITERAL = Pattern.compile("-?\\d+");

  /**
   * The outcome of one scan.
   *
   * @param scale the scale tokens with their values, in declaration order
   * @param declarations the number of {@code z-index} declarations read
   * @param offenders one line per broken rule
   */
  record Scan(
      @NotNull Map<String, Integer> scale, int declarations, @NotNull List<String> offenders) {}

  /**
   * Asserts the pinned scale order and that every shipped {@code z-index} keeps to the scale.
   *
   * @throws IOException if a stylesheet cannot be read
   * @throws URISyntaxException if the classpath root cannot be resolved
   */
  @Test
  void everyZIndexKeepsToTheScale() throws IOException, URISyntaxException {
    Scan scan = scan(CssSources.mainResources());
    assertThat(scan.declarations()).as("z-index declarations are read at all").isGreaterThan(40);
    assertThat(scan.scale().keySet())
        .as("REQ-UI-001: the stacking layers, bottom to top")
        .containsExactlyElementsOf(SCALE);
    assertThat(scan.offenders())
        .as("REQ-UI-001: a page-level z-index is a --z-* token, a literal stays local")
        .isEmpty();
  }

  /**
   * Asserts that the scan reports a planted magic number, an undeclared token and a scale declared
   * out of order, and leaves a token, a local literal and {@code auto} alone.
   *
   * @throws IOException if a fixture cannot be read
   * @throws URISyntaxException if the fixture root cannot be resolved
   */
  @Test
  void aPlantedViolationIsReported() throws IOException, URISyntaxException {
    Scan scan = scan(CssSources.fixture("z-index"));
    assertThat(scan.offenders())
        .containsExactlyInAnyOrder(
            "static/css/styles.css: --z-high (40) is not above --z-low (50)",
            "static/css/pages/planted.css: z-index: 1500",
            "static/css/pages/planted.css: z-index: var(--z-missing)");
  }

  /**
   * Scans the stylesheets of one resources tree.
   *
   * @param resources a directory holding {@code static/css/styles.css}
   * @return the scale, the declaration count and the offenders
   * @throws IOException if a stylesheet cannot be read
   */
  static @NotNull Scan scan(@NotNull Path resources) throws IOException {
    Path cssRoot = resources.resolve("static/css");
    List<String> offenders = new ArrayList<>();
    Map<String, Integer> scale = new LinkedHashMap<>();
    Matcher token = TOKEN.matcher(CssSources.stripComments(read(cssRoot.resolve("styles.css"))));
    String previous = null;
    while (token.find()) {
      int value = Integer.parseInt(token.group(2));
      if (previous != null && value <= scale.get(previous)) {
        offenders.add(
            "static/css/styles.css: "
                + token.group(1)
                + " ("
                + value
                + ") is not above "
                + previous
                + " ("
                + scale.get(previous)
                + ")");
      }
      scale.put(token.group(1), value);
      previous = token.group(1);
    }
    int declarations = 0;
    for (Path sheet : CssSources.files(cssRoot, ".css")) {
      String path = resources.relativize(sheet).toString().replace('\\', '/');
      Matcher zIndex = Z_INDEX.matcher(CssSources.stripComments(read(sheet)));
      while (zIndex.find()) {
        declarations++;
        String value = zIndex.group(1).strip();
        if (!allowed(value, scale)) {
          offenders.add(path + ": z-index: " + value);
        }
      }
    }
    return new Scan(scale, declarations, offenders);
  }

  private static boolean allowed(String value, Map<String, Integer> scale) {
    if ("auto".equals(value)) {
      return true;
    }
    if (LITERAL.matcher(value).matches()) {
      return Math.abs(Integer.parseInt(value)) < LOCAL_LIMIT;
    }
    Matcher use = TOKEN_USE.matcher(value);
    return use.matches() && scale.containsKey(use.group(1));
  }

  private static String read(Path file) throws IOException {
    return Files.readString(file, StandardCharsets.UTF_8);
  }
}
