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
    ServerRequestObservationContext context = serverContext("/notifications/stream");

    assertThat(predicate.test("http.server.requests", context)).isFalse();
  }

  @Test
  void shouldObserveHttpServerRequestForOtherEndpoints() {
    ServerRequestObservationContext context = serverContext("/notifications/recent");

    assertThat(predicate.test("http.server.requests", context)).isTrue();
  }

  @Test
  void shouldObserveNonHttpServerRequestObservationsForRelayPath() {
    ServerRequestObservationContext context = serverContext("/notifications/stream");

    assertThat(predicate.test("spring.security.filterchains", context)).isTrue();
  }

  @Test
  void shouldObserveWhenContextIsNotAServerRequest() {
    Observation.Context context = new Observation.Context();

    assertThat(predicate.test("http.server.requests", context)).isTrue();
  }

  private static ServerRequestObservationContext serverContext(String requestUri) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", requestUri);
    request.setRequestURI(requestUri);
    return new ServerRequestObservationContext(request, new MockHttpServletResponse());
  }
}
