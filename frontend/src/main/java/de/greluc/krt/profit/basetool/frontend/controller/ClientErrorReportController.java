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

package de.greluc.krt.profit.basetool.frontend.controller;

import de.greluc.krt.profit.basetool.frontend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.logging.LogSafe;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives the browser error beacon from {@code krt-client-error.js} and records it as the {@code
 * basetool_client_error_total{kind}} counter plus a DEBUG log line.
 *
 * <p>The payload is untrusted: only the five {@link ClientErrorReport} fields are bound, free text
 * is sanitised via {@link LogSafe#text(String, int)}, {@code source} loses its query and fragment,
 * {@code kind} must be one of {@link #ALLOWED_KINDS} (REQ-OBS-006), and the user identity comes
 * from the MDC only (REQ-OBS-004). Logging stays at DEBUG because callers can trigger it at will.
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Slf4j
public class ClientErrorReportController {

  /** Beacon endpoint path; mirrored by {@code ENDPOINT} in {@code krt-client-error.js}. */
  public static final String PATH = "/internal/client-error";

  /**
   * Characters kept per free-text field before it reaches a logger. Generous enough for a browser
   * exception message and a script URL, short enough that a burst of reports cannot bloat the log.
   */
  static final int MAX_FIELD_LENGTH = 200;

  /**
   * The only {@code kind} values that produce a metric series. Resolved server-side because the tag
   * would otherwise be an unbounded, client-chosen label; mirrored by the {@code KIND_*} constants
   * in {@code krt-client-error.js} and pinned by {@code ClientErrorReportControllerTest}.
   */
  static final Set<String> ALLOWED_KINDS =
      Set.of(
          MetricNames.CLIENT_ERROR_SCRIPT_ERROR,
          MetricNames.CLIENT_ERROR_UNHANDLED_REJECTION,
          MetricNames.CLIENT_ERROR_RESOURCE_ERROR,
          MetricNames.CLIENT_ERROR_CSP_VIOLATION,
          MetricNames.CLIENT_ERROR_I18N_MISSING);

  private final MeterRegistry meterRegistry;

  /**
   * The complete accepted beacon payload; undeclared JSON properties are ignored.
   *
   * @param message the browser's exception message or rejection reason; sanitised before logging
   * @param source the failing script URL; query and fragment are stripped before logging
   * @param line the 1-based line number within {@code source}, or {@code null}
   * @param column the 1-based column number within {@code source}, or {@code null}
   * @param kind the browser-error class; must be one of {@link #ALLOWED_KINDS}
   */
  public record ClientErrorReport(
      @Nullable String message,
      @Nullable String source,
      @Nullable Integer line,
      @Nullable Integer column,
      @Nullable String kind) {}

  /**
   * Records one browser-side failure: increments {@code basetool_client_error_total} with the
   * resolved {@code kind} tag and logs the sanitised detail at DEBUG.
   *
   * @param report the beacon payload; {@code null} when the body was absent
   * @return {@code 204} once counted and logged, or {@code 400} for a missing body or an unknown
   *     {@code kind}, in which case nothing is counted
   */
  @PostMapping(PATH)
  public ResponseEntity<Void> report(
      @RequestBody(required = false) @Nullable ClientErrorReport report) {
    String kind = report == null ? null : report.kind();
    if (kind == null || !ALLOWED_KINDS.contains(kind)) {
      log.debug(
          "Rejected client error report with unknown kind={}",
          LogSafe.text(kind, MAX_FIELD_LENGTH));
      return ResponseEntity.badRequest().build();
    }
    meterRegistry.counter(MetricNames.CLIENT_ERROR, MetricNames.TAG_KIND, kind).increment();
    log.debug(
        "Client error reported [kind={}, message={}, source={}, line={}, column={}]",
        kind,
        LogSafe.text(report.message(), MAX_FIELD_LENGTH),
        LogSafe.text(
            MetricNames.CLIENT_ERROR_CSP_VIOLATION.equals(kind)
                ? originOnly(report.source())
                : stripQuery(report.source()),
            MAX_FIELD_LENGTH),
        report.line(),
        report.column());
    return ResponseEntity.noContent().build();
  }

  /**
   * Answers a malformed or wrongly-typed beacon body with a bare {@code 400}, logging only the
   * exception type, so the global handler's ERROR log and 500 page are never reached.
   *
   * @param e the parse or content-type failure
   * @return {@code 400 Bad Request} with no body
   */
  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    HttpMediaTypeNotSupportedException.class
  })
  public ResponseEntity<Void> handleUnreadableBody(@NotNull Exception e) {
    log.debug("Unreadable client error report body [exception={}]", e.getClass().getSimpleName());
    return ResponseEntity.badRequest().build();
  }

  /**
   * Reduces a CSP violation's blocked URI to its origin ({@code scheme://host[:port]}); a
   * non-hierarchical value is cut at its first {@code :}, {@code /}, {@code ?} or {@code #}.
   *
   * @param blocked the reported blocked URI, possibly {@code null}
   * @return the origin, the bare keyword or scheme, or {@code null} when {@code blocked} was {@code
   *     null}
   */
  @Contract("null -> null")
  static @Nullable String originOnly(@Nullable String blocked) {
    if (blocked == null) {
      return null;
    }
    try {
      URI uri = new URI(blocked.strip());
      if (uri.getScheme() != null && uri.getHost() != null) {
        return uri.getScheme()
            + "://"
            + uri.getHost()
            + (uri.getPort() >= 0 ? ":" + uri.getPort() : "");
      }
    } catch (URISyntaxException ignored) {
    }
    int cut = blocked.length();
    for (int i = 0; i < blocked.length(); i++) {
      char c = blocked.charAt(i);
      if (c == ':' || c == '/' || c == '?' || c == '#') {
        cut = i;
        break;
      }
    }
    return blocked.substring(0, cut);
  }

  /**
   * Returns {@code source} truncated at the first {@code ?} or {@code #}.
   *
   * @param source the reported script URL, possibly {@code null}
   * @return the URL up to its first {@code ?} or {@code #}, or {@code null} when {@code source} was
   *     {@code null}
   */
  @Contract("null -> null")
  private static @Nullable String stripQuery(@Nullable String source) {
    if (source == null) {
      return null;
    }
    int cut = source.length();
    int query = source.indexOf('?');
    if (query >= 0) {
      cut = query;
    }
    int fragment = source.indexOf('#');
    if (fragment >= 0 && fragment < cut) {
      cut = fragment;
    }
    return source.substring(0, cut);
  }
}
