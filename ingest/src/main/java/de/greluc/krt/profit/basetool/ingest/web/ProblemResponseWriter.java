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

package de.greluc.krt.profit.basetool.ingest.web;

import de.greluc.krt.profit.basetool.ingest.config.LoggingProperties;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes an RFC 7807 {@code application/problem+json} body directly to the servlet response. Used
 * by the pre-security filters (size cap, rate limit) and the security-chain handlers, which run
 * before Spring MVC, so {@link GlobalExceptionHandler} cannot serialize them. The body itself comes
 * from {@link Problems#of}, the same builder the controller-level problems use, so both carry the
 * identical {@code code} + {@code correlationId} extension shape.
 */
public final class ProblemResponseWriter {

  private ProblemResponseWriter() {}

  /**
   * Serializes a problem document and writes it with the given status. Safe to call before the MVC
   * dispatcher runs.
   *
   * @param response the servlet response to write to
   * @param objectMapper the JSON serializer
   * @param logging supplies the MDC key the correlation id is read from
   * @param status the HTTP status
   * @param title a short, stable title
   * @param code the stable machine-readable code
   * @param detail the human-readable, non-sensitive detail
   * @throws IOException if writing the response body fails
   */
  public static void write(
      @NotNull HttpServletResponse response,
      @NotNull ObjectMapper objectMapper,
      @NotNull LoggingProperties logging,
      @NotNull HttpStatus status,
      @NotNull String title,
      @NotNull String code,
      @NotNull String detail)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response
        .getWriter()
        .write(objectMapper.writeValueAsString(Problems.of(logging, status, title, code, detail)));
  }
}
