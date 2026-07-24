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
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
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
 */
@Slf4j
@RequiredArgsConstructor
public class PendingApprovalAccessFilter extends OncePerRequestFilter {

  /** The synthetic authority a PENDING/REJECTED user carries (and nothing else). */
  static final String PENDING_AUTHORITY = "ROLE_PENDING_APPROVAL";

  /** The only {@code /api} endpoint a pending user may reach (drives the waiting-page routing). */
  static final String SELF_STATUS_PATH = "/api/v1/users/me/registration-status";

  /** Stable machine-readable code the frontend maps to the waiting-page routing. */
  static final String CODE_PENDING_APPROVAL = "PENDING_APPROVAL";

  /** App-wide correlation-id response header, mirroring {@code LoggingProperties} default. */
  static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

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

  private boolean isBlockedPendingApiCall(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    if (!path.startsWith("/api/") || path.equals(SELF_STATUS_PATH)) {
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
   * @param request the rejected request (its URI becomes the {@code instance})
   * @param response the response to write the problem body into
   * @throws IOException if serialization or writing the body fails
   */
  private void writeForbidden(HttpServletRequest request, HttpServletResponse response)
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
