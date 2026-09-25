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

package de.greluc.krt.profit.basetool.backend.web;

import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Static factory for {@code application/pdf} download responses, replacing the {@code HttpHeaders}
 * + {@code setContentType} + {@code setContentDispositionFormData} + {@code ResponseEntity.ok()}
 * boilerplate that the PDF-export endpoints (job-order handover reports, bank statements/exports,
 * audit-log exports) each repeated.
 */
public final class PdfResponses {

  private PdfResponses() {}

  /**
   * Builds a {@code 200 OK} response delivering the bytes as a downloadable PDF attachment.
   *
   * @param body the rendered PDF bytes
   * @param filename the download filename offered to the browser (e.g. {@code
   *     kontoauszug-<id>.pdf})
   * @return a {@link ResponseEntity} carrying the PDF body and attachment headers
   */
  @NotNull
  public static ResponseEntity<byte[]> pdfAttachment(
      byte @NotNull [] body, @NotNull String filename) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_PDF);
    headers.setContentDispositionFormData("attachment", filename);
    return ResponseEntity.ok().headers(headers).body(body);
  }
}
