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
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.jetbrains.annotations.NotNull;
import org.openpdf.text.Document;
import org.openpdf.text.Image;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfPageEventHelper;
import org.openpdf.text.pdf.PdfWriter;

/**
 * Paints the KRT background of every page as artifacts below the content: the canvas fill and the
 * orange top bar as layout, the logo in the bottom-right corner as pagination artifact.
 */
public final class KrtPageDecoration extends PdfPageEventHelper {

  /** Height of the orange top-accent bar, in points. */
  private static final float TOP_ACCENT_HEIGHT = 3f;

  /** Height of the logo, in points; its width follows the image's aspect ratio. */
  private static final float LOGO_HEIGHT = 60f;

  /** Distance of the logo from the right page edge, in points. */
  private static final float LOGO_MARGIN = 20f;

  /** Distance of the logo from the bottom page edge, in points. */
  private static final float LOGO_BOTTOM = 24f;

  private static final String LOGO_RESOURCE = "META-INF/resources/logos/krt.png";

  /** The logo bytes, read once per JVM; a missing logo is a packaging defect. */
  private static final byte[] LOGO = readLogo();

  /** The logo of this document; one instance, so the file embeds the image once. */
  private final Image logo = logo();

  @Override
  public void onStartPage(@NotNull PdfWriter writer, Document document) {
    PdfContentByte canvas = writer.getDirectContentUnder();
    float width = PdfPageFlow.PAGE.getWidth();
    float height = PdfPageFlow.PAGE.getHeight();
    PdfArtifact.LAYOUT.draw(
        canvas,
        () -> {
          canvas.setColorFill(KrtPdfTheme.COLOR_CANVAS);
          canvas.rectangle(0, 0, width, height);
          canvas.fill();
          canvas.setColorFill(KrtPdfTheme.COLOR_ORANGE);
          canvas.rectangle(0, height - TOP_ACCENT_HEIGHT, width, TOP_ACCENT_HEIGHT);
          canvas.fill();
        });
    PdfArtifact.PAGINATION.draw(canvas, () -> canvas.addImage(logo));
  }

  private static @NotNull Image logo() {
    try {
      Image image = Image.getInstance(LOGO);
      float logoWidth = image.getWidth() * LOGO_HEIGHT / image.getHeight();
      image.scaleAbsolute(logoWidth, LOGO_HEIGHT);
      image.setAbsolutePosition(PdfPageFlow.PAGE.getWidth() - logoWidth - LOGO_MARGIN, LOGO_BOTTOM);
      return image;
    } catch (IOException failure) {
      throw new UncheckedIOException("KRT logo could not be decoded", failure);
    }
  }

  private static byte @NotNull [] readLogo() {
    try (InputStream stream =
        KrtPageDecoration.class.getClassLoader().getResourceAsStream(LOGO_RESOURCE)) {
      if (stream == null) {
        throw new IllegalStateException("Bundled logo missing from classpath: " + LOGO_RESOURCE);
      }
      return stream.readAllBytes();
    } catch (IOException failure) {
      throw new UncheckedIOException("Bundled logo could not be read: " + LOGO_RESOURCE, failure);
    }
  }
}
