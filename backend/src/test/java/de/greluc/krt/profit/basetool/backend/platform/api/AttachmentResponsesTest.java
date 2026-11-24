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

package de.greluc.krt.profit.basetool.backend.platform.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Unit tests for {@link AttachmentResponses}: status, media type and an RFC 6266 {@code attachment}
 * disposition whose {@code filename*} appears only for a name outside US-ASCII (REQ-API-025).
 */
class AttachmentResponsesTest {

  @Test
  void pdf_isAnOkAttachmentWithThePdfMediaType() {
    byte[] body = {1, 2, 3, 4};

    ResponseEntity<byte[]> response = AttachmentResponses.pdf(body, "kontoauszug-42.pdf");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(body);
    HttpHeaders headers = response.getHeaders();
    assertThat(headers.getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
    assertThat(headers.getFirst(HttpHeaders.CONTENT_DISPOSITION))
        .isEqualTo("attachment; filename=\"kontoauszug-42.pdf\"");
  }

  @Test
  void pdf_streamsAResourceAsAnAttachment() {
    Resource body = new ByteArrayResource(new byte[] {1});

    ResponseEntity<Resource> response = AttachmentResponses.pdf(body, "report.pdf");

    assertThat(response.getBody()).isSameAs(body);
    assertThat(response.getHeaders().getContentDisposition().isAttachment()).isTrue();
  }

  @Test
  void json_carriesTheJsonMediaTypeWithoutACharsetParameter() {
    List<String> body = List.of("a");

    ResponseEntity<List<String>> response = AttachmentResponses.json(body, "audit-bank.json");

    assertThat(response.getBody()).isSameAs(body);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    assertThat(response.getHeaders().getContentType().getCharset()).isNull();
    assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
        .isEqualTo("attachment; filename=\"audit-bank.json\"");
  }

  @Test
  void contentDisposition_addsAUtf8FilenameStarOnlyOutsideAscii() {
    String header = AttachmentResponses.contentDisposition("Datenexport Jörg 26-11-24.json");

    assertThat(header)
        .startsWith("attachment; filename=\"")
        .contains("filename*=UTF-8''Datenexport%20J%C3%B6rg%2026-11-24.json");
    String fallback = header.substring(header.indexOf('"') + 1, header.indexOf("\";"));
    assertThat(fallback.chars().allMatch(c -> c < 0x80)).as("ASCII fallback").isTrue();
    assertThat(ContentDisposition.parse(header).getFilename())
        .isEqualTo("Datenexport Jörg 26-11-24.json");
  }

  @Test
  void contentDisposition_quotesAnAsciiNameWithoutFilenameStar() {
    assertThat(AttachmentResponses.contentDisposition("Datenexport Alice 26-11-24.pdf"))
        .isEqualTo("attachment; filename=\"Datenexport Alice 26-11-24.pdf\"");
  }
}
