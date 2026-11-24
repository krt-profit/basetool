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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import de.greluc.krt.profit.basetool.backend.platform.api.pdf.KrtPdfTheme;
import java.awt.Color;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.openpdf.text.pdf.BaseFont;
import org.openpdf.text.pdf.PdfContentByte;

/**
 * Draws the per-account balance-over-time step chart for the management three-month report
 * (REQ-BANK-015) onto the canvas of a tagged {@code Figure} (REQ-API-029).
 */
public final class BankBalanceChart {

  /** Chart width in points — matches the A4 content width (595.28 − 2×40 margins). */
  public static final float WIDTH = 515f;

  /** Chart height in points. */
  public static final float HEIGHT = 130f;

  private static final float MARGIN_LEFT = 60f;
  private static final float MARGIN_RIGHT = 10f;
  private static final float MARGIN_TOP = 10f;
  private static final float MARGIN_BOTTOM = 18f;

  private static final Color AXIS = new Color(0x46, 0x46, 0x46);
  private static final Color GRID = new Color(0x30, 0x30, 0x30);
  private static final float LABEL_SIZE = 6f;

  private BankBalanceChart() {}

  /**
   * Draws a bounded chart envelope without retaining the report's posting rows.
   *
   * @param cb the figure canvas, {@link #WIDTH} by {@link #HEIGHT} with the origin at its
   *     bottom-left corner
   * @param envelope the exact endpoints and per-display-column extrema
   * @param from chart start
   * @param to chart end
   * @param fromLabel localized start label
   * @param toLabel localized end label
   */
  public static void draw(
      @NotNull PdfContentByte cb,
      @NotNull BankBalanceEnvelope envelope,
      @NotNull Instant from,
      @NotNull Instant to,
      @NotNull String fromLabel,
      @NotNull String toLabel) {
    float x0 = MARGIN_LEFT;
    float x1 = WIDTH - MARGIN_RIGHT;
    float y0 = MARGIN_BOTTOM;
    float y1 = HEIGHT - MARGIN_TOP;
    final float plotW = x1 - x0;
    final float plotH = y1 - y0;

    double fromMs = from.toEpochMilli();
    double toMs = to.toEpochMilli();
    final double span = Math.max(1d, toMs - fromMs);

    final List<double[]> vertices = envelope.vertices();
    BigDecimal dataMin = envelope.getMinimum();
    BigDecimal dataMax = envelope.getMaximum();

    double lo = dataMin.doubleValue();
    double hi = dataMax.doubleValue();
    if (hi - lo < 1e-6) {
      lo -= 1;
      hi += 1;
    }
    double pad = (hi - lo) * 0.08;
    final double axisMin = lo - pad;
    final double axisRange = (hi + pad) - axisMin;

    cb.setColorFill(KrtPdfTheme.COLOR_DARK_GRAY);
    cb.rectangle(0, 0, WIDTH, HEIGHT);
    cb.fill();
    cb.setColorStroke(KrtPdfTheme.COLOR_HAIRLINE);
    cb.setLineWidth(0.5f);
    cb.rectangle(0.5f, 0.5f, WIDTH - 1, HEIGHT - 1);
    cb.stroke();

    final float maxLineY = (float) (y0 + (dataMax.doubleValue() - axisMin) / axisRange * plotH);
    final float minLineY = (float) (y0 + (dataMin.doubleValue() - axisMin) / axisRange * plotH);
    cb.setColorStroke(GRID);
    cb.setLineWidth(0.4f);
    cb.moveTo(x0, maxLineY);
    cb.lineTo(x1, maxLineY);
    cb.moveTo(x0, minLineY);
    cb.lineTo(x1, minLineY);
    cb.stroke();

    cb.setColorStroke(AXIS);
    cb.setLineWidth(0.5f);
    cb.moveTo(x0, y0);
    cb.lineTo(x0, y1);
    cb.moveTo(x0, y0);
    cb.lineTo(x1, y0);
    cb.stroke();

    cb.setColorStroke(KrtPdfTheme.COLOR_ORANGE);
    cb.setLineWidth(1.2f);
    for (int i = 0; i < vertices.size(); i++) {
      float px = (float) (x0 + (vertices.get(i)[0] - fromMs) / span * plotW);
      float py = (float) (y0 + (vertices.get(i)[1] - axisMin) / axisRange * plotH);
      if (i == 0) {
        cb.moveTo(px, py);
      } else {
        cb.lineTo(px, py);
      }
    }
    cb.stroke();

    BaseFont font = KrtPdfTheme.regularBaseFont();
    drawText(cb, font, BankPdfFormat.groupedAmount(dataMax), x0 - 3, maxLineY - 2, Align.RIGHT);
    drawText(cb, font, BankPdfFormat.groupedAmount(dataMin), x0 - 3, minLineY - 2, Align.RIGHT);
    drawText(cb, font, fromLabel, x0, y0 - 9, Align.LEFT);
    drawText(cb, font, toLabel, x1, y0 - 9, Align.RIGHT);
  }

  /** Text anchor for {@link #drawText}. */
  private enum Align {
    LEFT,
    RIGHT
  }

  /**
   * Draws a single Lato label at the given anchor, computing right alignment from the font metrics.
   *
   * @param cb the template canvas
   * @param font the Lato base font
   * @param text the label text
   * @param x the anchor x
   * @param y the baseline y
   * @param align the horizontal anchor
   */
  private static void drawText(
      PdfContentByte cb, BaseFont font, String text, float x, float y, Align align) {
    float tx = align == Align.RIGHT ? x - font.getWidthPoint(text, LABEL_SIZE) : x;
    cb.beginText();
    cb.setFontAndSize(font, LABEL_SIZE);
    cb.setColorFill(KrtPdfTheme.COLOR_LIGHT_GRAY);
    cb.setTextMatrix(tx, y);
    cb.showText(text);
    cb.endText();
  }
}
