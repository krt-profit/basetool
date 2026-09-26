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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Unit test for {@link PdfResponses#pdfAttachment(byte[], String)}, pinning the status, {@code
 * Content-Type} and {@code Content-Disposition}.
 */
class PdfResponsesTest {

  @Test
  void buildsOkPdfAttachmentResponse() {
    byte[] body = {1, 2, 3, 4};

    ResponseEntity<byte[]> response = PdfResponses.pdfAttachment(body, "kontoauszug-42.pdf");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(body);

    HttpHeaders headers = response.getHeaders();
    assertThat(headers.getContentType()).isEqualTo(MediaType.APPLICATION_PDF);

    ContentDisposition disposition = headers.getContentDisposition();
    assertThat(disposition.getType()).isEqualTo("form-data");
    assertThat(disposition.getName()).isEqualTo("attachment");
    assertThat(disposition.getFilename()).isEqualTo("kontoauszug-42.pdf");
  }
}
