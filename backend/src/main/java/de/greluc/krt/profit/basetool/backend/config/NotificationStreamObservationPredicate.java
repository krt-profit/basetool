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

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

/**
 * Excludes the long-lived notification SSE endpoint from the {@code http.server.requests}
 * observation, so stream lifetimes do not distort the latency metric (REQ-OBS-009).
 */
@Component
public class NotificationStreamObservationPredicate implements ObservationPredicate {

  /** Micrometer/Spring MVC name of the inbound HTTP server request observation. */
  private static final String HTTP_SERVER_REQUESTS = "http.server.requests";

  /** Exact request path of the notification SSE endpoint on this module (no path variables). */
  private static final String STREAM_PATH = "/api/v1/notifications/stream";

  /**
   * Drops the {@code http.server.requests} observation for the notification SSE endpoint, matched
   * on {@link HttpServletRequest#getRequestURI()}.
   *
   * @param name the observation name
   * @param context the observation context; a {@link ServerRequestObservationContext} for inbound
   *     HTTP requests
   * @return {@code false} for the SSE stream endpoint, {@code true} otherwise
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
