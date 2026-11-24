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
import de.greluc.krt.profit.basetool.backend.platform.api.pdf.KrtPdfDocuments;
import java.io.OutputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/**
 * Opens every backend PDF as a {@link TaggedKrtPdfDocument} (REQ-API-029), with its footer line
 * resolved in German from the backend message bundle.
 */
@Component
@RequiredArgsConstructor
public class TaggedKrtPdfDocuments implements KrtPdfDocuments {

  /** The language every backend PDF is written in. */
  private static final Locale PDF_LOCALE = Locale.GERMAN;

  /** Message key of the footer line; its one argument is the formatted generation timestamp. */
  private static final String FOOTER_KEY = "pdf.footer.generated";

  /**
   * Footer generation-timestamp format. Always UTC, so the footer is an unambiguous stamp whatever
   * zone the rest of the document is rendered in.
   */
  private static final DateTimeFormatter FOOTER_TIMESTAMP =
      DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneOffset.UTC);

  /** The backend message bundle the footer line is resolved from. */
  private final MessageSource messageSource;

  @Override
  public @NotNull KrtPdfDocument open(@NotNull OutputStream target, @NotNull String title) {
    Instant generatedAt = Instant.now();
    return TaggedKrtPdfDocument.open(target, title, generatedAt, footerLine(generatedAt));
  }

  private @NotNull String footerLine(@NotNull Instant generatedAt) {
    return messageSource.getMessage(
        FOOTER_KEY, new Object[] {FOOTER_TIMESTAMP.format(generatedAt)}, PDF_LOCALE);
  }
}
