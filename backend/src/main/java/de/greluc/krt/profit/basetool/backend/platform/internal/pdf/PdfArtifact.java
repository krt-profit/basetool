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
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfDictionary;
import org.openpdf.text.pdf.PdfName;

/**
 * The kinds of artifact content the layer draws, each marked as an artifact sequence with its type
 * property (ISO 32000-2 14.8.2.2.2, WTPDF 8.3.1).
 */
@RequiredArgsConstructor
public enum PdfArtifact {

  /** Design decoration: the page background, the accent bar, rules, cell fills and borders. */
  LAYOUT("Layout"),

  /** Content repeated because of pagination: the logo on every page and repeated table headers. */
  PAGINATION("Pagination");

  private static final PdfName ARTIFACT = new PdfName("Artifact");

  /** The value of the sequence's {@code Type} property. */
  private final String type;

  /**
   * Draws artifact content as one marked sequence of this kind.
   *
   * @param canvas the canvas to draw on
   * @param drawing the drawing operations
   */
  public void draw(@NotNull PdfContentByte canvas, @NotNull Runnable drawing) {
    PdfDictionary properties = new PdfDictionary();
    properties.put(PdfName.TYPE, new PdfName(type));
    canvas.beginMarkedContentSequence(ARTIFACT, properties, true);
    drawing.run();
    canvas.endMarkedContentSequence();
  }
}
