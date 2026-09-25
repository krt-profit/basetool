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

import de.greluc.krt.profit.basetool.logging.LogSafe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Emits one structured access-log line per frontend request, on INFO or on WARN for slow requests;
 * static resources, actuator and swagger assets are skipped (REQ-OBS-001).
 *
 * <p>The notification SSE relay ({@value #STREAM_PATH}) is never logged as slow. The line appends
 * the whitelisted {@value #FRAGMENT_PARAM} value and an {@code ajax} flag from {@value
 * #AJAX_HEADER}; the query string is otherwise never logged, and values pass through {@link
 * LogSafe} (REQ-OBS-004).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RequestLoggingFilter extends OncePerRequestFilter implements Ordered {

  /** Request path of the long-lived notification SSE relay, excluded from slow-request WARNs. */
  private static final String STREAM_PATH = "/notifications/stream";

  /**
   * The one query parameter whose value the access line may carry: the Thymeleaf fragment name a
   * {@code krtFetch} refresh asks for. It is a server-defined identifier the page echoes back and
   * cannot carry a callsign or any other personal datum the way a search term can — which is why it
   * is whitelisted by name rather than the query string being logged as a whole.
   */
  private static final String FRAGMENT_PARAM = "fragment";

  /**
   * Truncation bound for the logged {@value #FRAGMENT_PARAM} value. Real fragment names are short
   * identifiers ({@code results}, {@code detail}, {@code leitungSections}); a longer value is a
   * crafted request and only its head is worth keeping.
   */
  private static final int MAX_FRAGMENT_LENGTH = 48;

  /** Header {@code krtFetch} sets on every AJAX mutation and fragment refresh. */
  private static final String AJAX_HEADER = "X-Requested-With";

  /** The {@value #AJAX_HEADER} value that marks a request as an XHR rather than a navigation. */
  private static final String AJAX_HEADER_VALUE = "XMLHttpRequest";

  private final LoggingProperties loggingProperties;

  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain filterChain)
      throws ServletException, IOException {
    long start = System.nanoTime();
    try {
      filterChain.doFilter(request, response);
    } finally {
      long durationMs = (System.nanoTime() - start) / 1_000_000L;
      int status = response.getStatus();
      String method = request.getMethod();
      String path = request.getRequestURI();
      String marker = ajaxMarker(request);
      if (durationMs >= loggingProperties.slowRequestThresholdMs() && !STREAM_PATH.equals(path)) {
        log.warn("Slow request {} {}{} -> {} in {} ms", method, path, marker, status, durationMs);
      } else if (log.isInfoEnabled()) {
        log.info("{} {}{} -> {} in {} ms", method, path, marker, status, durationMs);
      }
    }
  }

  /**
   * Builds the suffix that marks a live-update refresh, e.g. {@code " [fragment=results
   * ajax=true]"}, omitting either half when it does not apply.
   *
   * @param request the request being logged
   * @return the marker to append after the path, empty for a plain navigation, never {@code null}
   */
  @NotNull
  private static String ajaxMarker(@NotNull HttpServletRequest request) {
    String fragment = whitelistedQueryParam(request.getQueryString(), FRAGMENT_PARAM);
    boolean ajax = AJAX_HEADER_VALUE.equalsIgnoreCase(request.getHeader(AJAX_HEADER));
    if ((fragment == null || fragment.isBlank()) && !ajax) {
      return "";
    }
    StringBuilder marker = new StringBuilder(" [");
    if (fragment != null && !fragment.isBlank()) {
      marker.append("fragment=").append(LogSafe.text(fragment, MAX_FRAGMENT_LENGTH));
      if (ajax) {
        marker.append(' ');
      }
    }
    if (ajax) {
      marker.append("ajax=true");
    }
    return marker.append(']').toString();
  }

  /**
   * Picks one named parameter out of the raw query string without triggering body parsing.
   *
   * <p>The value is returned still percent-encoded.
   *
   * @param queryString the raw query string, or {@code null} when the request has none
   * @param name the whitelisted parameter name to extract
   * @return the raw value, or {@code null} if the parameter is not present
   */
  @Nullable
  private static String whitelistedQueryParam(@Nullable String queryString, @NotNull String name) {
    if (queryString == null || queryString.isEmpty()) {
      return null;
    }
    String prefix = name + "=";
    for (String pair : queryString.split("&")) {
      if (pair.startsWith(prefix)) {
        return pair.substring(prefix.length());
      }
    }
    return null;
  }

  @Override
  protected boolean shouldNotFilter(@NotNull HttpServletRequest request) {
    String uri = PublicPaths.relativePath(request);
    return uri.endsWith(".css")
        || uri.endsWith(".js")
        || uri.endsWith(".ico")
        || uri.endsWith(".woff")
        || uri.endsWith(".woff2")
        || uri.contains("/images/")
        || uri.contains("/logos/")
        || uri.contains("/fonts/")
        || uri.startsWith("/actuator/")
        || uri.startsWith("/webjars/");
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE - 50;
  }
}
