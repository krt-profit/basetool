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
 * Relays the originating client IP (snapshotted by {@link ClientIpContextFilter}) to the backend
 * via the {@code X-Forwarded-For} header on every outbound {@code WebClient} call.
 *
 * <p>The backend is a pure resource server reached only server-side by this frontend, so without
 * this relay its per-IP rate limiter attributes every request to the single frontend container IP
 * and collapses each per-client / per-endpoint budget into one shared org-wide bucket — letting a
 * single caller trip a public endpoint's limit for everyone (security audit DOS-1). The backend
 * already honours {@code X-Forwarded-For} only from its configured trusted proxies (the frontend
 * container) and resolves the client from the chain by walking it right-to-left, skipping its own
 * trusted hops (see {@code backend ClientIpContextFilter.resolveClientIp}), so relaying the
 * resolved IP restores per-client isolation while reusing the backend's existing, hardened limiter
 * rather than duplicating it on the frontend.
 *
 * <p>An existing {@code X-Forwarded-For} on the outbound request is never overwritten, and a
 * request with no bound client IP (background task / scheduled job) degrades silently to "no header
 * added".
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
