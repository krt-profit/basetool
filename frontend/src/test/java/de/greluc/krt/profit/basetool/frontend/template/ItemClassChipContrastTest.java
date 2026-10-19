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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Checks the item-class chips of {@code styles.css} (REQ-INV-062, REQ-UI-006): each variant takes
 * its colours from the design-system tokens, and its label stays readable on its tinted fill. The
 * classes are read from the backend enum, so a new one fails here until its chip exists.
 */
class ItemClassChipContrastTest {

  /** WCAG AA minimum for normal text. */
  private static final double MIN_CONTRAST = 4.5;

  /** The surfaces a Lager row sits on: the panel (Grau 4) and the row hover (control fill). */
  private static final String[] SURFACES = {"#35393d", "#3f474c"};

  /** The backend enum whose constants each need a chip variant. */
  private static final String SHIP_COMPONENT_CLASS =
      "backend/src/main/java/de/greluc/krt/profit/basetool/backend/catalogue/api/"
          + "ShipComponentClass.java";

  /** An enum constant: an upper-snake identifier opening its constructor arguments. */
  private static final Pattern CONSTANT =
      Pattern.compile("^\\s{2}([A-Z][A-Z0-9_]*)\\(", Pattern.MULTILINE);

  @ParameterizedTest
  @MethodSource("itemClasses")
  void chipUsesTheTokensAndKeepsItsLabelReadable(String itemClass) throws IOException {
    String css = stylesheet();
    String hue = token(css, "--krt-item-class-" + itemClass);
    String text = token(css, "--krt-item-class-" + itemClass + "-text");

    Matcher rule =
        Pattern.compile("\\.chip--item-class-" + itemClass + "\\s*\\{([^}]*)}").matcher(css);
    assertThat(rule.find()).as(".chip--item-class-%s exists", itemClass).isTrue();
    String body = rule.group(1);
    assertThat(body)
        .contains("color: var(--krt-item-class-" + itemClass + "-text)")
        .contains("border-color: var(--krt-item-class-" + itemClass + ")")
        .contains(
            "background-color: color-mix(in srgb, var(--krt-item-class-"
                + itemClass
                + ") 15%, transparent)");

    for (String surface : SURFACES) {
      double[] fill = mix(rgb(hue), rgb(surface), 0.15);
      assertThat(contrast(rgb(text), fill))
          .as("%s label on its fill over %s", itemClass, surface)
          .isGreaterThanOrEqualTo(MIN_CONTRAST);
    }
  }

  /**
   * The CSS suffix of every {@code ShipComponentClass} constant, read from the backend source.
   *
   * @return the lower-cased constant names
   * @throws IOException if the source cannot be read
   */
  static List<String> itemClasses() throws IOException {
    Path file = Path.of("..").resolve(SHIP_COMPONENT_CLASS);
    if (!Files.isRegularFile(file)) {
      file = Path.of(SHIP_COMPONENT_CLASS);
    }
    String source = Files.readString(file, StandardCharsets.UTF_8);
    List<String> out =
        CONSTANT
            .matcher(source.substring(source.indexOf("public enum ShipComponentClass")))
            .results()
            .map(m -> m.group(1).toLowerCase(Locale.ROOT))
            .toList();
    assertThat(out).as("ShipComponentClass constants").isNotEmpty();
    return out;
  }

  /**
   * Reads the kernel stylesheet from the classpath.
   *
   * @return the stylesheet text
   * @throws IOException if it cannot be read
   */
  private static String stylesheet() throws IOException {
    try (InputStream in =
        ItemClassChipContrastTest.class.getResourceAsStream("/static/css/kernel/styles.css")) {
      assertThat(in).as("styles.css on the classpath").isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /**
   * Returns the hex value a custom property is declared with.
   *
   * @param css the stylesheet
   * @param name the custom property
   * @return its {@code #rrggbb} value
   */
  private static String token(String css, String name) {
    Matcher m = Pattern.compile(Pattern.quote(name) + ":\\s*(#[0-9a-fA-F]{6});").matcher(css);
    assertThat(m.find()).as("%s is declared", name).isTrue();
    return m.group(1);
  }

  /**
   * Parses a {@code #rrggbb} colour.
   *
   * @param hex the colour
   * @return its red, green and blue channels in 0..255
   */
  private static double[] rgb(String hex) {
    int v = Integer.parseInt(hex.substring(1), 16);
    return new double[] {(v >> 16) & 0xff, (v >> 8) & 0xff, v & 0xff};
  }

  /**
   * Composites {@code top} at {@code alpha} over {@code bottom}, as {@code color-mix} with {@code
   * transparent} renders on an opaque surface.
   *
   * @param top the tint
   * @param bottom the surface
   * @param alpha the tint's share
   * @return the resulting channels
   */
  private static double[] mix(double[] top, double[] bottom, double alpha) {
    double[] out = new double[3];
    for (int i = 0; i < 3; i++) {
      out[i] = top[i] * alpha + bottom[i] * (1 - alpha);
    }
    return out;
  }

  /**
   * Computes the WCAG contrast ratio of two colours.
   *
   * @param a the first colour
   * @param b the second colour
   * @return the ratio, at least 1
   */
  private static double contrast(double[] a, double[] b) {
    double la = luminance(a);
    double lb = luminance(b);
    return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
  }

  /**
   * Computes the WCAG relative luminance of a colour.
   *
   * @param c the colour's channels in 0..255
   * @return the luminance in 0..1
   */
  private static double luminance(double[] c) {
    double[] lin = new double[3];
    for (int i = 0; i < 3; i++) {
      double s = c[i] / 255.0;
      lin[i] = s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }
    return 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2];
  }
}
