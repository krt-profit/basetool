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

import de.greluc.krt.profit.basetool.frontend.logging.LogSafe;
import de.greluc.krt.profit.basetool.frontend.metrics.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * Receives the browser-side error beacon posted by {@code static/js/krt-client-error.js} (audit
 * finding M12) and turns it into the two server-side signals a browser failure otherwise leaves
 * behind: a DEBUG log line and the {@code basetool_client_error_total{kind}} counter.
 *
 * <p><b>What this actually covers.</b> Not "a JS error reached production" in general — {@code
 * :frontend:lintJs} and the Playwright suite already catch the syntax-error half of that, since a
 * file that does not parse fails the gate or breaks every end-to-end flow. What neither catches is
 * the runtime half: a handler that parses cleanly and throws a {@code TypeError} on one browser, on
 * one data shape, most often against a DOM that a {@code krtFetch} fragment swap has just replaced
 * underneath it. That failure produces no request, so there is no access-log line, no {@code
 * http.server.requests} sample and no counter — "the button did nothing" is a user report and
 * nothing else. This endpoint is the missing signal for exactly that case.
 *
 * <p><b>The payload is attacker-controllable and self-triggerable</b> — anyone with a session can
 * POST anything here, repeatedly, and any page can make the browser do it by throwing. Every design
 * choice below follows from that:
 *
 * <ul>
 *   <li>Exactly five fields are accepted ({@link ClientErrorReport}); Jackson silently drops
 *       anything else, so a caller cannot smuggle a stack trace, {@code document.title}, DOM
 *       content, form values or {@code location.search} into the log by adding a property.
 *   <li>Both free-text fields go through {@link LogSafe#text(String, int)} at {@value
 *       #MAX_FIELD_LENGTH} characters, which strips control characters (no forged {@code ERROR}
 *       line, CWE-117) and bounds the line length.
 *   <li>{@code source} additionally loses its query string and fragment <em>server-side</em>, so a
 *       token or search term smuggled into a script URL never reaches the log even if the client
 *       half is bypassed.
 *   <li>{@code kind} is resolved against {@link #ALLOWED_KINDS} — the three {@code MetricNames}
 *       literals — and a report matching none of them is rejected without creating a meter. Echoing
 *       a client-supplied tag value would be an unbounded, attacker-chosen label (REQ-OBS-006).
 *   <li>The user identity comes from the MDC ({@code CorrelationIdFilter} puts the {@code sub}
 *       there), never from the request body. No callsign, name, e-mail, token or client IP is
 *       logged (REQ-OBS-004).
 * </ul>
 *
 * <p><b>DEBUG is not optional here.</b> The endpoint is self-triggerable at frame rate; at INFO or
 * WARN a single looping error handler in one tab would flood the logs and, on the ERROR path, trip
 * the {@code LogbackErrorSpike} alert with a client-side fault. The counter is the always-on signal
 * — it is what an alert or dashboard watches — and the log line is raised to DEBUG via {@code
 * /actuator/loggers} for the duration of a support call, when a specific user's failure needs its
 * message and script URL. Following the same rule, the malformed-body handler below logs at DEBUG
 * too: a client that can send garbage on demand must not be able to author WARN lines.
 *
 * <p>Authorization is doubled on purpose: {@code SecurityConfig}'s {@code
 * anyRequest().authenticated()} catch-all already gates the path (an anonymous POST is answered by
 * the OIDC entry point and never reaches this class), and {@link PreAuthorize} keeps that true if
 * the path is ever moved into a {@code permitAll} block. CSRF protection is the frontend default;
 * the beacon replays the {@code _csrf} meta tags as a header.
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
          MetricNames.CLIENT_ERROR_RESOURCE_ERROR);

  private final MeterRegistry meterRegistry;

  /**
   * The complete accepted payload — five fields and nothing else. Unknown JSON properties are
   * ignored by the frontend's Jackson defaults, so the shape of this record <em>is</em> the input
   * allowlist: a field that is not declared here can never reach a log line or a metric.
   *
   * @param message the browser's exception message or promise-rejection reason; free text, treated
   *     as hostile and sanitised before logging
   * @param source the URL of the script that failed; its query string and fragment are stripped
   *     server-side before the value is logged
   * @param line the 1-based line number within {@code source}, or {@code null} when the browser did
   *     not supply one (promise rejections never do)
   * @param column the 1-based column number within {@code source}, or {@code null}
   * @param kind the browser-error class; only the three {@link #ALLOWED_KINDS} literals are
   *     accepted, any other value causes the whole report to be rejected
   */
  public record ClientErrorReport(
      @Nullable String message,
      @Nullable String source,
      @Nullable Integer line,
      @Nullable Integer column,
      @Nullable String kind) {}

  /**
   * Records one browser-side failure: bumps {@code basetool_client_error_total} with the
   * server-resolved {@code kind} tag and writes the sanitised detail to the DEBUG log.
   *
   * @param report the beacon payload; {@code null} when the body was absent, which is rejected the
   *     same way an unknown {@code kind} is
   * @return {@code 204 No Content} once the report has been counted and logged, or {@code 400 Bad
   *     Request} when the body is missing or carries a {@code kind} outside {@link #ALLOWED_KINDS}
   *     — in which case nothing is counted, so a crafted payload cannot create a metric series
   */
  @PostMapping(PATH)
  public ResponseEntity<Void> report(
      @RequestBody(required = false) @Nullable ClientErrorReport report) {
    String kind = report == null ? null : report.kind();
    if (kind == null || !ALLOWED_KINDS.contains(kind)) {
      // DEBUG, not WARN: rejecting a crafted payload is the endpoint working as designed, and the
      // caller can repeat it at will. The raw value is still sanitised — it is about to be logged.
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
        LogSafe.text(stripQuery(report.source()), MAX_FIELD_LENGTH),
        report.line(),
        report.column());
    return ResponseEntity.noContent().build();
  }

  /**
   * Answers a malformed, unparseable or wrongly-typed beacon body with a bare {@code 400}.
   *
   * <p>Without this controller-local handler both exceptions would reach {@code
   * GlobalExceptionHandler}'s {@code Exception} catch-all, which logs at ERROR and renders a 500
   * page — handing any authenticated caller a one-request ERROR-log generator on an endpoint they
   * can hammer, and one that would trip the {@code LogbackErrorSpike} alert. A controller-local
   * {@code @ExceptionHandler} wins over the {@code @ControllerAdvice} one, so the flood vector is
   * closed here rather than by weakening the global handler. For the same reason the mapping
   * declares no {@code consumes} restriction: a {@code consumes} mismatch is raised during handler
   * <em>mapping</em>, before this controller is selected, and would therefore escape to the global
   * advice — whereas a converter mismatch during argument resolution lands here.
   *
   * @param e the parse or content-type failure, logged only as its type — the offending body is
   *     never echoed back into the log
   * @return {@code 400 Bad Request} with no body
   */
  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    HttpMediaTypeNotSupportedException.class
  })
  public ResponseEntity<Void> handleUnreadableBody(Exception e) {
    log.debug("Unreadable client error report body [exception={}]", e.getClass().getSimpleName());
    return ResponseEntity.badRequest().build();
  }

  /**
   * Returns {@code source} truncated at the first {@code ?} or {@code #}, so neither a query string
   * nor a fragment can carry a token, a search term or any other request detail into the log. The
   * client strips them too; this is the half that is actually a guarantee, because the client half
   * can be bypassed by posting to the endpoint directly.
   *
   * @param source the reported script URL, possibly {@code null}
   * @return the URL up to its first {@code ?} or {@code #}, or {@code null} when {@code source} was
   *     {@code null}
   */
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
