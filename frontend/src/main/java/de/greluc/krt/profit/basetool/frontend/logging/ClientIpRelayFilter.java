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

package de.greluc.krt.profit.basetool.frontend.logging;

import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;

/**
 * Relays the client IP resolved by {@link ClientIpContextFilter} to the backend as {@code
 * X-Forwarded-For} on every outbound {@code WebClient} call, so the backend's per-IP rate limiter
 * sees the real client.
 *
 * <p>Never overwrites an existing header; adds nothing when no client IP is bound.
 */
@Component
public class ClientIpRelayFilter {

  /** Header carrying the originating client IP to the backend's trusted-proxy-aware limiter. */
  public static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

  /**
   * Returns the filter function that adds {@code X-Forwarded-For} to outbound requests when a
   * client IP is bound for the current request.
   *
   * @return filter function for the WebClient pipeline; never {@code null}.
   */
  @NotNull
  public ExchangeFilterFunction relayClientIp() {
    return (request, next) -> {
      String clientIp = ClientIpContext.get();
      if (clientIp == null || request.headers().getFirst(FORWARDED_FOR_HEADER) != null) {
        return next.exchange(request);
      }
      return next.exchange(
          ClientRequest.from(request).header(FORWARDED_FOR_HEADER, clientIp).build());
    };
  }
}
