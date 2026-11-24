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

import de.greluc.krt.profit.basetool.backend.platform.api.pdf.KrtPdfDocument;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.KrtPdfTheme;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.PdfDataTable;
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.PdfKeyValue;
import java.io.OutputStream;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.PdfArray;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfName;
import org.openpdf.text.pdf.PdfTemplate;
import org.openpdf.text.pdf.PdfWriter;

/**
 * The tagged {@link KrtPdfDocument}: every block is drawn at the cursor of a {@link PdfPageFlow}
 * and tagged in the PDF 2.0 namespace through the {@link PdfStructureTree} (REQ-API-029).
 *
 * <p>The title is {@code H1}, headings {@code H2}, notes, notices and the footer {@code P}, tables
 * {@code Table}, figures {@code Figure}; background, rules, cell fills, borders, the logo and
 * repeated table headers are artifacts. The OpenPDF {@link Document} stays private, so no content
 * can bypass the tagging.
 */
public final class TaggedKrtPdfDocument implements KrtPdfDocument {

  private static final PdfTextStyle TITLE =
      new PdfTextStyle(
          PdfName.H1,
          KrtPdfTheme.bold(20, KrtPdfTheme.COLOR_ORANGE),
          PdfTextStyle.PARAGRAPH_LEADING,
          Element.ALIGN_LEFT,
          0f,
          4f,
          0f);

  /** Headings keep room for a table header and its first row below them. */
  private static final PdfTextStyle HEADING =
      new PdfTextStyle(
          PdfName.H2,
          KrtPdfTheme.bold(12, KrtPdfTheme.COLOR_ORANGE),
          PdfTextStyle.PARAGRAPH_LEADING,
          Element.ALIGN_LEFT,
          0f,
          8f,
          48f);

  private static final PdfTextStyle NOTE =
      new PdfTextStyle(
          PdfName.P,
          KrtPdfTheme.italic(8, KrtPdfTheme.COLOR_LIGHT_GRAY),
          PdfTextStyle.PARAGRAPH_LEADING,
          Element.ALIGN_LEFT,
          4f,
          4f,
          0f);

  private static final PdfTextStyle NOTICE =
      new PdfTextStyle(
          PdfName.P,
          KrtPdfTheme.italic(10, KrtPdfTheme.COLOR_LIGHT_GRAY),
          PdfTextStyle.PARAGRAPH_LEADING,
          Element.ALIGN_LEFT,
          0f,
          0f,
          0f);

  private static final PdfTextStyle FOOTER =
      new PdfTextStyle(
          PdfName.P,
          KrtPdfTheme.regular(8, KrtPdfTheme.COLOR_LIGHT_GRAY),
          PdfTextStyle.PARAGRAPH_LEADING,
          Element.ALIGN_CENTER,
          15f,
          0f,
          0f);

  private static final float KEY_FONT_SIZE = 9f;

  private static final float VALUE_FONT_SIZE = 10f;

  /** Relative widths of the label and value columns of a key/value table. */
  private static final float[] KEY_VALUE_WIDTHS = {1f, 2f};

  /** Space below a key/value table, in points. */
  private static final float KEY_VALUE_SPACE_AFTER = 20f;

  /** Space between the title's separator rule and the first block, in points. */
  private static final float TITLE_RULE_SPACE_AFTER = 9f;

  /** Width of the title's orange separator rule, in points. */
  private static final float TITLE_RULE_WIDTH = 0.5f;

  /** The vertical gap between sections, one blank 12 pt line, in points. */
  private static final float SECTION_GAP = 18f;

  private static final PdfName LAYOUT_OWNER = new PdfName("Layout");

  private static final PdfName PLACEMENT = new PdfName("Placement");

  private static final PdfName BLOCK = new PdfName("Block");

  private final Document document;

  private final PdfContentByte canvas;

  private final PdfPageFlow flow;

  private final PdfStructureTree tree;

  private final PdfTextBlock text;

  /** The localized line {@link #footer()} draws. */
  private final String footerLine;

  private @Nullable TaggedPdfDataTable openTable;

  private TaggedKrtPdfDocument(
      @NotNull Document document, @NotNull PdfWriter writer, @NotNull String footerLine) {
    this.document = document;
    this.canvas = writer.getDirectContent();
    this.flow = new PdfPageFlow(document);
    this.tree = new PdfStructureTree(writer);
    this.text = new PdfTextBlock(tree, flow, canvas);
    this.footerLine = footerLine;
  }

