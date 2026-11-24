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

import java.awt.Color;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.openpdf.text.Element;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.PdfName;

/**
 * One cell as the row painter lays it out, draws and tags it.
 *
 * @param phrase the cell text with its fonts
 * @param alignment the horizontal alignment, an {@link Element} {@code ALIGN_*} constant
 * @param colspan the number of columns the cell spans, at least 1
 * @param background the fill behind the cell
 * @param frame the padding and border
 * @param role {@link PdfName#TH} for a header cell, {@link PdfName#TD} for a data cell
 * @param scope the cells a header cell applies to ({@code Column} or {@code Row}); {@code null} for
 *     a data cell
 */
public record PdfCell(
    @NotNull Phrase phrase,
    int alignment,
    int colspan,
    @NotNull Color background,
    @NotNull PdfCellFrame frame,
    @NotNull PdfName role,
    @Nullable PdfName scope) {

  /** Scope of a header cell that heads the cells below it. */
  public static final PdfName SCOPE_COLUMN = new PdfName("Column");

  /** Scope of a header cell that heads the cells beside it. */
  public static final PdfName SCOPE_ROW = new PdfName("Row");

  /**
   * A header cell of one column.
   *
   * @param phrase the cell text
   * @param background the fill
   * @param frame the padding and border
   * @param scope {@link #SCOPE_COLUMN} or {@link #SCOPE_ROW}
   * @return the header cell
   */
  public static @NotNull PdfCell header(
      @NotNull Phrase phrase,
      @NotNull Color background,
      @NotNull PdfCellFrame frame,
      @NotNull PdfName scope) {
    return new PdfCell(phrase, Element.ALIGN_LEFT, 1, background, frame, PdfName.TH, scope);
  }

  /**
   * A data cell.
   *
   * @param phrase the cell text
   * @param alignment the horizontal alignment
   * @param colspan the number of columns spanned
   * @param background the fill
   * @param frame the padding and border
   * @return the data cell
   */
  public static @NotNull PdfCell data(
      @NotNull Phrase phrase,
      int alignment,
      int colspan,
      @NotNull Color background,
      @NotNull PdfCellFrame frame) {
    return new PdfCell(phrase, alignment, colspan, background, frame, PdfName.TD, null);
  }
}
