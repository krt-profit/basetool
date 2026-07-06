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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import de.greluc.krt.profit.basetool.frontend.metrics.MetricNames;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;

/**
 * Unit tests for {@link CsrfMetricsAccessDeniedHandler} (#1041 item 18): a CSRF-token rejection
 * must bump {@code basetool_csrf_rejections_total} and still delegate the {@code 403}; any other
 * access-denied cause must delegate uncounted.
 */
class CsrfMetricsAccessDeniedHandlerTest {

  /**
   * Reads the unlabelled {@code basetool_csrf_rejections_total} value.
   *
   * @param registry the registry under assertion
   * @return the counter value, or {@code 0.0} when the series is absent
   */
  private static double csrfCount(SimpleMeterRegistry registry) {
    var counter = registry.find(MetricNames.CSRF_REJECTIONS).counter();
    return counter == null ? 0.0 : counter.count();
  }

  @Test
  void csrfRejection_isCounted_andStillDelegated() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    AccessDeniedHandler delegate = mock(AccessDeniedHandler.class);
    CsrfMetricsAccessDeniedHandler handler = new CsrfMetricsAccessDeniedHandler(registry, delegate);
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    CsrfException csrf = new CsrfException("Invalid CSRF token");

    handler.handle(request, response, csrf);

    assertThat(csrfCount(registry)).isEqualTo(1.0);
    verify(delegate).handle(request, response, csrf);
  }

  @Test
  void nonCsrfAccessDenied_isNotCounted_butStillDelegated() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    AccessDeniedHandler delegate = mock(AccessDeniedHandler.class);
    CsrfMetricsAccessDeniedHandler handler = new CsrfMetricsAccessDeniedHandler(registry, delegate);
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    AccessDeniedException denied = new AccessDeniedException("Forbidden");

    handler.handle(request, response, denied);

    assertThat(csrfCount(registry)).isZero();
    verify(delegate).handle(request, response, denied);
  }
}