  /**
   * Creates and opens a tagged, fully compressed KRT document on A4 — the dark background, accent
   * bar and logo on every page — and sets its title both as document metadata and as the {@code H1}
   * of the first page.
   *
   * @param target the stream receiving the PDF bytes
   * @param title the (already uppercase) document title
   * @param generatedAt the generation instant, written as the metadata's creation date
   * @param footerLine the localized footer line naming {@code generatedAt}, drawn by {@link
   *     #footer()}
   * @return the open document; the caller must close it
   */
  public static @NotNull TaggedKrtPdfDocument open(
      @NotNull OutputStream target,
      @NotNull String title,
      @NotNull Instant generatedAt,
      @NotNull String footerLine) {
    Document document =
        new Document(
            PdfPageFlow.PAGE,
            PdfPageFlow.SIDE_MARGIN,
            PdfPageFlow.SIDE_MARGIN,
            PdfPageFlow.TOP_BOTTOM_MARGIN,
            PdfPageFlow.TOP_BOTTOM_MARGIN);
    PdfWriter writer = PdfWriter.getInstance(document, target);
    // Flate streams in object streams keep a 100 000-row table near 26 MB (REQ-API-029).
    writer.setFullCompression();
    writer.setTagged();
    writer.setPageEvent(new KrtPageDecoration());
    PdfConformanceProfile.beforeOpen(writer);
    document.open();
    PdfConformanceProfile.afterOpen(writer, title, generatedAt);
    TaggedKrtPdfDocument pdf = new TaggedKrtPdfDocument(document, writer, footerLine);
    pdf.title(title);
    return pdf;
  }

  @Override
  public void heading(@NotNull String heading) {
    requireNoOpenTable();
    text.draw(heading, HEADING);
  }

  @Override
  public void note(@NotNull String note) {
    requireNoOpenTable();
    text.draw(note, NOTE);
  }

  @Override
  public void notice(@NotNull String notice) {
    requireNoOpenTable();
    text.draw(notice, NOTICE);
  }

  @Override
  public void keyValueTable(@NotNull List<PdfKeyValue> rows) {
    requireNoOpenTable();
    PdfRowPainter painter = new PdfRowPainter(tree, flow, canvas, KEY_VALUE_WIDTHS);
    PdfStructureTree.Element table = tree.group(tree.document(), PdfName.TABLE);
    for (PdfKeyValue row : rows) {
      painter.draw(
          table,
          List.of(
              PdfCell.header(
                  new Phrase(row.label(), KrtPdfTheme.bold(KEY_FONT_SIZE, KrtPdfTheme.COLOR_LABEL)),
                  KrtPdfTheme.COLOR_DARK_GRAY,
                  PdfCellFrame.PLAIN,
                  PdfCell.SCOPE_ROW),
              PdfCell.data(
                  new Phrase(
                      row.value(), KrtPdfTheme.regular(VALUE_FONT_SIZE, KrtPdfTheme.COLOR_WHITE)),
                  Element.ALIGN_LEFT,
                  1,
                  KrtPdfTheme.COLOR_DARK_GRAY,
                  PdfCellFrame.PLAIN)),
          () -> {});
    }
    tree.end(table);
    flow.advance(KEY_VALUE_SPACE_AFTER);
  }

  @Override
  public @NotNull PdfDataTable dataTable(float @NotNull [] widths, @NotNull List<String> headers) {
    requireNoOpenTable();
    TaggedPdfDataTable table =
        new TaggedPdfDataTable(tree, flow, canvas, widths, headers, () -> openTable = null);
    openTable = table;
    return table;
  }

  @Override
  public void figure(
      float width,
      float height,
      @NotNull String alternateDescription,
      @NotNull Consumer<PdfContentByte> drawing) {
    requireNoOpenTable();
    if (height > flow.available() && !flow.atPageStart()) {
      flow.newPage();
    }
    PdfTemplate template = canvas.createTemplate(width, height);
    drawing.accept(template);
    float x = flow.left();
    float y = flow.cursor() - height;
    PdfStructureTree.Element figure = tree.content(tree.document(), PdfName.FIGURE);
    figure.alternateDescription(alternateDescription);
    figure.attribute(
        LAYOUT_OWNER, PdfName.BBOX, new PdfArray(new float[] {x, y, x + width, y + height}));
    figure.attribute(LAYOUT_OWNER, PLACEMENT, BLOCK);
    tree.beginContent(canvas, figure);
    canvas.addTemplate(template, x, y);
    canvas.endMarkedContentSequence();
    tree.end(figure);
    flow.moveTo(y);
  }

  @Override
  public void sectionGap() {
    requireNoOpenTable();
    flow.advance(SECTION_GAP);
  }

  @Override
  public void newPage() {
    requireNoOpenTable();
    flow.newPage();
  }

  @Override
  public void footer() {
    requireNoOpenTable();
    text.draw(footerLine, FOOTER);
  }

  @Override
  public void close() {
    if (openTable != null) {
      openTable.close();
    }
    tree.close();
    document.close();
  }

  private void title(@NotNull String title) {
    text.draw(title, TITLE);
    float y = flow.cursor();
    PdfArtifact.LAYOUT.draw(
        canvas,
        () -> {
          canvas.setColorStroke(KrtPdfTheme.COLOR_ORANGE);
          canvas.setLineWidth(TITLE_RULE_WIDTH);
          canvas.moveTo(flow.left(), y);
          canvas.lineTo(flow.right(), y);
          canvas.stroke();
        });
    flow.advance(TITLE_RULE_SPACE_AFTER);
  }

  private void requireNoOpenTable() {
    if (openTable != null) {
      throw new IllegalStateException("Close the open data table before adding another block");
    }
  }
}
