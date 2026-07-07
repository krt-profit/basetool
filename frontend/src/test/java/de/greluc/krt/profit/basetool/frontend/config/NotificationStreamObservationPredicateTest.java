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

import io.micrometer.observation.Observation;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for the SSE-relay observation exclusion (REQ-OBS-009): the ~30-minute notification
 * relay endpoint is dropped from {@code http.server.requests} so its lifetime never pollutes the
 * p95 latency histogram, while every other request and non-HTTP observation is still recorded.
 */
class NotificationStreamObservationPredicateTest {

  private final NotificationStreamObservationPredicate predicate =
      new NotificationStreamObservationPredicate();

  @Test
  void shouldSkipHttpServerRequestObservationForRelayEndpoint() {
    // Given
    ServerRequestObservationContext context = serverContext("/notifications/stream");

    // When / Then: the SSE relay is not observed (neither timer sample nor span).
    assertThat(predicate.test("http.server.requests", context)).isFalse();
  }

  @Test
  void shouldObserveHttpServerRequestForOtherEndpoints() {
    // Given
    ServerRequestObservationContext context = serverContext("/notifications/recent");

    // When / Then: every non-relay request is observed as usual.
    assertThat(predicate.test("http.server.requests", context)).isTrue();
  }

  @Test
  void shouldObserveNonHttpServerRequestObservationsForRelayPath() {
    // Given: a different observation name that happens to share the relay path in its context.
    ServerRequestObservationContext context = serverContext("/notifications/stream");

    // When / Then: only http.server.requests is filtered; other observations pass through.
    assertThat(predicate.test("spring.security.filterchains", context)).isTrue();
  }

  @Test
  void shouldObserveWhenContextIsNotAServerRequest() {
    // Given: a plain observation context (e.g. a client or scheduled observation).
    Observation.Context context = new Observation.Context();

    // When / Then: without a servlet request there is nothing to exclude.
    assertThat(predicate.test("http.server.requests", context)).isTrue();
  }

  private static ServerRequestObservationContext serverContext(String requestUri) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", requestUri);
    request.setRequestURI(requestUri);
    return new ServerRequestObservationContext(request, new MockHttpServletResponse());
  }
}
