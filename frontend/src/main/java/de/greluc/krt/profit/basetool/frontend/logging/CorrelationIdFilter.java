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
 * Central correlation / MDC enrichment filter for the frontend module.
 *
 * <p>Mirrors the backend {@code CorrelationIdFilter}: on every request a {@code correlationId} is
 * either read from the inbound header (configurable) or generated as UUID and echoed back in the
 * response. The resolved id plus the user id ({@code OidcUser.subject} for OAuth2 login users or
 * {@code sub} claim for JWT) is placed into the MDC for the duration of the request and removed in
 * the {@code finally} block to avoid thread-pool bleed-through.
 *
 * <p>Intentionally limited to {@code sub} – never emails/names/tokens – to avoid PII in logs.
 *
 * <p>The third correlation field REQ-OBS-001 mandates, {@code orgUnitId}, is deliberately
 * <b>not</b> bound here: this filter is ordered {@link Ordered#LOWEST_PRECEDENCE} − 100 and runs
 * before {@link ActiveSquadronContextFilter} ({@link Ordered#LOWEST_PRECEDENCE} − 99) has read the
 * caller's pin out of the session, so reading it at this point would only ever yield {@code null}.
 * That filter owns the key and clears it again on the way out.
 *
 * <p><b>Async dispatches re-bind what the initial dispatch resolved (2026-09-25).</b> A request
 * whose handler returns an {@code SseEmitter}, a {@code DeferredResult} or a {@code Callable} is
 * dispatched a second time — {@code DispatcherType.ASYNC}, on a different container thread — when
 * its async result arrives, and everything logged on that pass (the stream's completion, an async
 * error resolved by {@code GlobalExceptionHandler}) used to carry no {@code correlationId} and the
 * logback fallback {@code userId=anonymous}, because a {@link OncePerRequestFilter} skips async
 * dispatches by default. That made an authenticated member's stream failures read as anonymous
 * traffic. The initial dispatch therefore stashes both resolved values as request attributes, and
 * the async dispatch binds them again for its own duration and removes them in its own {@code
 * finally}. The async pass <b>never</b> resolves anything afresh: it mints no new id, reads no
 * header (the request's headers are the client's, a request attribute is not) and does not consult
 * the security context. It also leaves the response header alone — the initial dispatch already set
 * it, and a streaming response is committed by then.
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
   * Runs the async dispatch of a request with the correlation id and user id its initial dispatch
   * resolved bound to the MDC — and the id to {@link CorrelationContext}, so a backend call made on
   * this pass carries the same id outbound (REQ-OBS-002) — and removes exactly what it bound once
   * the chain returns, so nothing survives on the container thread.
   *
   * <p>A value the initial dispatch did not stash — possible only when that dispatch never passed
   * this filter — stays unbound: the line then renders the empty slot, which is the truthful
   * answer, rather than a freshly minted id that no other line of the request carries.
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
