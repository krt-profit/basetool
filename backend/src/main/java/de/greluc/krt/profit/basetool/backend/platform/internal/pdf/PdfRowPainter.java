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

import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.openpdf.text.pdf.ColumnText;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfName;
import org.openpdf.text.pdf.PdfNumber;

/**
 * Lays out and draws the rows of one table into the page flow: the cell fills and borders as layout
 * artifacts, each cell's text as its own {@code TH} or {@code TD} element in a {@code TR}.
 *
 * <p>A row that does not fit the rest of the page starts on the next one; a row taller than a page
 * continues there, every cell inside its own element. Line spacing equals the font size, as in an
 * OpenPDF table cell.
 */
public final class PdfRowPainter {

  /** Owner of the table attributes {@code Scope} and {@code ColSpan} (ISO 32000-2 14.8.5.7). */
  private static final PdfName TABLE_OWNER = new PdfName("Table");

  private static final PdfName SCOPE = new PdfName("Scope");

  private static final PdfName COLSPAN = new PdfName("ColSpan");

  private final PdfStructureTree tree;

  private final PdfPageFlow flow;

  private final PdfContentByte canvas;

  /** The x coordinates of the column edges: column {@code i} spans {@code [i]} to {@code [i+1]}. */
  private final float[] edges;

  /**
   * Prepares the painter of one table.
   *
   * @param tree the document's structure tree
   * @param flow the page flow the rows are drawn into
   * @param canvas the canvas the rows are drawn on
   * @param widths the relative column widths; the table spans the content width
   */
  public PdfRowPainter(
      @NotNull PdfStructureTree tree,
      @NotNull PdfPageFlow flow,
      @NotNull PdfContentByte canvas,
      float @NotNull [] widths) {
    this.tree = tree;
    this.flow = flow;
    this.canvas = canvas;
    float total = 0;
    for (float width : widths) {
      total += width;
    }
    edges = new float[widths.length + 1];
    edges[0] = flow.left();
    for (int column = 0; column < widths.length; column++) {
      edges[column + 1] = edges[column] + flow.width() * widths[column] / total;
    }
  }

  /**
   * The height of a row drawn in one piece.
   *
   * @param row the cells of the row
   * @return the height, in points
   */
  public float height(@NotNull List<PdfCell> row) {
    return measure(layout(row), 0, -PdfTextBlock.UNBOUNDED).height();
  }

  /**
   * Draws a row at the cursor as a pagination artifact, such as a table header repeated on a new
   * page.
   *
   * @param row the cells of the row
   */
  public void drawArtifact(@NotNull List<PdfCell> row) {
    List<Run> runs = layout(row);
    float top = flow.cursor();
    float height = measure(runs, top, -PdfTextBlock.UNBOUNDED).height();
    PdfArtifact.PAGINATION.draw(
        canvas,
        () -> {
          paintBoxes(runs, top, height);
          runs.forEach(run -> paintText(run, top, height));
        });
    flow.advance(height);
  }

  /**
   * Draws a row at the cursor as a {@code TR} of the table, breaking the page before the row or
   * inside it as needed.
   *
   * @param table the open table element
   * @param row the cells of the row
   * @param afterPageBreak what is drawn at the top of a page the row moved to, such as the repeated
   *     column headers
   */
  public void draw(
      @NotNull PdfStructureTree.Element table,
      @NotNull List<PdfCell> row,
      @NotNull Runnable afterPageBreak) {
    List<Run> runs = layout(row);
    float fullHeight = measure(runs, 0, -PdfTextBlock.UNBOUNDED).height();
    if (fullHeight > flow.available() && fullHeight <= flow.capacity() && !flow.atPageStart()) {
      flow.newPage();
      afterPageBreak.run();
    }
    PdfStructureTree.Element tableRow = tree.group(table, PdfName.TABLEROW);
    List<PdfStructureTree.Element> cells = new ArrayList<>(runs.size());
    for (Run run : runs) {
      cells.add(cellElement(tableRow, run.cell));
    }
    boolean continued = false;
    while (true) {
      float top = flow.cursor();
      Segment segment =
          !continued && fullHeight <= flow.available()
              ? new Segment(fullHeight, true)
              : measure(runs, top, flow.bottom());
      float height = segment.complete() ? segment.height() : flow.available();
      PdfArtifact.LAYOUT.draw(canvas, () -> paintBoxes(runs, top, height));
      boolean progressed = false;
      for (int index = 0; index < runs.size(); index++) {
        Run run = runs.get(index);
        if (run.pending) {
          tree.beginContent(canvas, cells.get(index));
          paintText(run, top, height);
          canvas.endMarkedContentSequence();
          progressed |= run.column.getLinesWritten() > 0;
        }
      }
      flow.moveTo(top - height);
      if (segment.complete()) {
        break;
      }
      if (continued && !progressed) {
        throw new IllegalStateException("A line of the table row is taller than a page");
      }
      flow.newPage();
      afterPageBreak.run();
      continued = true;
    }
    cells.forEach(tree::end);
    tree.end(tableRow);
  }

