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
 * observation (REQ-OBS-009), so its lifetime-long duration does not distort the latency metrics.
 *
 * <p>Relay health is tracked by the {@code basetool_notification_relay_connections} gauge instead.
 */
@Component
public class NotificationStreamObservationPredicate implements ObservationPredicate {

  /** Micrometer/Spring MVC name of the inbound HTTP server request observation. */
  private static final String HTTP_SERVER_REQUESTS = "http.server.requests";

  /** Exact request path of the notification SSE relay on this module (no path variables). */
  private static final String STREAM_PATH = "/notifications/stream";

  /**
   * Skips the {@code http.server.requests} observation for the notification SSE relay, matched on
   * {@link HttpServletRequest#getRequestURI()}, and passes every other observation.
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
