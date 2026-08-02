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

import de.greluc.krt.profit.basetool.frontend.logging.LogSafe;
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
 * Emits a single structured access-log line per request on INFO (or WARN for slow requests) in the
 * frontend module. Correlation id and user id are rendered via the MDC pattern in {@code
 * logback-spring.xml} and therefore not duplicated in the message body.
 *
 * <p>Static resources, the actuator and swagger assets are skipped to keep the access log focused
 * on real user traffic.
 *
 * <p>The notification SSE relay ({@value #STREAM_PATH}) is never escalated to the "Slow request"
 * WARN branch: Spring MVC books the async request's whole lifetime as the elapsed duration, so a
 * relay held open for up to 30 minutes ({@code NotificationPageController.STREAM_TIMEOUT_MS}) would
 * cross the slow-request threshold on every close and flood the access log with false-positive WARN
 * lines. It still gets its one INFO access-log line. This is the access-log mirror of {@link
 * NotificationStreamObservationPredicate} dropping the same endpoint from {@code
 * http.server.requests} (REQ-OBS-001/-009).
 *
 * <p><b>Why the line carries an AJAX marker.</b> {@link HttpServletRequest#getRequestURI()} drops
 * the query string, so a {@code ?fragment=…} live-update refresh used to be logged byte-for-byte
 * identically to a full page load of the same path. That made the commonest live-update report —
 * "the list did not refresh after booking" — unanswerable from the logs: nothing said whether the
 * refresh request was even sent. {@code http.server.requests} cannot answer it either; it is keyed
 * on the URI template and collapses the {@code ?fragment=} refresh into the same page-load bucket.
 * The access line therefore appends the whitelisted {@value #FRAGMENT_PARAM} parameter value and an
 * {@code ajax} flag derived from the {@value #AJAX_HEADER} header — as a suffix on the existing
 * line, never a second line.
 *
 * <p>The query string is emphatically <b>not</b> logged wholesale: {@code /users/search?query=} and
 * the free-text {@code q} filters carry callsigns, which REQ-OBS-004 forbids outright. Only
 * parameter names on the explicit whitelist are read, and their values still go through {@link
 * LogSafe} — a query parameter is client-supplied free text whatever its name, so it is stripped of
 * control characters and truncated before it reaches the logger (log forging, CWE-117).
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
   * Builds the suffix that distinguishes a live-update refresh from a full page load of the same
   * path: {@code " [fragment=results ajax=true]"}, either half omitted when it does not apply, and
   * the empty string for a plain navigation so those lines keep their previous shape.
   *
   * @param request the request being logged
   * @return the marker to append after the path, possibly empty, never {@code null}
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
   * Picks one named parameter out of the raw query string, or {@code null} when it is not present.
   *
   * <p>Scans {@code queryString} directly rather than calling {@code request.getParameter(...)} for
   * two reasons. First, this runs in the filter's {@code finally} block: on a form POST that never
   * reached a controller, {@code getParameter} would trigger body parsing after the response is
   * already committed. Second, reading the raw query string makes the whitelist literal — only the
   * named parameter is ever looked at, so a {@code query=} or {@code q=} carrying a callsign cannot
   * reach a log line by accident (REQ-OBS-004).
   *
   * <p>The value is returned percent-encoded, undecoded on purpose: a fragment name is a plain
   * identifier that needs no decoding, decoding client input here can throw on a malformed escape,
   * and leaving {@code %0A} literal is one less way to smuggle a line break past {@link LogSafe}.
   *
   * @param queryString the raw query string, or {@code null} when the request has none
   * @param name the whitelisted parameter name to extract
   * @return the raw (still percent-encoded) value, or {@code null} if the parameter is not present
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
    String uri = request.getRequestURI();
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
