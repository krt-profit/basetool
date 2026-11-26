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

package de.greluc.krt.profit.basetool.frontend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the edge's maintenance page sets its text in Lato, the design system's one typeface
 * (REQ-OPS-061): every font it loads lies in its own font folder, which the edge serves while the
 * applications are down, and is the frontend's file byte for byte, so the two copies cannot drift
 * apart.
 */
class MaintenancePageFontParityTest {

  /** The page the edge serves while the applications are down. */
  private static final Path PAGE =
      RepositoryPaths.resolve("docker/maintenance/static/maintenance.html");

  /** The folder the edge serves the page's fonts from, at {@code /maintenance/fonts/}. */
  private static final Path PAGE_FONTS = RepositoryPaths.resolve("docker/maintenance/static/fonts");

  /** The frontend's own fonts, the source of the page's copies. */
  private static final Path FRONTEND_FONTS = Path.of("src/main/resources/static/fonts");

  /** A font file the page's {@code @font-face} rules load, its file name in group 1. */
  private static final Pattern FONT_URL =
      Pattern.compile("url\\('/maintenance/fonts/([A-Za-z0-9-]+\\.woff2)'\\)");

  /** A {@code font-family} declaration, its value in group 1. */
  private static final Pattern FONT_FAMILY = Pattern.compile("font-family:\\s*([^;]+);");

  /**
   * The page loads at least one font, and each is the frontend's file byte for byte.
   *
   * @throws IOException if a file cannot be read
   */
  @Test
  void everyFontThePageLoadsIsTheFrontendsFile() throws IOException {
    List<String> fonts = new ArrayList<>();
    Matcher url = FONT_URL.matcher(Files.readString(PAGE, StandardCharsets.UTF_8));
    while (url.find()) {
      fonts.add(url.group(1));
    }

    assertThat(fonts).isNotEmpty();
    for (String font : fonts) {
      assertThat(PAGE_FONTS.resolve(font)).exists();
      assertThat(Files.readAllBytes(PAGE_FONTS.resolve(font)))
          .as(font)
          .isEqualTo(Files.readAllBytes(FRONTEND_FONTS.resolve(font)));
    }
    assertThat(PAGE_FONTS.resolve("OFL.txt")).exists();
  }

  /**
   * Every {@code font-family} of the page names Lato, with only the generic fallback.
   *
   * @throws IOException if the page cannot be read
   */
  @Test
  void thePageUsesLatoOnly() throws IOException {
    List<String> families = new ArrayList<>();
    Matcher family = FONT_FAMILY.matcher(Files.readString(PAGE, StandardCharsets.UTF_8));
    while (family.find()) {
      families.add(family.group(1).trim());
    }

    assertThat(families).isNotEmpty().allMatch(value -> value.matches("'Lato'(, sans-serif)?"));
  }
}
