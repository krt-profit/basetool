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

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.ColumnText;
import org.openpdf.text.pdf.PdfContentByte;

/**
 * Draws one tagged text block, a heading or a paragraph, into the page flow as a single structure
 * element; a block longer than the rest of the page continues on the next one inside the same
 * element.
 */
@RequiredArgsConstructor
public final class PdfTextBlock {

  /** Half-height of the column a block is measured in; no block reaches it. */
  public static final float UNBOUNDED = 1.0e6f;

  private final PdfStructureTree tree;

  private final PdfPageFlow flow;

  private final PdfContentByte canvas;

  /**
   * Draws the text at the cursor and moves the cursor below it.
   *
   * @param text the text of the block
   * @param style how the block is set
   */
  public void draw(@NotNull String text, @NotNull PdfTextStyle style) {
    if (!flow.atPageStart()) {
      flow.advance(style.spaceBefore());
    }
    ColumnText column = column(style.phrase(text), style);
    if (height(column) + style.keepWithNext() > flow.available() && !flow.atPageStart()) {
      flow.newPage();
    }
    PdfStructureTree.Element element = tree.content(tree.document(), style.type());
    while (true) {
      column.setSimpleColumn(flow.left(), flow.bottom(), flow.right(), flow.cursor());
      tree.beginContent(canvas, element);
      final int status = column.go();
      canvas.endMarkedContentSequence();
      if (column.getLinesWritten() == 0 && flow.atPageStart()) {
        throw new IllegalStateException("A line of the text block is taller than a page");
      }
      flow.moveTo(column.getYLine());
      if (!ColumnText.hasMoreText(status)) {
        break;
      }
      flow.newPage();
    }
    tree.end(element);
    flow.advance(style.spaceAfter());
  }

  private @NotNull ColumnText column(@NotNull Phrase phrase, @NotNull PdfTextStyle style) {
    ColumnText column = new ColumnText(canvas);
    column.addText(phrase);
    column.setAlignment(style.alignment());
    column.setLeading(0, style.leading());
    return column;
  }

  private float height(@NotNull ColumnText column) {
    ColumnText simulation = ColumnText.duplicate(column);
    simulation.setSimpleColumn(flow.left(), -UNBOUNDED, flow.right(), 0);
    simulation.go(true);
    return -simulation.getYLine();
  }
}
