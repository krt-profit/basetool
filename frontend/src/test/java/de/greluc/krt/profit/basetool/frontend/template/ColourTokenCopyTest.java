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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Build-time check that no stylesheet writes out a colour token's value by hand: an opaque copy is
 * {@code var(--color-x)}, an alpha variant is {@code color-mix(in srgb, var(--color-x) N%,
 * transparent)} (REQ-UI-001). The colour tokens are the {@code --color-*} hex declarations in
 * {@code styles.css}; their own declarations are the only place the values may appear.
 */
class ColourTokenCopyTest {

  private static final Pattern TOKEN =
      Pattern.compile("(--color-[\\w-]+)\\s*:\\s*#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})\\s*;");

  private static final Pattern DECLARATION =
      Pattern.compile("(?<![\\w-])(--[\\w-]+|[a-zA-Z-]+)\\s*:\\s*([^;{}]+)(?=[;}])");

  private static final Pattern RGB =
      Pattern.compile("rgba?\\(\\s*(\\d{1,3})[\\s,]+(\\d{1,3})[\\s,]+(\\d{1,3})[^)]*\\)");

  private static final Pattern HEX =
      Pattern.compile("#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})(?![0-9a-zA-Z_-])");

  private static final Pattern SINGLE_HEX = Pattern.compile("#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})\\s*");

  /**
   * The outcome of one scan.
   *
   * @param tokens the number of colour tokens found
   * @param files the number of stylesheets read
   * @param offenders one line per hand-written copy of a token's value
   */
  record Scan(int tokens, int files, @NotNull List<String> offenders) {}

  /**
   * Asserts that no shipped stylesheet repeats a colour token's value outside its declaration.
   *
   * @throws IOException if a stylesheet cannot be read
   * @throws URISyntaxException if the classpath root cannot be resolved
   */
  @Test
  void noStylesheetCopiesAColourToken() throws IOException, URISyntaxException {
    Scan scan = scan(CssSources.mainResources());
    assertThat(scan.tokens()).as("colour tokens are found at all").isGreaterThan(20);
    assertThat(scan.files()).as("the stylesheets are read at all").isGreaterThan(80);
    assertThat(scan.offenders())
        .as("REQ-UI-001: a colour token's value is written through the token")
        .isEmpty();
  }

  /**
   * Asserts that the scan reports a planted alpha copy and a planted hex copy, and leaves an
   * unrelated colour, a {@code color-mix()} and the token declarations alone.
   *
   * @throws IOException if a fixture cannot be read
   * @throws URISyntaxException if the fixture root cannot be resolved
   */
  @Test
  void aPlantedCopyIsReported() throws IOException, URISyntaxException {
    Scan scan = scan(CssSources.fixture("colour-copy"));
    assertThat(scan.offenders())
        .containsExactlyInAnyOrder(
            "static/css/pages/planted.css: color: rgba(231, 126, 35, 0.2) copies --color-primary",
            "static/css/pages/planted.css: background: #FFF copies --color-white");
  }

  /**
   * Scans the stylesheets of one resources tree.
   *
   * @param resources a directory holding {@code static/css/styles.css}
   * @return the counts and the offenders
   * @throws IOException if a stylesheet cannot be read
   */
  static @NotNull Scan scan(@NotNull Path resources) throws IOException {
    Path cssRoot = resources.resolve("static/css");
    Map<String, String> tokenByRgb = new TreeMap<>();
    Matcher token = TOKEN.matcher(CssSources.stripComments(read(cssRoot.resolve("styles.css"))));
    while (token.find()) {
      tokenByRgb.putIfAbsent(normalise(token.group(2)), token.group(1));
    }
    List<Path> sheets = CssSources.files(cssRoot, ".css");
    List<String> offenders = new ArrayList<>();
    for (Path sheet : sheets) {
      String path = resources.relativize(sheet).toString().replace('\\', '/');
      Matcher declaration = DECLARATION.matcher(CssSources.stripComments(read(sheet)));
      while (declaration.find()) {
        String property = declaration.group(1);
        String value = declaration.group(2).strip();
        if (property.startsWith("--color-") && SINGLE_HEX.matcher(value).matches()) {
          continue;
        }
        report(path, property, RGB.matcher(value), tokenByRgb, offenders, true);
        report(path, property, HEX.matcher(value), tokenByRgb, offenders, false);
      }
    }
    return new Scan(tokenByRgb.size(), sheets.size(), offenders);
  }

  private static void report(
      String path,
      String property,
      Matcher literal,
      Map<String, String> tokenByRgb,
      List<String> offenders,
      boolean functional) {
    while (literal.find()) {
      String rgb =
          functional
              ? rgbOf(literal.group(1), literal.group(2), literal.group(3))
              : normalise(literal.group(1));
      String name = rgb == null ? null : tokenByRgb.get(rgb);
      if (name != null) {
        offenders.add(path + ": " + property + ": " + literal.group() + " copies " + name);
      }
    }
  }

  private static @Nullable String rgbOf(String red, String green, String blue) {
    int r = Integer.parseInt(red);
    int g = Integer.parseInt(green);
    int b = Integer.parseInt(blue);
    if (r > 255 || g > 255 || b > 255) {
      return null;
    }
    return String.format(Locale.ROOT, "%02x%02x%02x", r, g, b);
  }

  private static String normalise(String hex) {
    String lower = hex.toLowerCase(Locale.ROOT);
    if (lower.length() == 3) {
      StringBuilder doubled = new StringBuilder();
      for (char c : lower.toCharArray()) {
        doubled.append(c).append(c);
      }
      return doubled.toString();
    }
    return lower;
  }

  private static String read(Path file) throws IOException {
    return Files.readString(file, StandardCharsets.UTF_8);
  }
}
