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

import java.nio.charset.StandardCharsets;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Builds every file-download response of the backend (REQ-API-008, REQ-API-025): a {@code 200 OK}
 * with the file's media type and an RFC 6266 {@code attachment} disposition.
 */
public final class AttachmentResponses {

  private AttachmentResponses() {}

  /**
   * Delivers rendered PDF bytes as a download.
   *
   * @param body the rendered PDF bytes
   * @param filename the download filename (e.g. {@code kontoauszug-<id>.pdf})
   * @return the {@code application/pdf} attachment response
   */
  public static @NotNull ResponseEntity<byte[]> pdf(
      byte @NotNull [] body, @NotNull String filename) {
    return attachment(body, MediaType.APPLICATION_PDF, filename);
  }

  /**
   * Delivers a PDF that the response converter streams from a request-owned resource.
   *
   * @param body the completed, request-owned resource
   * @param filename the download filename
   * @return the streaming {@code application/pdf} attachment response
   */
  public static @NotNull ResponseEntity<Resource> pdf(
      @NotNull Resource body, @NotNull String filename) {
    return attachment(body, MediaType.APPLICATION_PDF, filename);
  }

  /**
   * Delivers a value the JSON converter serialises as a download.
   *
   * @param body the value to serialise
   * @param filename the download filename (e.g. {@code audit-bank.json})
   * @param <T> the body type
   * @return the {@code application/json} attachment response
   */
  public static <T> @NotNull ResponseEntity<T> json(@NotNull T body, @NotNull String filename) {
    return attachment(body, MediaType.APPLICATION_JSON, filename);
  }

  /**
   * Delivers a body of any media type as a download.
   *
   * @param body the response body
   * @param mediaType the file's media type, parameters included
   * @param filename the download filename
   * @param <T> the body type
   * @return the attachment response
   */
  public static <T> @NotNull ResponseEntity<T> attachment(
      @NotNull T body, @NotNull MediaType mediaType, @NotNull String filename) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(mediaType);
    headers.set(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(filename));
    return ResponseEntity.ok().headers(headers).body(body);
  }

  /**
   * The {@code Content-Disposition} value of a download: {@code attachment} with a quoted ASCII
   * {@code filename}, plus an RFC 8187 UTF-8 {@code filename*} only when the name leaves US-ASCII
   * (RFC 6266 appendix D); the ASCII fallback then transliterates what it can.
   *
   * @param filename the download filename
   * @return the header value
   */
  public static @NotNull String contentDisposition(@NotNull String filename) {
    ContentDisposition.Builder builder = ContentDisposition.attachment();
    if (StandardCharsets.US_ASCII.newEncoder().canEncode(filename)) {
      builder.filename(filename);
    } else {
      builder.filename(filename, StandardCharsets.UTF_8);
    }
    return builder.build().toString();
  }
}
