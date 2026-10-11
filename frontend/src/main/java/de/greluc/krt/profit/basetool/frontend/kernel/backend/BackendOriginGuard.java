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

package de.greluc.krt.profit.basetool.frontend.kernel.backend;

import java.net.URI;
import java.util.Locale;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

/**
 * The first filter of every backend {@code WebClient}: refuses a request whose URL is not on the
 * configured backend origin (scheme, host and port of {@code app.backend-url}) before any later
 * filter runs, so the OAuth2 filter never attaches the member's bearer to another host
 * (REQ-FE-029).
 *
 * @param scheme the backend scheme, lower case
 * @param host the backend host, lower case
 * @param port the backend port, the scheme's default when the URL names none
 */
public record BackendOriginGuard(@NotNull String scheme, @NotNull String host, int port) {

  /**
   * Reads the origin of the configured backend URL.
   *
   * @param backendUrl the absolute backend base URL
   * @return the guard for that origin
   * @throws IllegalArgumentException when the URL has no scheme or host
   */
  @NotNull
  public static BackendOriginGuard forBaseUrl(@NotNull String backendUrl) {
    URI base = URI.create(backendUrl);
    if (base.getScheme() == null || base.getHost() == null) {
      throw new IllegalArgumentException("app.backend-url must be an absolute http(s) URL");
    }
    String scheme = base.getScheme().toLowerCase(Locale.ROOT);
    return new BackendOriginGuard(
        scheme, base.getHost().toLowerCase(Locale.ROOT), effectivePort(scheme, base.getPort()));
  }

  /**
   * Whether a request URL is on the backend origin.
   *
   * @param url the request URL, absolute after the base URL was applied
   * @return {@code true} for the same scheme, host and effective port
   */
  public boolean admits(@Nullable URI url) {
    if (url == null || url.getScheme() == null || url.getHost() == null) {
      return false;
    }
    String requestScheme = url.getScheme().toLowerCase(Locale.ROOT);
    return scheme.equals(requestScheme)
        && host.equals(url.getHost().toLowerCase(Locale.ROOT))
        && port == effectivePort(requestScheme, url.getPort());
  }

  /**
   * The exchange filter enforcing {@link #admits(URI)}; a refused request fails with {@link
   * BackendOriginViolationException} and reaches neither a later filter nor the network.
   *
   * @return the filter
   */
  @NotNull
  public ExchangeFilterFunction filter() {
    return (request, next) ->
        admits(request.url())
            ? next.exchange(request)
            : Mono.error(new BackendOriginViolationException(request.method(), request.url()));
  }

  private static int effectivePort(String scheme, int port) {
    if (port >= 0) {
      return port;
    }
    return "https".equals(scheme) ? 443 : 80;
  }
}
