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

package de.greluc.krt.profit.basetool.backend.platform.api.pdf;

import java.util.List;
import java.util.function.Consumer;
import org.jetbrains.annotations.NotNull;
import org.openpdf.text.pdf.PdfContentByte;

/**
 * One open backend PDF in KRT design, written top to bottom as a tagged PDF/A-4, PDF/UA-2 and
 * Well-Tagged PDF file (REQ-API-026, REQ-API-029); {@link KrtPdfDocuments#open} creates it with its
 * title.
 *
 * <p>Every block is tagged: headings as {@code H2}, notes, notices and the footer as {@code P},
 * tables as {@code Table} with header cells, figures as {@code Figure} with their description.
 * Decoration is drawn as artifact. No other content can be added.
 */
public interface KrtPdfDocument extends AutoCloseable {

  /**
   * Adds a section heading: 12 pt Lato Bold, orange, tagged {@code H2}; it moves to the next page
   * when there is no room for a table header and its first row below it.
   *
   * @param heading the (uppercase) section title
   */
  void heading(@NotNull String heading);

  /**
   * Adds a small italic note about the document itself, tagged {@code P}.
   *
   * @param note the localized note
   */
  void note(@NotNull String note);

  /**
   * Adds an italic notice in body size, such as the statement that a report has no entries, tagged
   * {@code P}.
   *
   * @param notice the localized notice
   */
  void notice(@NotNull String notice);

  /**
   * Adds a two-column key/value table: each row a muted bold label, tagged {@code TH} with row
   * scope, beside its white value, tagged {@code TD}, on dark-gray cells without borders.
   *
   * @param rows the labelled values in reading order
   */
  void keyValueTable(@NotNull List<PdfKeyValue> rows);

  /**
   * Starts a data table at the cursor; its rows are drawn as they are added, and no other block may
   * be added until the table is closed.
   *
   * @param widths the relative column widths
   * @param headers the column headers, one per width
   * @return the open table
   * @throws IllegalArgumentException when the widths and headers differ in number
   */
  @NotNull
  PdfDataTable dataTable(float @NotNull [] widths, @NotNull List<String> headers);

  /**
   * Adds a figure, such as a chart, as a {@code Figure} element with its alternate description and
   * bounding box; it starts on a new page when it does not fit the rest of this one.
   *
   * @param width the figure width, in points
   * @param height the figure height, in points
   * @param alternateDescription what a reader announces instead of the figure: everything the
   *     figure shows, in words
   * @param drawing draws the figure onto a canvas of {@code width} by {@code height} with the
   *     origin at its bottom-left corner
   */
  void figure(
      float width,
      float height,
      @NotNull String alternateDescription,
      @NotNull Consumer<PdfContentByte> drawing);

  /** Leaves the vertical gap that separates two sections. */
  void sectionGap();

  /** Continues on a new page. */
  void newPage();

  /**
   * Adds the centered footer {@code "Generiert von Profit Basetool am <date> <time> UTC"}, tagged
   * {@code P}; the timestamp is always UTC.
   */
  void footer();

  /** Closes an open table, completes the structure tree and writes the rest of the file. */
  @Override
  void close();
}
