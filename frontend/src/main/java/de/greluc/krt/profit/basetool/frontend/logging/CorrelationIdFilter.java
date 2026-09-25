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

import de.greluc.krt.profit.basetool.frontend.config.LoggingProperties;
import de.greluc.krt.profit.basetool.frontend.support.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Frontend correlation and MDC filter: reads the {@code correlationId} from the inbound header or
 * generates one, echoes it in the response, and binds it and the user's {@code sub} to the MDC for
 * the request.
 *
 * <p>Never logs e-mails, names or tokens. {@code orgUnitId} is bound by {@link
 * ActiveSquadronContextFilter} instead. Async dispatches re-bind the values the initial dispatch
 * stored as request attributes, without resolving anything afresh.
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

  /**
   * Request attribute carrying the user id ({@code sub} or {@code anonymous}) the initial dispatch
   * resolved, for the async dispatch of the same request to re-bind.
   */
  static final String USER_ID_ATTRIBUTE = CorrelationIdFilter.class.getName() + ".userId";

  private static final int MAX_ID_LENGTH = 128;
  private static final String ANONYMOUS = "anonymous";

  private final LoggingProperties loggingProperties;

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

    MDC.put(loggingProperties.correlationIdMdcKey(), correlationId);
    MDC.put(loggingProperties.userIdMdcKey(), userId);
    response.setHeader(loggingProperties.correlationIdHeader(), correlationId);
    CorrelationContext.set(correlationId);
    request.setAttribute(CORRELATION_ID_ATTRIBUTE, correlationId);
    request.setAttribute(USER_ID_ATTRIBUTE, userId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(loggingProperties.correlationIdMdcKey());
      MDC.remove(loggingProperties.userIdMdcKey());
      CorrelationContext.clear();
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
   * Runs an async dispatch with the correlation id and user id stored by the initial dispatch bound
   * to the MDC and the id to {@link CorrelationContext} (REQ-OBS-002), and removes them afterwards.
   * A value that was not stored stays unbound.
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
    final String correlationKey = loggingProperties.correlationIdMdcKey();
    final String userKey = loggingProperties.userIdMdcKey();
    final String correlationId = stashed(request, CORRELATION_ID_ATTRIBUTE);
    final String userId = stashed(request, USER_ID_ATTRIBUTE);
    if (correlationId != null) {
      MDC.put(correlationKey, correlationId);
      CorrelationContext.set(correlationId);
    }
    if (userId != null) {
      MDC.put(userKey, userId);
    }
    try {
      filterChain.doFilter(request, response);
    } finally {
      if (correlationId != null) {
        MDC.remove(correlationKey);
        CorrelationContext.clear();
      }
      if (userId != null) {
        MDC.remove(userKey);
      }
    }
  }

  /**
   * Reads a value the initial dispatch stashed on the request.
   *
   * @param request the request carrying the attribute
   * @param attribute the attribute name, one of this class's {@code *_ATTRIBUTE} constants
   * @return the stashed value, or {@code null} when it is absent, not a string, or blank
   */
  @Nullable
  private static String stashed(@NotNull HttpServletRequest request, @NotNull String attribute) {
    return request.getAttribute(attribute) instanceof String value && !value.isBlank()
        ? value
        : null;
  }

  @NotNull
  private String resolveCorrelationId(@NotNull HttpServletRequest request) {
    String inbound = request.getHeader(loggingProperties.correlationIdHeader());
    if (inbound != null && !inbound.isBlank() && isSafe(inbound)) {
      return inbound.length() > MAX_ID_LENGTH ? inbound.substring(0, MAX_ID_LENGTH) : inbound;
    }
    return UUID.randomUUID().toString();
  }

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
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof OidcUser oidc) {
      String userId = CurrentUser.userIdText(oidc);
      if (userId != null && !userId.isBlank()) {
        return userId;
      }
    }
    return ANONYMOUS;
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE - 100;
  }
}
