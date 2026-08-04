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

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.support.AuthenticatedSubject;
import de.greluc.krt.profit.basetool.backend.support.ProblemResponseFactory;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.slf4j.MDC;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.PathContainer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import tools.jackson.databind.ObjectMapper;

/**
 * Backend enforcement of "a PENDING/REJECTED registration has no access" (REQ-SEC-017).
 *
 * <p>{@link CustomJwtGrantedAuthoritiesConverter} already short-circuits a non-approved user to the
 * single authority {@code ROLE_PENDING_APPROVAL}, but such a user is still <em>authenticated</em> —
 * so the many writes gated only on {@code @PreAuthorize("isAuthenticated()")} (e.g. personal-
 * inventory create/delete) would otherwise be reachable by a pending user calling the API directly,
 * bypassing the frontend's waiting-page redirect (which is UX, not the access boundary). This
 * filter closes that gap: any authenticated caller whose sole authority is the pending marker is
 * refused with {@code 403} on every {@code /api/**} endpoint, with one deliberate exception — the
 * registration-status endpoint the frontend reads to route them to the waiting page.
 *
 * <p>Runs after the bearer-token authentication filter (so the authorities are already assembled)
 * and is a no-op for every approved/role-bearing user, so it adds no risk to the normal path.
 *
 * <p>The 403 body is a full RFC&nbsp;7807 problem document mirroring {@code GlobalExceptionHandler}
 * (RFC-7807 hardening, REQ-API-004): {@code type} built off {@link AppProblemProperties}, localized
 * {@code title}/{@code detail}, the stable {@code code} {@code PENDING_APPROVAL}, and a {@code
 * correlationId}. Because this filter runs before {@code CorrelationIdFilter}, no request-scoped id
 * exists yet, so a fresh one is minted, echoed as the {@code X-Correlation-Id} response header and
 * logged. Serialization goes through the shared {@link ObjectMapper} so every field is safely
 * JSON-escaped (the request URI in {@code instance} is attacker-controlled).
 *
 * <p>Running that early also means the {@code userId} MDC key is still unset — {@code
 * CorrelationIdFilter} is its only writer in the backend and it never runs on a request this filter
 * rejects. The block line would therefore claim {@code anonymous} (the logback pattern's default)
 * for a caller who is demonstrably authenticated, which is worse than no value at all: an identical
 * controller-level denial renders the real {@code sub}. {@link #writeForbidden} therefore stamps
 * the JWT {@code sub} for the duration of the rejection write and removes it again, mirroring the
 * ownership discipline the minted correlation id follows. Only the {@code sub} — never the callsign
 * or e-mail (REQ-OBS-004).
 */
@Slf4j
@RequiredArgsConstructor
public class PendingApprovalAccessFilter extends OncePerRequestFilter {

  /** The synthetic authority a PENDING/REJECTED user carries (and nothing else). */
  static final String PENDING_AUTHORITY = "ROLE_PENDING_APPROVAL";

  /** The only {@code /api} endpoint a pending user may reach (drives the waiting-page routing). */
  static final String SELF_STATUS_PATH = "/api/v1/users/me/registration-status";

  /** Parses the two patterns below once; matching is per request and allocation-light. */
  private static final PathPatternParser PATH_PARSER = PathPatternParser.defaultInstance;

  /**
   * The surface this filter guards.
   *
   * <p>Matched as a parsed {@link PathPattern} rather than with {@code
   * requestUri.startsWith("/api/")}, because {@code getRequestURI()} is the <em>raw</em>
   * percent-encoded URI while Spring MVC routes on the <em>decoded</em> path. A request for {@code
   * /%61pi/v1/missions} fails a raw prefix test, so the gate would wave it through, and {@code
   * RequestMappingHandlerMapping} would then decode {@code %61pi} to {@code api} and dispatch it —
   * handing a PENDING/REJECTED account exactly the {@code isAuthenticated()}-only writes this
   * filter exists to deny. The default {@code StrictHttpFirewall} blocks {@code %2e}, {@code %2f},
   * {@code %25} and friends, but not {@code %61}.
   *
   * <p>{@link PathPattern} matches on {@code PathSegment#valueToMatch()}, which is decoded, so
   * filter and routing agree. Note that {@code ServletRequestPathUtils} does <em>not</em> solve
   * this: {@code PathContainer.Element#value()} is contractually the unmodified original.
   *
   * <p>Precedent: {@code filter.RateLimitingFilter} matches its configured paths the same way, and
   * {@link TermsAcceptanceAccessFilter} guards this same surface with the same construct — the two
   * gates must agree on what {@code /api} means, so neither may drift back to a raw prefix test.
   */
  private static final PathPattern API_SCOPE = PATH_PARSER.parse("/api/**");

