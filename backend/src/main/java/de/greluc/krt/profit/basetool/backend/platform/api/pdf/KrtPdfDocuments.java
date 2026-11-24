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

import java.io.OutputStream;
import org.jetbrains.annotations.NotNull;

/**
 * Opens backend PDFs: the single way to write one, so every PDF is tagged PDF/A-4, PDF/UA-2 and
 * Well-Tagged PDF (REQ-API-026, REQ-API-029, ADR-0410).
 */
public interface KrtPdfDocuments {

  /**
   * Creates and opens a KRT document on A4 — the dark background, accent bar and logo on every page
   * — and sets its title both as document metadata and as the {@code H1} of the first page.
   *
   * @param target the stream receiving the PDF bytes; closing the document closes it
   * @param title the (already uppercase) document title
   * @return the open document; the caller must close it
   */
  @NotNull
  KrtPdfDocument open(@NotNull OutputStream target, @NotNull String title);
}
