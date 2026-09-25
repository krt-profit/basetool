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
import de.greluc.krt.profit.basetool.backend.support.RefusedSubjectWindow;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.PathContainer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import tools.jackson.databind.ObjectMapper;

/**
 * Refuses every {@code /api/**} request with 403 for an authenticated caller who is pending
 * approval (REQ-SEC-017) or holds no application role (REQ-SEC-053).
 *
 * <p>Exempt are the registration-status endpoint and the two anonymous reads of REQ-SEC-052. Runs
 * after {@link ActingMemberFilter}, so ingest requests are judged on the acting member. The body is
 * an RFC&nbsp;7807 problem document with a freshly minted correlation id.
 */
@Slf4j
@RequiredArgsConstructor
public class PendingApprovalAccessFilter extends OncePerRequestFilter {

  /** The synthetic authority a PENDING/REJECTED user carries (and nothing else). */
  static final String PENDING_AUTHORITY = "ROLE_PENDING_APPROVAL";

  /**
   * The synthetic authority an approved account with no application role carries (REQ-SEC-053).
   *
   * <p>Assembled in {@code CustomJwtGrantedAuthoritiesConverter#assembleFor}, so it reaches this
   * filter from the JWT path and from the ingest gateway's acting-member path alike. A marker, not
   * a permission: nothing grants on it, and this filter is the only thing that reads it.
   */
  static final String NO_ROLE_AUTHORITY = Roles.NO_ROLE_MARKER;

  /** The only {@code /api} endpoint a pending user may reach (drives the waiting-page routing). */
  static final String SELF_STATUS_PATH = "/api/v1/users/me/registration-status";

  /**
   * The two reads served without any token (REQ-SEC-052), exempt so a signed-in pending or
   * role-less member can still read them.
   */
  static final List<String> ANONYMOUS_READ_PATHS =
      List.of("/api/v1/app/version-policy", "/api/v1/terms/document");

  /** Shared parser for this filter's path patterns. */
  private static final PathPatternParser PATH_PARSER = PathPatternParser.defaultInstance;

  /**
   * The guarded surface {@code /api/**}, matched against the decoded path so percent-encoded
   * spellings cannot bypass the filter.
   */
  private static final PathPattern API_SCOPE = PATH_PARSER.parse("/api/**");

  /**
   * {@link #SELF_STATUS_PATH} as a literal pattern, matched on the decoded path with exact-match
   * semantics.
   */
  private static final PathPattern SELF_STATUS_PATTERN = PATH_PARSER.parse(SELF_STATUS_PATH);

  /** {@link #ANONYMOUS_READ_PATHS} parsed once, matched on the decoded path. */
  private static final List<PathPattern> ANONYMOUS_READ_PATTERNS =
      ANONYMOUS_READ_PATHS.stream().map(PATH_PARSER::parse).toList();

  /** Stable machine-readable code the frontend maps to the waiting-page routing. */
  static final String CODE_PENDING_APPROVAL = "PENDING_APPROVAL";

  /** Stable machine-readable code for the role-less refusal (REQ-SEC-053). */
  static final String CODE_NO_ROLE = "NO_ROLE";

  /** App-wide correlation-id response header, mirroring {@code LoggingProperties} default. */
  static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

  /** MDC key of the caller's subject, matching the logback pattern's {@code userId} placeholder. */
  static final String MDC_USER_ID = "userId";

  private final MessageSource messageSource;
  private final ProblemResponseFactory problemResponseFactory;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;

