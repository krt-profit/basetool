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

package de.greluc.krt.profit.basetool.backend.support;

import java.io.IOException;
import org.jetbrains.annotations.NotNull;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

/** Extracts the visible text of a rendered PDF so a test can assert on what a reader sees. */
public final class PdfText {

  private PdfText() {}

  /**
   * Returns the text of every page, one page per line block.
   *
   * @param pdf the rendered PDF
   * @return the text of all pages, each followed by a line break
   * @throws IOException when the bytes are not a readable PDF
   */
  public static @NotNull String of(byte @NotNull [] pdf) throws IOException {
    PdfReader reader = new PdfReader(pdf);
    try {
      PdfTextExtractor extractor = new PdfTextExtractor(reader);
      StringBuilder text = new StringBuilder();
      for (int page = 1; page <= reader.getNumberOfPages(); page++) {
        text.append(extractor.getTextFromPage(page)).append('\n');
      }
      return text.toString();
    } finally {
      reader.close();
    }
  }
}
