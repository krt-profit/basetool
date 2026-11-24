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

package de.greluc.krt.profit.basetool.backend.platform.api.pdf;

import java.awt.Color;
import java.io.InputStream;
import org.jetbrains.annotations.NotNull;
import org.openpdf.text.Font;
import org.openpdf.text.pdf.BaseFont;

/**
 * The KRT corporate design of every backend PDF: the colour palette and the embedded Lato fonts
 * (REQ-BANK-017).
 *
 * <p>Orange is an accent only (REQ-UI-002); surfaces and grid lines are neutral. Lato is embedded
 * with {@link BaseFont#WINANSI}, a standard encoding, so every glyph maps to Unicode and the
 * uncompressed content stream stays text-extractable; PDF/A-4 admits no unembedded font.
 */
public final class KrtPdfTheme {

  /** Page background fill, the app's canvas gray ({@code --color-canvas}). */
  public static final Color COLOR_CANVAS = new Color(0x0D, 0x0F, 0x12);

  /** Box background for meta rows and odd table rows ({@code --surface-panel}). */
  public static final Color COLOR_DARK_GRAY = new Color(0x1A, 0x1E, 0x24);

  /**
   * DAS KARTELL primary orange ({@code --color-primary}), used only for titles, headings, header
   * accent lines, the chart line, the top bar and the logo (REQ-UI-002).
   */
  public static final Color COLOR_ORANGE = new Color(0xE7, 0x7E, 0x23);

  /** Default body / data-value text on the dark background (design token {@code --data-fg}). */
  public static final Color COLOR_WHITE = new Color(0xFF, 0xFF, 0xFF);

  /** Muted text — footers, empty-state hints and chart axis labels (Grau 1). */
  public static final Color COLOR_LIGHT_GRAY = new Color(0xD7, 0xDA, 0xDE);

  /**
   * Muted key/meta-label gray (design token {@code --color-gray-2-text} {@code #9AA1AB}). Field
   * labels in the meta and summary blocks are labels, not headings, so they take this neutral gray
   * — never orange.
   */
  public static final Color COLOR_LABEL = new Color(0x9A, 0xA1, 0xAB);

  /**
   * Bar surface, one step darker than the {@link #COLOR_DARK_GRAY} box, used for table-head fills
   * (design token {@code --surface-bar} {@code #14171C}) instead of an orange fill.
   */
  public static final Color COLOR_SURFACE_INPUT = new Color(0x14, 0x17, 0x1C);

  /**
   * Neutral hairline border / grid color (design token Grau 3 {@code #2A3038}). Frames every data
   * cell so the table grid is quiet, with orange reserved for the single header-underline accent.
   */
  public static final Color COLOR_HAIRLINE = new Color(0x2A, 0x30, 0x38);

  /** Alternating (even) table row background, the canvas gray. */
  public static final Color COLOR_TABLE_ROW_ALT = new Color(0x0D, 0x0F, 0x12);

  /** Embedded Lato Regular — loaded once per JVM from the bundled TTF. */
  private static final BaseFont LATO_REGULAR = loadFont("fonts/Lato-Regular.ttf");

  /** Embedded Lato Bold — loaded once per JVM from the bundled TTF. */
  private static final BaseFont LATO_BOLD = loadFont("fonts/Lato-Bold.ttf");

  private KrtPdfTheme() {}

  /**
   * A Lato-Regular font instance.
   *
   * @param size point size
   * @param color text color
   * @return the font
   */
  public static @NotNull Font regular(float size, @NotNull Color color) {
    return new Font(LATO_REGULAR, size, Font.NORMAL, color);
  }

  /**
   * The embedded Lato-Regular {@link BaseFont}, for text drawn straight onto a canvas, such as the
   * axis labels of the three-month report's balance chart.
   *
   * @return the Lato-Regular base font
   */
  public static @NotNull BaseFont regularBaseFont() {
    return LATO_REGULAR;
  }

  /**
   * A Lato-Bold font instance (real bold glyphs, not simulated).
   *
   * @param size point size
   * @param color text color
   * @return the font
   */
  public static @NotNull Font bold(float size, @NotNull Color color) {
    return new Font(LATO_BOLD, size, Font.NORMAL, color);
  }

  /**
   * A Lato-Regular font with the italic style flag (skew-simulated by OpenPDF), used for notes and
   * empty-state hints.
   *
   * @param size point size
   * @param color text color
   * @return the font
   */
  public static @NotNull Font italic(float size, @NotNull Color color) {
    return new Font(LATO_REGULAR, size, Font.ITALIC, color);
  }

  /**
   * The alternating row background for zebra-striped data tables.
   *
   * @param alt {@code true} for the alternate (even) row
   * @return the background color
   */
  public static @NotNull Color rowBackground(boolean alt) {
    return alt ? COLOR_TABLE_ROW_ALT : COLOR_DARK_GRAY;
  }

  /**
   * Loads one bundled TTF as an embedded WinAnsi {@link BaseFont}. A missing font resource is a
   * packaging defect, so it fails at class initialization.
   *
   * @param resource the classpath resource path
   * @return the embedded base font
   */
  private static @NotNull BaseFont loadFont(@NotNull String resource) {
    try (InputStream stream = KrtPdfTheme.class.getClassLoader().getResourceAsStream(resource)) {
      if (stream == null) {
        throw new IllegalStateException("Bundled font missing from classpath: " + resource);
      }
      return BaseFont.createFont(
          resource, BaseFont.WINANSI, BaseFont.EMBEDDED, true, stream.readAllBytes(), null);
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Failed to load bundled font: " + resource, e);
    }
  }
}
