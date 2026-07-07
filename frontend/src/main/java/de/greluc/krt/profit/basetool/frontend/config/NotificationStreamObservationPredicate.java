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

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

/**
 * Excludes the long-lived notification SSE relay endpoint from the {@code http.server.requests}
 * observation (REQ-OBS-009). Boot registers every {@link ObservationPredicate} bean on the
 * observation registry; when {@link #test} returns {@code false} the observation is a no-op, so the
 * request produces neither a Micrometer timer sample nor a trace span.
 *
 * <p>Why: {@code GET /notifications/stream} relays the backend SSE stream to the browser and holds
 * its own {@link org.springframework.web.servlet.mvc.method.annotation.SseEmitter} open for up to
 * 30 minutes ({@code NotificationPageController.STREAM_TIMEOUT_MS}). Spring MVC books the request's
 * <em>whole lifetime</em> into {@code http.server.requests} when the async request finally
 * completes, so each closed relay records an ~1800s latency observation. That value lands in the
 * {@code +Inf} bucket of the histogram (capped at a 10s {@code maximum-expected-value}), and once
 * relay turnover exceeds ~5% of requests in a scrape window it pins the aggregate {@code
 * histogram_quantile(0.95, …)} to the top bucket — firing {@code HttpLatencyP95High} (a p95 &gt;2s
 * warning) with no real user-facing slowness. Skipping the observation keeps the relay lifetime out
 * of the latency metric entirely.
 *
 * <p>This is the servlet-inbound mirror of leaving the outbound {@code sseWebClient} off the
 * observation registry ({@code WebClientConfig}); the relay's health is instead tracked by the
 * dedicated {@code basetool_notification_relay_connections} gauge, so nothing observable is lost.
 * Mirrored across backend/frontend per the no-shared-module convention, each with its own
 * module-local stream path.
 */
@Component
public class NotificationStreamObservationPredicate implements ObservationPredicate {

  /** Micrometer/Spring MVC name of the inbound HTTP server request observation. */
  private static final String HTTP_SERVER_REQUESTS = "http.server.requests";

  /** Exact request path of the notification SSE relay on this module (no path variables). */
  private static final String STREAM_PATH = "/notifications/stream";

  /**
   * Skips the {@code http.server.requests} observation for the notification SSE relay and lets
   * every other observation through unchanged. Matches on the raw request path ({@link
   * HttpServletRequest#getRequestURI()}, query string already excluded) because the endpoint
   * carries no path variables, so the actual path equals its route template.
   *
   * @param name the observation name being evaluated by the registry
   * @param context the observation context; a {@link ServerRequestObservationContext} for inbound
   *     HTTP server requests
   * @return {@code false} to drop the observation for the SSE relay endpoint, {@code true} to
   *     record every other observation
   */
  @Override
  public boolean test(String name, Observation.Context context) {
    if (!HTTP_SERVER_REQUESTS.equals(name)
        || !(context instanceof ServerRequestObservationContext serverContext)) {
      return true;
    }
    HttpServletRequest request = serverContext.getCarrier();
    return request == null || !STREAM_PATH.equals(request.getRequestURI());
  }
}
