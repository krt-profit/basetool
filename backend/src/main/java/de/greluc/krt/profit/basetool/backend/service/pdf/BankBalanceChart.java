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

package de.greluc.krt.profit.basetool.backend.service.pdf;

import de.greluc.krt.profit.basetool.backend.model.projection.BankBookingRow;
import java.awt.Color;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.openpdf.text.Image;
import org.openpdf.text.pdf.BaseFont;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfTemplate;
import org.openpdf.text.pdf.PdfWriter;

/**
 * Draws the per-account balance-over-time chart embedded in the management three-month report
 * (REQ-BANK-015, epic #556). The account balance is piecewise constant — it only changes at a
 * booking — so the running balance is rendered as a step line in the DAS KARTELL orange over a dark
 * panel, with the data min/max on the y-axis and the period bounds on the x-axis.
 *
 * <p>Implemented as a {@link PdfTemplate} wrapped in an {@link Image} so the chart flows inline in
 * the document like any other element (OpenPDF has no charting API). All drawing is vector — there
 * is no raster image and the labels use the embedded Lato font, so the report stays crisp at any
 * zoom.
 */
public final class BankBalanceChart {

  /** Chart width in points — matches the A4 content width (595.28 − 2×40 margins). */
  private static final float WIDTH = 515f;

  /** Chart height in points. */
  private static final float HEIGHT = 130f;

  private static final float MARGIN_LEFT = 60f;
  private static final float MARGIN_RIGHT = 10f;
  private static final float MARGIN_TOP = 10f;
  private static final float MARGIN_BOTTOM = 18f;

  private static final Color AXIS = new Color(0x46, 0x46, 0x46);
  private static final Color GRID = new Color(0x30, 0x30, 0x30);
  private static final float LABEL_SIZE = 6f;

  private BankBalanceChart() {}

  /**
   * Renders the running-balance step chart for one account over the report window.
   *
   * @param writer the PDF writer the template is created on
   * @param opening the account balance at {@code from} (the chart's first point)
   * @param rows the account's bookings inside the window, chronological (oldest first)
   * @param from window start (the x-axis left bound)
   * @param to window end (the x-axis right bound)
   * @param fromLabel the {@code from} date rendered under the left of the x-axis
   * @param toLabel the {@code to} date rendered under the right of the x-axis
   * @return the chart as an inline image
   */
  public static @NotNull Image render(
      @NotNull PdfWriter writer,
      @NotNull BigDecimal opening,
      @NotNull List<BankBookingRow> rows,
      @NotNull Instant from,
      @NotNull Instant to,
      @NotNull String fromLabel,
      @NotNull String toLabel) {
    try {
      final PdfTemplate cb = writer.getDirectContent().createTemplate(WIDTH, HEIGHT);

      float x0 = MARGIN_LEFT;
      float x1 = WIDTH - MARGIN_RIGHT;
      float y0 = MARGIN_BOTTOM;
      float y1 = HEIGHT - MARGIN_TOP;
      final float plotW = x1 - x0;
      final float plotH = y1 - y0;

      double fromMs = from.toEpochMilli();
      double toMs = to.toEpochMilli();
      final double span = Math.max(1d, toMs - fromMs);

      List<double[]> vertices = new ArrayList<>();
      vertices.add(new double[] {fromMs, opening.doubleValue()});
      BigDecimal running = opening;
      BigDecimal dataMin = opening;
      BigDecimal dataMax = opening;
      for (BankBookingRow row : rows) {
        double t = clamp(row.createdAt().toEpochMilli(), fromMs, toMs);
        vertices.add(new double[] {t, running.doubleValue()});
        running = running.add(row.amount());
        vertices.add(new double[] {t, running.doubleValue()});
        dataMin = dataMin.min(running);
        dataMax = dataMax.max(running);
      }
      vertices.add(new double[] {toMs, running.doubleValue()});

      double lo = dataMin.doubleValue();
      double hi = dataMax.doubleValue();
      if (hi - lo < 1e-6) {
        lo -= 1;
        hi += 1;
      }
      double pad = (hi - lo) * 0.08;
      final double axisMin = lo - pad;
      final double axisRange = (hi + pad) - axisMin;

      cb.setColorFill(KrtPdfSupport.COLOR_DARK_GRAY);
      cb.rectangle(0, 0, WIDTH, HEIGHT);
      cb.fill();
      cb.setColorStroke(KrtPdfSupport.COLOR_HAIRLINE);
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

      cb.setColorStroke(KrtPdfSupport.COLOR_ORANGE);
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

      BaseFont font = KrtPdfSupport.regularBaseFont();
      drawText(cb, font, BankPdfFormat.groupedAmount(dataMax), x0 - 3, maxLineY - 2, Align.RIGHT);
      drawText(cb, font, BankPdfFormat.groupedAmount(dataMin), x0 - 3, minLineY - 2, Align.RIGHT);
      drawText(cb, font, fromLabel, x0, y0 - 9, Align.LEFT);
      drawText(cb, font, toLabel, x1, y0 - 9, Align.RIGHT);

      return Image.getInstance(cb);
    } catch (Exception e) {
      throw new IllegalStateException("Bank balance chart rendering failed", e);
    }
  }

  /** Text anchor for {@link #drawText}. */
  private enum Align {
    LEFT,
    RIGHT
  }

  /**
   * Draws a single Lato label at the given anchor, computing the offset for right alignment from
   * the font's own metrics (OpenPDF's canvas has no layout engine).
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
    cb.setColorFill(KrtPdfSupport.COLOR_LIGHT_GRAY);
    cb.setTextMatrix(tx, y);
    cb.showText(text);
    cb.endText();
  }

  /**
   * Clamps a timestamp into the chart's window so a boundary booking never plots outside the axes.
   *
   * @param value the epoch-milli timestamp
   * @param min the window start
   * @param max the window end
   * @return the clamped value
   */
  private static double clamp(double value, double min, double max) {
    return Math.max(min, Math.min(max, value));
  }
}
