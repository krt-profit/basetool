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

package de.greluc.krt.profit.basetool.frontend.config;

import de.greluc.krt.profit.basetool.frontend.metrics.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.csrf.CsrfException;

/**
 * {@link AccessDeniedHandler} that counts CSRF-token rejections into {@code
 * basetool_csrf_rejections_total} (unlabelled) before delegating to the default handler, which
 * still writes the usual {@code 403}.
 *
 * <p>Without this counter a systematic CSRF-wiring regression is invisible: {@code krtFetch}'s
 * silent single-retry self-heal (re-fetch {@code GET /csrf}, retry the write once) turns a
 * structural failure into what merely looks like intermittent failed writes. The counter makes the
 * rejection <b>rate</b> observable so {@code CsrfRejectionSpike} can distinguish a genuine wiring
 * regression from the occasional stale token (#1041 item 18). Only {@link CsrfException} (and its
 * subtypes {@code MissingCsrfTokenException} / {@code InvalidCsrfTokenException}) is counted; every
 * other {@link AccessDeniedException} passes through uncounted.
 */
public class CsrfMetricsAccessDeniedHandler implements AccessDeniedHandler {

  private final MeterRegistry meterRegistry;
  private final AccessDeniedHandler delegate;

  /**
   * Builds the handler with a fresh {@link AccessDeniedHandlerImpl} delegate (the default {@code
   * 403} behaviour).
   *
   * @param meterRegistry the registry the {@code basetool_csrf_rejections_total} counter is bumped
   *     against
   */
  public CsrfMetricsAccessDeniedHandler(@NotNull MeterRegistry meterRegistry) {
    this(meterRegistry, new AccessDeniedHandlerImpl());
  }

  /**
   * Constructor seam for tests: injects the delegate so the {@code 403} write can be asserted
   * without a real servlet response.
   *
   * @param meterRegistry the registry the counter is bumped against
   * @param delegate the access-denied handler the call is forwarded to after counting
   */
  CsrfMetricsAccessDeniedHandler(
      @NotNull MeterRegistry meterRegistry, @NotNull AccessDeniedHandler delegate) {
    this.meterRegistry = meterRegistry;
    this.delegate = delegate;
  }

  /**
   * Counts the rejection when it is a CSRF failure, then delegates the {@code 403} response
   * unchanged.
   *
   * @param request the current HTTP request
   * @param response the HTTP response the delegate writes the {@code 403} to
   * @param accessDeniedException the access-denied cause
   * @throws IOException if the delegate fails to write the response
   * @throws ServletException if the delegate raises a servlet-layer exception
   */
  @Override
  public void handle(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull AccessDeniedException accessDeniedException)
      throws IOException, ServletException {
    if (accessDeniedException instanceof CsrfException) {
      meterRegistry.counter(MetricNames.CSRF_REJECTIONS).increment();
    }
    delegate.handle(request, response, accessDeniedException);
  }
}
