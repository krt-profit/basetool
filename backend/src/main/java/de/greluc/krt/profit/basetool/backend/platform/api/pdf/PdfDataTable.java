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
import org.jetbrains.annotations.NotNull;

/**
 * An open data table of a {@link KrtPdfDocument}, drawn row by row as it is filled: column headers
 * tagged {@code TH} with column scope, body cells {@code TD} (REQ-API-029).
 *
 * <p>Every row is drawn and its structure written as soon as it is added, so a table of any length
 * holds no more than one row in memory; the column headers repeat on every further page. Closing
 * the table ends its structure element; the document refuses other blocks until then.
 */
public interface PdfDataTable extends AutoCloseable {

  /**
   * Adds a body row; body rows alternate their background.
   *
   * @param cells one cell per column
   * @throws IllegalArgumentException when the row has more or fewer cells than the table columns
   * @throws IllegalStateException when the table is closed
   */
  void row(@NotNull List<PdfTableCell> cells);

  /**
   * Adds the indented full-width detail row under the last body row, such as a booking's
   * justification, note and staff note (REQ-BANK-044): one cell spanning every column on the
   * background of the row above, each entry as its label followed by the value. Entries with an
   * empty value are left out; when none is left, no row is added.
   *
   * @param details the labelled details in reading order
   * @throws IllegalStateException when the table is closed
   */
  void detailRow(@NotNull List<PdfKeyValue> details);

  /**
   * Adds the full-width row that says the table has no entries.
   *
   * @param text the localized empty-state text
   * @throws IllegalStateException when the table is closed
   */
  void emptyRow(@NotNull String text);

  /** Ends the table's structure element; later rows are refused. */
  @Override
  void close();
}
