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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * The single builder for every RFC 7807 problem body the gateway emits (REQ-API-004).
 *
 * <p>Reads the correlation id under the MDC key configured in {@link
 * LoggingProperties#correlationIdMdcKey()}; the extension member is always named {@code
 * correlationId}.
 */
public final class Problems {

  /** Name of the problem extension member carrying the stable machine-readable code. */
  public static final String CODE_MEMBER = "code";

  /** Name of the problem extension member carrying the request's correlation id. */
  public static final String CORRELATION_ID_MEMBER = "correlationId";

  private Problems() {}

  /**
   * Builds a problem with the stable {@code code} extension and, when the MDC carries one under the
   * configured key, the request's {@code correlationId}.
   *
   * @param logging supplies the MDC key the correlation id was stored under
   * @param status the HTTP status
   * @param title a short, stable title
   * @param code the stable machine-readable code clients branch on
   * @param detail the human-readable, non-sensitive detail; {@code null} is rendered as empty
   * @return the assembled problem, never {@code null}
   */
  public static @NotNull ProblemDetail of(
      @NotNull LoggingProperties logging,
      @NotNull HttpStatusCode status,
      @NotNull String title,
      @NotNull String code,
      @Nullable String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail == null ? "" : detail);
    problem.setTitle(title);
    problem.setProperty(CODE_MEMBER, code);
    String correlationId = MDC.get(logging.correlationIdMdcKey());
    if (correlationId != null) {
      problem.setProperty(CORRELATION_ID_MEMBER, correlationId);
    }
    return problem;
  }
}
