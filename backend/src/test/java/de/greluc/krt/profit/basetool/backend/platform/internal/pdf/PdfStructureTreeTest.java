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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpdf.text.Document;
import org.openpdf.text.pdf.PdfArray;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfName;
import org.openpdf.text.pdf.PdfWriter;

/**
 * Pins the bounded working set of the structure tree (REQ-API-029): a content element stays on the
 * structure tree root only while it is open, so a table of any length never holds more than its
 * open row.
 */
class PdfStructureTreeTest {

  private ByteArrayOutputStream out;

  private Document document;

  private PdfWriter writer;

  private PdfStructureTree tree;

  @BeforeEach
  void open() {
    out = new ByteArrayOutputStream();
    document = new Document();
    writer = PdfWriter.getInstance(document, out);
    writer.setTagged();
    document.open();
    tree = new PdfStructureTree(writer);
  }

  @AfterEach
  void close() throws IOException {
    out.close();
  }

  @Test
  void aContentElementLeavesTheRootWhenItEnds() {
    PdfContentByte canvas = writer.getDirectContent();
    PdfStructureTree.Element table = tree.group(tree.document(), PdfName.TABLE);

    for (int row = 0; row < 3; row++) {
      PdfStructureTree.Element tableRow = tree.group(table, PdfName.TABLEROW);
      PdfStructureTree.Element cell = tree.content(tableRow, PdfName.TD);
      tree.beginContent(canvas, cell);
      canvas.endMarkedContentSequence();
      assertThat(openContent().size()).isEqualTo(1);
      tree.end(cell);
      tree.end(tableRow);
      assertThat(openContent().isEmpty()).isTrue();
    }
    tree.end(table);
    tree.close();
    document.close();

    assertThat(out.size()).isPositive();
  }

  @Test
  void aGroupCarriesNoMarkedContent() {
    PdfStructureTree.Element table = tree.group(tree.document(), PdfName.TABLE);

    assertThatThrownBy(() -> tree.beginContent(writer.getDirectContent(), table))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private PdfArray openContent() {
    return writer.getStructureTreeRoot().getAsArray(PdfName.K);
  }
}
