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

package de.greluc.krt.profit.basetool.backend.platform.internal.pdf;

import de.greluc.krt.profit.basetool.backend.platform.api.pdf.KrtPdfTheme;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.PdfContentByte;

/**
 * The padding and border of a table cell in the KRT design. Padding is in points; the hairline
 * sides are a {@link Rectangle} border mask, stroked on the cell edges.
 */
@RequiredArgsConstructor
@Getter
public enum PdfCellFrame {

  /** A key or value cell of a key/value table: no border. */
  PLAIN(6f, 6f, 6f, 6f, Rectangle.NO_BORDER, false),

  /** A column header: hairline box with the orange accent line along the bottom (REQ-UI-002). */
  HEADER(7f, 7f, 7f, 7f, Rectangle.BOX, true),

  /** A body cell of a data table: hairline box. */
  BODY(6f, 6f, 6f, 6f, Rectangle.BOX, false),

  /** The full-width empty-state row of a data table: hairline box. */
  EMPTY(8f, 8f, 8f, 8f, Rectangle.BOX, false),

  /** The indented full-width detail row under a body row: hairline box open at the top. */
  DETAIL(0f, 6f, 6f, 18f, Rectangle.LEFT | Rectangle.RIGHT | Rectangle.BOTTOM, false);

  /** Width of the neutral grid lines, in points. */
  private static final float HAIRLINE_WIDTH = 0.3f;

  /** Width of the orange accent line under a column header, in points. */
  private static final float ACCENT_WIDTH = 1.2f;

  /** Space between the top edge and the text. */
  private final float paddingTop;

  /** Space between the text and the right edge. */
  private final float paddingRight;

  /** Space between the text and the bottom edge. */
  private final float paddingBottom;

  /** Space between the left edge and the text. */
  private final float paddingLeft;

  /** The sides stroked as hairline, a mask of {@link Rectangle} side constants. */
  private final int hairlineSides;

  /** Whether the orange accent line runs along the bottom edge. */
  private final boolean accent;

  /**
   * Strokes this frame's border around a cell; the caller marks it as artifact.
   *
   * @param canvas the canvas to draw on
   * @param x the left edge
   * @param y the bottom edge
   * @param width the cell width
   * @param height the cell height
   */
  public void stroke(@NotNull PdfContentByte canvas, float x, float y, float width, float height) {
    if (hairlineSides != Rectangle.NO_BORDER) {
      canvas.setColorStroke(KrtPdfTheme.COLOR_HAIRLINE);
      canvas.setLineWidth(HAIRLINE_WIDTH);
      side(canvas, Rectangle.TOP, x, y + height, x + width, y + height);
      side(canvas, Rectangle.RIGHT, x + width, y + height, x + width, y);
      side(canvas, Rectangle.BOTTOM, x, y, x + width, y);
      side(canvas, Rectangle.LEFT, x, y, x, y + height);
      canvas.stroke();
    }
    if (accent) {
      canvas.setColorStroke(KrtPdfTheme.COLOR_ORANGE);
      canvas.setLineWidth(ACCENT_WIDTH);
      canvas.moveTo(x, y);
      canvas.lineTo(x + width, y);
      canvas.stroke();
    }
  }

  private void side(
      @NotNull PdfContentByte canvas, int side, float fromX, float fromY, float toX, float toY) {
    if ((hairlineSides & side) != 0) {
      canvas.moveTo(fromX, fromY);
      canvas.lineTo(toX, toY);
    }
  }
}
