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
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/** Opens every backend PDF as a {@link TaggedKrtPdfDocument} (REQ-API-029). */
@Component
public class TaggedKrtPdfDocuments implements KrtPdfDocuments {

  @Override
  public @NotNull KrtPdfDocument open(@NotNull OutputStream target, @NotNull String title) {
    return TaggedKrtPdfDocument.open(target, title);
  }
}