  /**
   * {@link #SELF_STATUS_PATH} as a parsed pattern, so the one exemption is decided on the same
   * decoded path as the scope above — an encoded spelling of the status endpoint must stay
   * reachable or a client that happens to percent-encode loses its only route to the waiting page.
   * Being a literal pattern (no wildcard), it keeps the exact-match semantics of the {@code equals}
   * test it replaced: a future {@code /api/v1/users/me/registration-status-export} is not exempt.
   */
  private static final PathPattern SELF_STATUS_PATTERN = PATH_PARSER.parse(SELF_STATUS_PATH);

  /** Stable machine-readable code the frontend maps to the waiting-page routing. */
  static final String CODE_PENDING_APPROVAL = "PENDING_APPROVAL";

  /** App-wide correlation-id response header, mirroring {@code LoggingProperties} default. */
  static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

  /**
   * SLF4J MDC key the logback pattern renders as {@code [<userId>]}, mirroring {@code
   * LoggingProperties}' default. Hardcoded like {@link #CORRELATION_ID_HEADER}: it is a wire
   * constant shared with the logback pattern, not a per-deployment override, and reading it from
   * {@code LoggingProperties} here would make this filter depend on a bean it is constructed
   * without.
   */
  static final String MDC_USER_ID = "userId";

  private final MessageSource messageSource;
  private final ProblemResponseFactory problemResponseFactory;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;

  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain filterChain)
      throws ServletException, IOException {
    if (isBlockedPendingApiCall(request)) {
      writeForbidden(request, response);
      return;
    }
    filterChain.doFilter(request, response);
  }

  /**
   * Decides whether this request must be refused: an {@code /api} call — other than the self-status
   * exemption — made by an authenticated caller carrying the pending marker.
   *
   * <p>The path is parsed into a {@link PathContainer} and matched against {@link #API_SCOPE} /
   * {@link #SELF_STATUS_PATTERN} rather than string-compared, so the gate sees the same decoded
   * path the dispatcher will route on (see {@link #API_SCOPE}).
   *
   * @param request the current request
   * @return {@code true} when the request must be answered with the 403, {@code false} when it may
   *     proceed down the chain
   */
  private boolean isBlockedPendingApiCall(HttpServletRequest request) {
    PathContainer path =
        PathContainer.parsePath(
            request.getRequestURI().substring(request.getContextPath().length()));
    if (!API_SCOPE.matches(path) || SELF_STATUS_PATTERN.matches(path)) {
      return false;
    }
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return auth != null
        && auth.isAuthenticated()
        && auth.getAuthorities().stream()
            .anyMatch(authority -> PENDING_AUTHORITY.equals(authority.getAuthority()));
  }

  /**
   * Writes the RFC&nbsp;7807 403 body with a minted, logged and header-echoed {@code
   * correlationId}. Localizes {@code title}/{@code detail} from the request's {@code
   * Accept-Language} ({@code LocaleContextHolder} is not yet populated this early in the filter
   * chain, so {@code request.getLocale()} is the authoritative source), serializing via the shared
   * {@link ObjectMapper} for uniform JSON escaping.
   *
   * <p>Owns the {@code userId} MDC key for the duration of the write (see {@link
   * #stampAuthenticatedSub()}) and removes it again in a {@code finally}, so the DEBUG line names
   * the blocked caller's {@code sub} without the value bleeding into the next request on a pooled
   * or virtual thread.
   *
   * @param request the rejected request (its URI becomes the {@code instance})
   * @param response the response to write the problem body into
   * @throws IOException if serialization or writing the body fails
   */
  private void writeForbidden(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    boolean userIdOwned = stampAuthenticatedSub();
    try {
      writeForbiddenBody(request, response);
    } finally {
      if (userIdOwned) {
        MDC.remove(MDC_USER_ID);
      }
    }
  }

  /**
   * Puts the authenticated caller's subject into the {@code userId} MDC key, unless something
   * already populated that key (the existing value then wins) or the caller has no readable subject
   * (the key then stays unset, so the logback pattern's {@code anonymous} default stays truthful).
   *
   * <p>"No readable subject" is not the same as "no token". A pending caller acting through the
   * ingest gateway carries a subject and no token (ADR-0129) and IS stamped; a username/password
   * caller is not, because its name is a callsign and REQ-OBS-004 keeps that out of the log. The
   * distinction lives in {@code AuthenticatedSubject}, not here.
   *
   * <p>Deliberately duplicated in {@code SecurityProblemResponseHandler} instead of extracted into
   * the {@code logging} package: {@code logging.CorrelationIdFilter} already depends on {@code
   * config.LoggingProperties}, so a {@code config -> logging} helper call would close a package
   * cycle (ADR-0047).
   *
   * @return {@code true} when this call stamped the key and must therefore remove it again, {@code
   *     false} when nothing was stamped
   */
  private static boolean stampAuthenticatedSub() {
    String existing = MDC.get(MDC_USER_ID);
    if (existing != null && !existing.isBlank()) {
      return false;
    }
    // Asked of AuthenticatedSubject, not of the type — an acting member (ADR-0129) is a named
    // caller with no token, and a refusal logged as anonymous is the one line forensics would need.
    String sub =
        AuthenticatedSubject.of(SecurityContextHolder.getContext().getAuthentication())
            .orElse(null);
    if (sub == null) {
      return false;
    }
    MDC.put(MDC_USER_ID, sub);
    return true;
  }

  /**
   * Builds and writes the 403 problem document itself — correlation id, DEBUG log line, {@code
   * basetool_http_error_total} increment, status, headers and the serialized body — with the {@code
   * userId} MDC key already stamped by the caller.
   *
   * @param request the rejected request (its URI becomes the {@code instance})
   * @param response the response to write the problem body into
   * @throws IOException if serialization or writing the body fails
   */
  private void writeForbiddenBody(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String correlationId = UUID.randomUUID().toString();
    Locale locale = request.getLocale();
    final String title =
        messageSource.getMessage("problem.pending_approval.title", null, "Forbidden", locale);
    final String detail =
        messageSource.getMessage(
            "problem.pending_approval.detail", null, "Account is pending admin approval.", locale);

    // Logged at DEBUG, not WARN: a pending user's shell polls several endpoints on every page load,
    // so an approved-status-pending session emits a steady stream of these 403s — an expected,
    // self-inflicted condition, not an operational warning. The metric below (not this line) is the
    // monitoring signal, so dropping to DEBUG does not blind the mass-403 detector.
    log.debug(
        "Pending-approval user blocked on {} {} [correlationId={}]",
        request.getMethod(),
        request.getRequestURI(),
        correlationId);

    // REQ-OBS-011: this 403 is written at the servlet-filter level and bypasses
    // GlobalExceptionHandler, so it never reaches basetool_http_error_total via the advice. Count
    // it
    // here (mirroring IdentityProviderUnavailableFilter) so a converter / approval-sync regression
    // that mass-403s legitimate users surfaces on PendingApprovalBlockSpike, not in the log (which
    // is now DEBUG for this expected condition).
    meterRegistry
        .counter(MetricNames.HTTP_ERROR, MetricNames.TAG_CODE, CODE_PENDING_APPROVAL)
        .increment();

    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setHeader(CORRELATION_ID_HEADER, correlationId);
    // Write UTF-8 bytes directly rather than through getWriter(): the localized title/detail may
    // contain non-ASCII (German umlauts) and the servlet writer defaults to ISO-8859-1.
    ProblemDetail problem =
        problemResponseFactory.problem(
            HttpStatus.FORBIDDEN,
            title,
            detail,
            request.getRequestURI(),
            "pending-approval",
            CODE_PENDING_APPROVAL,
            correlationId);
    response.getOutputStream().write(objectMapper.writeValueAsBytes(problem));
  }
}
