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

package de.greluc.krt.profit.basetool.backend.config;

import de.greluc.krt.profit.basetool.backend.exception.CoreProblemCode;
import de.greluc.krt.profit.basetool.backend.kernel.ProblemResponseFactory;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.PathContainer;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Answers a retired Android operation with {@code 410 Gone} and the problem code {@code
 * APP_UPDATE_REQUIRED}, which the app maps to its update wall (REQ-API-020, ADR-0234).
 *
 * <p>Runs ahead of CSRF and bearer-token authentication, so an app whose token no longer validates
 * still meets the wall. It answers only the exact verb and path of an entry in {@link
 * RetiredOperations}, never forwards such a request, and is skipped entirely while the list is
 * empty.
 */
@Slf4j
@RequiredArgsConstructor
public class RetiredOperationFilter extends OncePerRequestFilter {

  /** Stable machine-readable code the Android app maps to its update wall. */
  public static final String CODE_APP_UPDATE_REQUIRED = CoreProblemCode.APP_UPDATE_REQUIRED.code();

  /** Problem-type suffix appended to the problem base URI. */
  static final String TYPE_SUFFIX = "app-update-required";

  /** App-wide correlation-id response header. */
  static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

  /** The retired operations. */
  private final @NotNull RetiredOperations retiredOperations;

  /** Resolves the localized {@code problem.app_update_required.*} title and detail. */
  private final @NotNull MessageSource messageSource;

  /** Assembles the RFC 7807 body. */
  private final @NotNull ProblemResponseFactory problemResponseFactory;

  /** Serializes the body. */
  private final @NotNull ObjectMapper objectMapper;

  /** Counts the answers under {@link MetricNames#HTTP_ERROR}. */
  private final @NotNull MeterRegistry meterRegistry;

  /**
   * Skips the filter while nothing is retired.
   *
   * @param request the current request
   * @return {@code true} when the list is empty
   */
  @Override
  protected boolean shouldNotFilter(@NotNull HttpServletRequest request) {
    return retiredOperations.isEmpty();
  }

  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain filterChain)
      throws ServletException, IOException {
    Optional<RetiredOperations.Entry> retired = retiredEntry(request);
    if (retired.isPresent()) {
      writeGone(request, response, retired.get());
      return;
    }
    filterChain.doFilter(request, response);
  }

  /**
   * Finds the retired entry the request matches.
   *
   * @param request the current request
   * @return the matching entry, or empty
   */
  @NotNull
  Optional<RetiredOperations.Entry> retiredEntry(@NotNull HttpServletRequest request) {
    PathContainer path =
        PathContainer.parsePath(
            request.getRequestURI().substring(request.getContextPath().length()));
    return retiredOperations.match(request.getMethod(), path);
  }

  private void writeGone(
      HttpServletRequest request, HttpServletResponse response, RetiredOperations.Entry entry)
      throws IOException {
    String correlationId = ProblemResponseFactory.correlationId();
    log.debug(
        "Retired operation {} answered {} [correlationId={}]",
        entry,
        CODE_APP_UPDATE_REQUIRED,
        correlationId);
    meterRegistry
        .counter(MetricNames.HTTP_ERROR, MetricNames.TAG_CODE, CODE_APP_UPDATE_REQUIRED)
        .increment();

    Locale locale = request.getLocale();
    String title =
        messageSource.getMessage(
            "problem.app_update_required.title", null, "App update required", locale);
    String detail =
        messageSource.getMessage(
            "problem.app_update_required.detail",
            null,
            "This app version is no longer supported. Install the current version.",
            locale);

    response.setStatus(HttpServletResponse.SC_GONE);
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setHeader(CORRELATION_ID_HEADER, correlationId);
    ProblemDetail problem =
        problemResponseFactory.problem(
            HttpStatus.GONE,
            title,
            detail,
            request.getRequestURI(),
            TYPE_SUFFIX,
            CODE_APP_UPDATE_REQUIRED,
            correlationId);
    response.getOutputStream().write(objectMapper.writeValueAsBytes(problem));
  }
}