  private @NotNull PdfStructureTree.Element cellElement(
      @NotNull PdfStructureTree.Element tableRow, @NotNull PdfCell cell) {
    PdfStructureTree.Element element = tree.content(tableRow, cell.role());
    if (cell.scope() != null) {
      element.attribute(TABLE_OWNER, SCOPE, cell.scope());
    }
    if (cell.colspan() > 1) {
      element.attribute(TABLE_OWNER, COLSPAN, new PdfNumber(cell.colspan()));
    }
    return element;
  }

  private @NotNull List<Run> layout(@NotNull List<PdfCell> row) {
    List<Run> runs = new ArrayList<>(row.size());
    int column = 0;
    for (PdfCell cell : row) {
      int end = column + cell.colspan();
      if (end >= edges.length) {
        throw new IllegalArgumentException("The row's cells span more columns than the table has");
      }
      runs.add(new Run(cell, edges[column], edges[end], column(cell)));
      column = end;
    }
    if (column != edges.length - 1) {
      throw new IllegalArgumentException("The row's cells span fewer columns than the table has");
    }
    return runs;
  }

  private @NotNull ColumnText column(@NotNull PdfCell cell) {
    ColumnText column = new ColumnText(canvas);
    column.addText(cell.phrase());
    column.setAlignment(cell.alignment());
    column.setLeading(0, 1);
    return column;
  }

  /**
   * Measures what of each pending cell fits between the row top and a lower limit.
   *
   * @param runs the cells of the row
   * @param top the top edge of the row on this page
   * @param limit the lowest y the cells may reach
   * @return the height the row needs, at least every cell's padding, and whether every cell ends
   *     within it
   */
  private @NotNull Segment measure(@NotNull List<Run> runs, float top, float limit) {
    float height = 0;
    boolean complete = true;
    for (Run run : runs) {
      PdfCellFrame frame = run.cell.frame();
      height = Math.max(height, frame.getPaddingTop() + frame.getPaddingBottom());
      if (!run.pending) {
        continue;
      }
      ColumnText simulation = ColumnText.duplicate(run.column);
      float textTop = top - frame.getPaddingTop();
      simulation.setSimpleColumn(
          run.left + frame.getPaddingLeft(),
          limit + frame.getPaddingBottom(),
          run.right - frame.getPaddingRight(),
          textTop);
      int status = simulation.go(true);
      complete &= !ColumnText.hasMoreText(status);
      height =
          Math.max(
              height,
              frame.getPaddingTop() + textTop - simulation.getYLine() + frame.getPaddingBottom());
    }
    return new Segment(height, complete);
  }

  private void paintBoxes(@NotNull List<Run> runs, float top, float height) {
    float bottom = top - height;
    for (Run run : runs) {
      float width = run.right - run.left;
      canvas.setColorFill(run.cell.background());
      canvas.rectangle(run.left, bottom, width, height);
      canvas.fill();
      run.cell.frame().stroke(canvas, run.left, bottom, width, height);
    }
  }

  private void paintText(@NotNull Run run, float top, float height) {
    PdfCellFrame frame = run.cell.frame();
    run.column.setSimpleColumn(
        run.left + frame.getPaddingLeft(),
        top - height + frame.getPaddingBottom(),
        run.right - frame.getPaddingRight(),
        top - frame.getPaddingTop());
    run.pending = ColumnText.hasMoreText(run.column.go());
  }

  /**
   * What of a row fits on one page.
   *
   * @param height the height the row's cells need, in points
   * @param complete whether every cell ends within that height
   */
  private record Segment(float height, boolean complete) {}

  /** One cell on its way through the row: its column of text and whether text is left over. */
  private static final class Run {

    private final PdfCell cell;

    private final float left;

    private final float right;

    private final ColumnText column;

    private boolean pending;

    private Run(@NotNull PdfCell cell, float left, float right, @NotNull ColumnText column) {
      this.cell = cell;
      this.left = left;
      this.right = right;
      this.column = column;
      this.pending = !cell.phrase().getContent().isEmpty();
    }
  }
}
