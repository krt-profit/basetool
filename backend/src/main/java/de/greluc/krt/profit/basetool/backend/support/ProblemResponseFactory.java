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

package de.greluc.krt.profit.basetool.backend.support;

import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/**
 * Builder for the RFC&nbsp;7807 {@link ProblemDetail} bodies of every error surface: title, {@code
 * type} (from {@link AppProblemProperties#getBaseUri()}), optional {@code instance}, and the {@code
 * code} and {@code correlationId} extensions.
 *
 * <p>The caller supplies the {@code correlationId}.
 */
@Component
@RequiredArgsConstructor
public class ProblemResponseFactory {

  /** MDC key the correlation-id filter populates; reused so one id spans a request's log lines. */
  private static final String MDC_CORRELATION_ID = "correlationId";

  private final AppProblemProperties problemProperties;

  /**
   * Builds the base {@link ProblemDetail} shared by every problem surface.
   *
   * @param status the HTTP status
   * @param title the localized summary
   * @param detail the localized explanation
   * @param instanceUri the request URI for {@code instance}, or {@code null}/blank to omit it
   * @param typeSuffix appended to {@link AppProblemProperties#getBaseUri()} to form the {@code
   *     type}
   * @param code the stable, machine-readable {@code code} extension
   * @param correlationId the correlation id echoed as the {@code correlationId} extension
   * @return the assembled problem detail
   */
  public ProblemDetail problem(
      HttpStatus status,
      String title,
      String detail,
      @Nullable String instanceUri,
      String typeSuffix,
      String code,
      String correlationId) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
    pd.setTitle(title);
    pd.setType(URI.create(problemProperties.baseUri() + typeSuffix));
    if (instanceUri != null && !instanceUri.isBlank()) {
      pd.setInstance(URI.create(instanceUri));
    }
    pd.setProperty("code", code);
    pd.setProperty("correlationId", correlationId);
    return pd;
  }

  /**
   * Reuses the correlation id already assigned to the request (via the SLF4J {@link MDC}, populated
   * by the correlation-id filter) or, when none is present yet, mints a fresh UUID. Callers that
   * must also log or echo the id read it from here so the logged and returned ids match.
   *
   * @return the request's correlation id, minted if absent
   */
  public static String correlationId() {
    String existing = MDC.get(MDC_CORRELATION_ID);
    if (existing != null && !existing.isBlank()) {
      return existing;
    }
    return UUID.randomUUID().toString();
  }
}