  /**
   * Distinct subjects refused with {@code NO_ROLE} in a rolling window, published as {@link
   * MetricNames#NO_ROLE_REFUSED_SUBJECTS}. The refusal rate cannot answer the question the alert
   * asks - see that constant.
   */
  private final RefusedSubjectWindow noRoleRefusedSubjects;

  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain filterChain)
      throws ServletException, IOException {
    Refusal refusal = refusalFor(request);
    if (refusal != null) {
      if (NO_ROLE_REFUSAL.equals(refusal)) {
        AuthenticatedSubject.of(SecurityContextHolder.getContext().getAuthentication())
            .map(PendingApprovalAccessFilter::toUuidOrNull)
            .ifPresent(noRoleRefusedSubjects::record);
      }
      writeForbidden(request, response, refusal);
      return;
    }
    filterChain.doFilter(request, response);
  }

  /**
   * One of the two refusal states, with its code and wording.
   *
   * @param code the stable machine-readable code on the problem body
   * @param titleKey message key for the localized title
   * @param detailKey message key for the localized detail
   * @param defaultDetail fallback detail when no bundle carries the key
   * @param type the problem type suffix
   * @param logSubject how the DEBUG line names the refused caller
   */
  private record Refusal(
      String code,
      String titleKey,
      String detailKey,
      String defaultDetail,
      String type,
      String logSubject) {}

  /** The pending/rejected refusal (REQ-SEC-017). */
  private static final Refusal PENDING_REFUSAL =
      new Refusal(
          CODE_PENDING_APPROVAL,
          "problem.pending_approval.title",
          "problem.pending_approval.detail",
          "Account is pending admin approval.",
          "pending-approval",
          "Pending-approval user");

  /** The role-less refusal (REQ-SEC-053). */
  private static final Refusal NO_ROLE_REFUSAL =
      new Refusal(
          CODE_NO_ROLE,
          "problem.no_role.title",
          "problem.no_role.detail",
          "Account holds no role. An administrator has to assign one.",
          "no-role",
          "Role-less user");

  /**
   * Decides whether the request must be refused, and on which ground.
   *
   * <p>The pending marker wins when both markers are present.
   *
   * @param request the current request
   * @return the refusal to write, or {@code null} when the request may proceed
   */
  @Nullable
  private Refusal refusalFor(@NotNull HttpServletRequest request) {
    PathContainer path =
        PathContainer.parsePath(
            request.getRequestURI().substring(request.getContextPath().length()));
    if (!API_SCOPE.matches(path)
        || SELF_STATUS_PATTERN.matches(path)
        || ANONYMOUS_READ_PATTERNS.stream().anyMatch(pattern -> pattern.matches(path))) {
      return null;
    }
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated()) {
      return null;
    }
    if (hasAuthority(auth, PENDING_AUTHORITY)) {
      return PENDING_REFUSAL;
    }
    return hasAuthority(auth, NO_ROLE_AUTHORITY) ? NO_ROLE_REFUSAL : null;
  }

  /**
   * Whether the caller carries a given authority, without building an intermediate collection.
   *
   * @param authentication the current authentication; never {@code null}
   * @param authority the authority name to look for
   * @return {@code true} as soon as one of the caller's authorities matches
   */
  private static boolean hasAuthority(
      @NotNull Authentication authentication, @NotNull String authority) {
    return authentication.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .anyMatch(authority::equals);
  }

  /**
   * Writes the localized RFC&nbsp;7807 403 body, stamping the caller's {@code sub} into the MDC for
   * the duration of the write and removing it afterwards.
   *
   * @param request the rejected request; its URI becomes the {@code instance}
   * @param response the response to write the problem body into
   * @param refusal which refusal to write
   * @throws IOException if serialization or writing the body fails
   */
  private void writeForbidden(
      HttpServletRequest request, HttpServletResponse response, Refusal refusal)
      throws IOException {
    boolean userIdOwned = stampAuthenticatedSub();
    try {
      writeForbiddenBody(request, response, refusal);
    } finally {
      if (userIdOwned) {
        MDC.remove(MDC_USER_ID);
      }
    }
  }

  /**
   * Parses a subject string into a {@link UUID}, or {@code null} when it is not one.
   *
   * @param subject the authenticated subject, as {@code AuthenticatedSubject} reports it
   * @return the parsed id, or {@code null} for a subject that is not a UUID (a service account, or
   *     a realm that does not mint UUID subjects) - such a caller simply does not enter the window
   */
  @Nullable
  private static UUID toUuidOrNull(String subject) {
    try {
      return UUID.fromString(subject);
    } catch (IllegalArgumentException notAnId) {
      return null;
    }
  }

  /**
   * Puts the authenticated caller's subject into the {@code userId} MDC key unless the key is
   * already set or no readable subject exists.
   *
   * @return {@code true} when this call stamped the key and must remove it again
   */
  private static boolean stampAuthenticatedSub() {
    String existing = MDC.get(MDC_USER_ID);
    if (existing != null && !existing.isBlank()) {
      return false;
    }
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
   * Writes the 403 problem document: correlation id, DEBUG log line, {@code
   * basetool_http_error_total} increment, status, headers and body.
   *
   * @param request the rejected request; its URI becomes the {@code instance}
   * @param response the response to write the problem body into
   * @param refusal the refusal whose code, title, detail and type are written
   * @throws IOException if serialization or writing the body fails
   */
  private void writeForbiddenBody(
      HttpServletRequest request, HttpServletResponse response, Refusal refusal)
      throws IOException {
    String correlationId = UUID.randomUUID().toString();
    Locale locale = request.getLocale();
    final String title = messageSource.getMessage(refusal.titleKey(), null, "Forbidden", locale);
    final String detail =
        messageSource.getMessage(refusal.detailKey(), null, refusal.defaultDetail(), locale);

    log.debug(
        "{} blocked on {} {} [correlationId={}]",
        refusal.logSubject(),
        request.getMethod(),
        request.getRequestURI(),
        correlationId);

    meterRegistry.counter(MetricNames.HTTP_ERROR, MetricNames.TAG_CODE, refusal.code()).increment();

    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setHeader(CORRELATION_ID_HEADER, correlationId);
    ProblemDetail problem =
        problemResponseFactory.problem(
            HttpStatus.FORBIDDEN,
            title,
            detail,
            request.getRequestURI(),
            refusal.type(),
            refusal.code(),
            correlationId);
    response.getOutputStream().write(objectMapper.writeValueAsBytes(problem));
  }
}
