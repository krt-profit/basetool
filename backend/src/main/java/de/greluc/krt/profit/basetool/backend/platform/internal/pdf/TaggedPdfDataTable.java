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
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.PdfDataTable;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.PdfKeyValue;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.PdfTableCell;
import java.awt.Color;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.openpdf.text.Chunk;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfName;

/**
 * The tagged {@link PdfDataTable}: a {@code Table} element whose first {@code TR} holds the column
 * headers ({@code TH}, scope column) and whose body rows hold {@code TD} cells, each row drawn and
 * written as soon as it is added (REQ-API-029).
 *
 * <p>On every further page the column headers are repeated as a pagination artifact. Body rows
 * alternate their background; a detail row shares the background of the row above it.
 */
public final class TaggedPdfDataTable implements PdfDataTable {

  private static final float HEADER_FONT_SIZE = 9f;

  private static final float BODY_FONT_SIZE = 9f;

  private static final float DETAIL_FONT_SIZE = 8f;

  private static final float EMPTY_FONT_SIZE = 10f;

  /** Space between two entries of a detail row. */
  private static final String DETAIL_SEPARATOR = "      ";

  /** Height of a one-line body row, the room a table needs below its header on the same page. */
  private static final float ONE_LINE_ROW =
      PdfCellFrame.BODY.getPaddingTop() + BODY_FONT_SIZE + PdfCellFrame.BODY.getPaddingBottom();

  private final PdfStructureTree tree;

  private final PdfRowPainter painter;

  private final PdfStructureTree.Element table;

  private final List<PdfCell> header;

  private final Runnable onClose;

  private boolean alternate;

  private Color lastBackground = KrtPdfTheme.COLOR_DARK_GRAY;

  private boolean closed;

  /**
   * Starts the table at the cursor with its header row.
   *
   * @param tree the document's structure tree
   * @param flow the page flow the table is drawn into
   * @param canvas the canvas the table is drawn on
   * @param widths the relative column widths
   * @param headers the column headers, one per width
   * @param onClose told when the table is closed
   */
  TaggedPdfDataTable(
      @NotNull PdfStructureTree tree,
      @NotNull PdfPageFlow flow,
      @NotNull PdfContentByte canvas,
      float @NotNull [] widths,
      @NotNull List<String> headers,
      @NotNull Runnable onClose) {
    if (widths.length != headers.size()) {
      throw new IllegalArgumentException("A data table needs one header per column");
    }
    this.tree = tree;
    this.painter = new PdfRowPainter(tree, flow, canvas, widths);
    this.onClose = onClose;
    this.header =
        headers.stream()
            .map(text ->
                PdfCell.header(
                    new Phrase(text, KrtPdfTheme.bold(HEADER_FONT_SIZE, KrtPdfTheme.COLOR_WHITE)),
                    KrtPdfTheme.COLOR_SURFACE_INPUT,
                    PdfCellFrame.HEADER,
                    PdfCell.SCOPE_COLUMN))
            .toList();
    if (painter.height(header) + ONE_LINE_ROW > flow.available() && !flow.atPageStart()) {
      flow.newPage();
    }
    this.table = tree.group(tree.document(), PdfName.TABLE);
    painter.draw(table, header, this::repeatHeader);
  }

  @Override
  public void row(@NotNull List<PdfTableCell> cells) {
    requireOpen();
    if (cells.size() != header.size()) {
      throw new IllegalArgumentException("A body row needs one cell per column");
    }
    Color background = KrtPdfTheme.rowBackground(alternate);
    alternate = !alternate;
    lastBackground = background;
    List<PdfCell> row =
        cells.stream()
            .map(cell ->
                PdfCell.data(
                    new Phrase(
                        cell.text(), KrtPdfTheme.regular(BODY_FONT_SIZE, KrtPdfTheme.COLOR_WHITE)),
                    cell.centered() ? Element.ALIGN_CENTER : Element.ALIGN_LEFT,
                    1,
                    background,
                    PdfCellFrame.BODY))
            .toList();
    painter.draw(table, row, this::repeatHeader);
  }

  @Override
  public void detailRow(@NotNull List<PdfKeyValue> details) {
    requireOpen();
    Phrase phrase = new Phrase();
    for (PdfKeyValue detail : details) {
      if (detail.value().isEmpty()) {
        continue;
      }
      if (!phrase.isEmpty()) {
        phrase.add(new Chunk(DETAIL_SEPARATOR, detailFont()));
      }
      phrase.add(
          new Chunk(
              detail.label() + ": ", KrtPdfTheme.bold(DETAIL_FONT_SIZE, KrtPdfTheme.COLOR_LABEL)));
      phrase.add(new Chunk(detail.value(), detailFont()));
    }
    if (phrase.isEmpty()) {
      return;
    }
    painter.draw(
        table,
        List.of(
            PdfCell.data(
                phrase, Element.ALIGN_LEFT, header.size(), lastBackground, PdfCellFrame.DETAIL)),
        this::repeatHeader);
  }

  @Override
  public void emptyRow(@NotNull String text) {
    requireOpen();
    painter.draw(
        table,
        List.of(
            PdfCell.data(
                new Phrase(text, KrtPdfTheme.italic(EMPTY_FONT_SIZE, KrtPdfTheme.COLOR_LIGHT_GRAY)),
                Element.ALIGN_LEFT,
                header.size(),
                KrtPdfTheme.COLOR_DARK_GRAY,
                PdfCellFrame.EMPTY)),
        this::repeatHeader);
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    tree.end(table);
    onClose.run();
  }

  private void repeatHeader() {
    painter.drawArtifact(header);
  }

  private void requireOpen() {
    if (closed) {
      throw new IllegalStateException("The data table is closed");
    }
  }

  private static @NotNull Font detailFont() {
    return KrtPdfTheme.regular(DETAIL_FONT_SIZE, KrtPdfTheme.COLOR_LIGHT_GRAY);
  }
}
