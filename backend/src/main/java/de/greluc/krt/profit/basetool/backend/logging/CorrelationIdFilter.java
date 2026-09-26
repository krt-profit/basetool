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

package de.greluc.krt.profit.basetool.backend.logging;

import de.greluc.krt.profit.basetool.backend.config.LoggingProperties;
import de.greluc.krt.profit.basetool.backend.service.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.service.OwnerScopeService;
import de.greluc.krt.profit.basetool.backend.support.AuthenticatedSubject;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the correlation id, user id and squadron id to the MDC for each request and echoes the
 * correlation id in the response header.
 *
 * <p>The correlation id comes from the inbound header ({@link
 * LoggingProperties#getCorrelationIdHeader()}) or is a fresh UUID; the user id is the caller's
 * {@code sub} via {@code AuthenticatedSubject} (ADR-0129), or {@code anonymous}. The MDC is cleared
 * in a {@code finally} block, and async dispatches re-bind the values the initial dispatch
 * resolved.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CorrelationIdFilter extends OncePerRequestFilter implements Ordered {

  /**
   * Request attribute carrying the correlation id the initial dispatch resolved, for the async
   * dispatch of the same request to re-bind. Namespaced by this class so no other component's
   * attribute can collide with it; set server-side only, so no client can forge it.
   */
  static final String CORRELATION_ID_ATTRIBUTE =
      CorrelationIdFilter.class.getName() + ".correlationId";

  /** Request attribute carrying the {@code userId} MDC value the initial dispatch resolved. */
  static final String USER_ID_ATTRIBUTE = CorrelationIdFilter.class.getName() + ".userId";

  /** Request attribute carrying the {@code orgUnitId} MDC value the initial dispatch resolved. */
  static final String ORG_UNIT_ID_ATTRIBUTE = CorrelationIdFilter.class.getName() + ".orgUnitId";

  /** Maximum accepted length for an inbound correlation id to avoid abuse / log injection. */
  private static final int MAX_ID_LENGTH = 128;

  private static final String ANONYMOUS = "anonymous";

  private final LoggingProperties loggingProperties;
  private final AuthHelperService authHelperService;
  private final OwnerScopeService ownerScopeService;

  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain filterChain)
      throws ServletException, IOException {
    if (isAsyncDispatch(request)) {
      rebindForAsyncDispatch(request, response, filterChain);
      return;
    }
    final String correlationId = resolveCorrelationId(request);
    final String userId = resolveUserId();
    final String orgUnitId = resolveSquadronId();

    MDC.put(loggingProperties.correlationIdMdcKey(), correlationId);
    MDC.put(loggingProperties.userIdMdcKey(), userId);
    MDC.put(loggingProperties.orgUnitIdMdcKey(), orgUnitId);
    response.setHeader(loggingProperties.correlationIdHeader(), correlationId);
    request.setAttribute(CORRELATION_ID_ATTRIBUTE, correlationId);
    request.setAttribute(USER_ID_ATTRIBUTE, userId);
    request.setAttribute(ORG_UNIT_ID_ATTRIBUTE, orgUnitId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(loggingProperties.correlationIdMdcKey());
      MDC.remove(loggingProperties.userIdMdcKey());
      MDC.remove(loggingProperties.orgUnitIdMdcKey());
    }
  }

  /**
   * Opts this filter into async dispatches, which {@link OncePerRequestFilter} skips by default, so
   * {@link #doFilterInternal} can re-bind the stashed values there.
   *
   * @return always {@code false}
   */
  @Override
  protected boolean shouldNotFilterAsyncDispatch() {
    return false;
  }

  /**
   * Runs an async dispatch with the MDC values its initial dispatch stashed, and removes exactly
   * those keys afterwards. A value that was not stashed stays unbound.
   *
   * @param request the request being dispatched asynchronously
   * @param response the response of the same request
   * @param filterChain the remaining filter chain
   * @throws ServletException if a downstream filter or the servlet fails
   * @throws IOException if writing the response fails
   */
  private void rebindForAsyncDispatch(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain filterChain)
      throws ServletException, IOException {
    final List<String> bound = new ArrayList<>(3);
    bindStashed(request, CORRELATION_ID_ATTRIBUTE, loggingProperties.correlationIdMdcKey(), bound);
    bindStashed(request, USER_ID_ATTRIBUTE, loggingProperties.userIdMdcKey(), bound);
    bindStashed(request, ORG_UNIT_ID_ATTRIBUTE, loggingProperties.orgUnitIdMdcKey(), bound);
    try {
      filterChain.doFilter(request, response);
    } finally {
      bound.forEach(MDC::remove);
    }
  }

  /**
   * Puts a value the initial dispatch stashed on the request into the MDC, when it is a non-blank
   * string, and records the key so the caller can remove it again.
   *
   * @param request the request carrying the attribute
   * @param attribute the attribute name, one of this class's {@code *_ATTRIBUTE} constants
   * @param mdcKey the MDC key to bind the value under
   * @param bound the keys bound so far on this pass; {@code mdcKey} is added when it is bound
   */
  private static void bindStashed(
      @NotNull HttpServletRequest request,
      @NotNull String attribute,
      @NotNull String mdcKey,
      @NotNull List<String> bound) {
    if (request.getAttribute(attribute) instanceof String value && !value.isBlank()) {
      MDC.put(mdcKey, value);
      bound.add(mdcKey);
    }
  }

  /**
   * Resolves the squadron MDC value: an admin's active selection, else the home squadron; {@code
   * all} for an admin without a selection, {@code none} when unauthenticated, unassigned or the
   * lookup fails.
   */
  @NotNull
  private String resolveSquadronId() {
    try {
      if (!authHelperService.isAuthenticated()) {
        return ANONYMOUS;
      }
      return ownerScopeService
          .currentSquadronId()
          .map(UUID::toString)
          .orElseGet(() -> authHelperService.isAdmin() ? "all" : "none");
    } catch (RuntimeException ex) {
      log.debug("squadronId MDC resolution failed, falling back to 'none'", ex);
      return "none";
    }
  }

  @NotNull
  private String resolveCorrelationId(@NotNull HttpServletRequest request) {
    String inbound = request.getHeader(loggingProperties.correlationIdHeader());
    if (inbound == null || inbound.isBlank()) {
      return UUID.randomUUID().toString();
    }
    String truncated =
        inbound.length() > MAX_ID_LENGTH ? inbound.substring(0, MAX_ID_LENGTH) : inbound;
    return isSafe(truncated) ? truncated : UUID.randomUUID().toString();
  }

  /**
   * Accept only characters that cannot break a log line or a response header. This is the same
   * practice Spring Cloud Sleuth / Micrometer Tracing apply to inbound B3 trace ids.
   */
  private static boolean isSafe(@NotNull String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      boolean allowed =
          (c >= '0' && c <= '9')
              || (c >= 'a' && c <= 'z')
              || (c >= 'A' && c <= 'Z')
              || c == '-'
              || c == '_'
              || c == '.';
      if (!allowed) {
        return false;
      }
    }
    return true;
  }

  @NotNull
  private static String resolveUserId() {
    return AuthenticatedSubject.of(SecurityContextHolder.getContext().getAuthentication())
        .orElse(ANONYMOUS);
  }

  /**
   * Orders the filter just before {@link Ordered#LOWEST_PRECEDENCE}, so the {@link
   * SecurityContextHolder} is populated and later filters still see the MDC values.
   */
  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE - 100;
  }
}
