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

package de.greluc.krt.profit.basetool.frontend.kernel.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Unit tests for the RFC 6266 attachment responses of {@link BackendDownloads} (REQ-API-025). */
class BackendDownloadsTest {

  @Test
  void attachment_isAnOkDownloadWithAnAttachmentDisposition() {
    byte[] body = {0x25, 0x50, 0x44, 0x46};

    ResponseEntity<byte[]> response =
        BackendDownloads.attachment(body, "uebergabeprotokoll-1.pdf", MediaType.APPLICATION_PDF);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(body);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
    assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
        .isEqualTo("attachment; filename=\"uebergabeprotokoll-1.pdf\"");
  }

  @Test
  void disposition_isNeverTheMultipartFormDataType() {
    assertThat(BackendDownloads.disposition("Datenexport.json"))
        .isEqualTo("attachment; filename=\"Datenexport.json\"");
  }

  @Test
  void disposition_refusesANameAPlainFilenameParameterCannotCarry() {
    assertThatThrownBy(() -> BackendDownloads.disposition("Übergabe.pdf"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
