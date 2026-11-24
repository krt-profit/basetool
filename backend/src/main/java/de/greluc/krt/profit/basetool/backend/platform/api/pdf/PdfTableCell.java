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

import org.jetbrains.annotations.NotNull;

/**
 * The text of one body cell of a {@link PdfDataTable}.
 *
 * @param text the cell text; an empty text leaves the cell blank
 * @param centered {@code true} centers the text, as numeric columns do
 */
public record PdfTableCell(@NotNull String text, boolean centered) {

  /**
   * A left-aligned cell.
   *
   * @param text the cell text
   * @return the cell
   */
  public static @NotNull PdfTableCell text(@NotNull String text) {
    return new PdfTableCell(text, false);
  }

  /**
   * A centered cell, for numbers and amounts.
   *
   * @param text the cell text
   * @return the cell
   */
  public static @NotNull PdfTableCell centered(@NotNull String text) {
    return new PdfTableCell(text, true);
  }
}
