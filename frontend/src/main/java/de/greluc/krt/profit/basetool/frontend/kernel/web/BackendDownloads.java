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

import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.withBackendStatus;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendBinaryTransfer;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Writes backend files to the browser as downloads with an RFC 6266 {@code attachment} disposition.
 */
public final class BackendDownloads {

  private BackendDownloads() {}

  /**
   * Wraps a fully read backend file as a {@code 200 OK} download.
   *
   * @param body the file bytes
   * @param filename the fixed US-ASCII attachment filename
   * @param mediaType the file's content type, parameters included
   * @return the attachment response
   */
  public static @NotNull ResponseEntity<byte[]> attachment(
      byte @NotNull [] body, @NotNull String filename, @NotNull MediaType mediaType) {
    return ResponseEntity.ok()
        .contentType(mediaType)
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition(filename))
        .body(body);
  }

  /**
   * The {@code attachment} disposition of a fixed US-ASCII filename.
   *
   * @param filename the attachment filename
   * @return the {@code Content-Disposition} header value
   * @throws IllegalArgumentException when the name leaves US-ASCII, which a plain {@code filename}
   *     parameter cannot carry (RFC 6266 appendix D)
   */
  public static @NotNull String disposition(@NotNull String filename) {
    if (!StandardCharsets.US_ASCII.newEncoder().canEncode(filename)) {
      throw new IllegalArgumentException("Download filename must be US-ASCII: " + filename);
    }
    return ContentDisposition.attachment().filename(filename).build().toString();
  }

  /**
   * Preserves pre-body backend failures and exposes attachment headers only after acceptance.
   *
   * @param response the browser response
   * @param transfer the authorized backend transfer
   * @param filename the fixed attachment filename
   * @param mediaType the attachment's content type
   * @throws IOException when the servlet cannot open its response stream
   */
  public static void send(
      @NotNull HttpServletResponse response,
      @NotNull BackendBinaryTransfer transfer,
      @NotNull String filename,
      @NotNull MediaType mediaType)
      throws IOException {
    OutputStream output = response.getOutputStream();
    try {
      withBackendStatus(() -> {
        transfer.writeTo(
            output,
            headers -> {
              response.setContentType(mediaType.toString());
              response.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition(filename));
              if (headers.getContentLength() >= 0) {
                response.setContentLengthLong(headers.getContentLength());
              }
            });
        return null;
      });
    } catch (ResponseStatusException failure) {
      if (response.isCommitted()) {
        throw new AsyncRequestNotUsableException(
            "Binary download interrupted after response commitment", failure);
      }
      resetUncommittedDownload(response);
      throw failure;
    }
  }

  private static void resetUncommittedDownload(@NotNull HttpServletResponse response) {
    Map<String, List<String>> retained = new LinkedHashMap<>();
    for (String name : response.getHeaderNames()) {
      if (!HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(name)
          && !HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name)
          && !HttpHeaders.CONTENT_DISPOSITION.equalsIgnoreCase(name)) {
        retained.put(name, List.copyOf(response.getHeaders(name)));
      }
    }
    response.reset();
    retained.forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
  }
}
