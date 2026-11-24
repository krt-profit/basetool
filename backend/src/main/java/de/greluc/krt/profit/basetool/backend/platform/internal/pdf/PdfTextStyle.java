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

import org.jetbrains.annotations.NotNull;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.PdfName;

/**
 * How one kind of text block is set: its structure type, font, line spacing, alignment and the
 * space around it. Distances are in points.
 *
 * @param type the PDF 2.0 structure type, such as {@code H1} or {@code P}
 * @param font the font the text is set in
 * @param leading the line spacing as a multiple of the font size
 * @param alignment the horizontal alignment, an {@link Element} {@code ALIGN_*} constant
 * @param spaceBefore the space above the block, left out at the top of a page
 * @param spaceAfter the space below the block
 * @param keepWithNext the room the block needs below itself on the same page, so a heading does not
 *     end a page
 */
public record PdfTextStyle(
    @NotNull PdfName type,
    @NotNull Font font,
    float leading,
    int alignment,
    float spaceBefore,
    float spaceAfter,
    float keepWithNext) {

  /** Paragraph line spacing of the KRT documents, as a multiple of the font size. */
  public static final float PARAGRAPH_LEADING = 1.5f;

  /**
   * The text set in this style.
   *
   * @param text the text
   * @return the phrase in this style's font
   */
  @NotNull
  public Phrase phrase(@NotNull String text) {
    return new Phrase(text, font);
  }
}
