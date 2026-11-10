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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.server.ResponseStatusException;

/** Writes bounded backend downloads to the servlet response. */
public final class BackendDownloads {

  private BackendDownloads() {}

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
              response.setHeader(
                  HttpHeaders.CONTENT_DISPOSITION,
                  ContentDisposition.attachment().filename(filename).build().toString());
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
