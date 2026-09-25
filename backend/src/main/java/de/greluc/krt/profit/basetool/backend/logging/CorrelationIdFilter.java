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
 * Central correlation / MDC enrichment filter.
 *
 * <p>Each request is decorated with two MDC keys:
 *
 * <ul>
 *   <li><b>correlationId</b> – either taken from the inbound header (configurable via {@link
 *       LoggingProperties#getCorrelationIdHeader()}) or freshly generated as UUID. The effective id
 *       is echoed back in the response header of the same name so clients/proxies can trace the
 *       same request end-to-end.
 *   <li><b>userId</b> – the authenticated caller's {@code sub}, read through {@code
 *       AuthenticatedSubject} so a token-less acting member (ADR-0129) is attributed rather than
 *       logged as anonymous, or {@code anonymous} for unauthenticated traffic. Intentionally
 *       restricted to {@code sub}: the principal name is a callsign, and REQ-OBS-004 keeps it out
 *       of the log entirely.
 * </ul>
 *
 * <p>The MDC is cleared in a {@code finally} block to prevent bleed-through on pooled or virtual
 * threads. The filter runs after Spring Security (order {@link Ordered#LOWEST_PRECEDENCE} minus a
 * small delta) so that the {@code SecurityContext} is already populated when the MDC is set. A
 * secondary lightweight pass at filter start still generates / echoes the correlation id even for
 * unauthenticated requests, ensuring every log line has the same id.
 *
 * <p><b>Async dispatches re-bind what the initial dispatch resolved (2026-09-25).</b> The SSE
 * endpoints ({@code /api/v1/notifications/stream}, the live-sync stream) are dispatched a second
 * time — {@code DispatcherType.ASYNC}, on another container thread — when their async result
 * arrives, and a {@link OncePerRequestFilter} skips that pass by default. Every line logged there
 * therefore carried no correlation fields at all, and {@code GlobalExceptionHandler}'s catch-all
 * even minted a fresh id for its {@code ERROR} line that no other line of the request shared. The
 * initial dispatch now stashes the three resolved values as request attributes and the async
 * dispatch binds them again for its own duration, removing them in its own {@code finally}. The
 * async pass resolves nothing afresh: no new id, no header read, no security-context or database
 * lookup, and no response header — the initial dispatch set it and the stream is committed.
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
    // Stashed for a later async dispatch of this same request, which runs on another thread.
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
   * Runs the async dispatch of a request with the three values its initial dispatch resolved bound
   * to the MDC, and removes exactly the keys it bound once the chain returns, so nothing survives
   * on the container thread. A value that was not stashed — possible only when the initial dispatch
   * never passed this filter — stays unbound rather than being resolved or minted afresh.
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
   * Resolves the squadron context for the MDC: the active switcher selection for admins, the
   * persistent home squadron for everyone else, or one of the sentinels {@code all} (admin without
   * active selection) / {@code none} (unauthenticated / no squadron assigned / lookup failed).
   * Defensive try-catch so a transient DB hiccup or a missing transaction context never brings down
   * the request - logs just degrade to {@code none}.
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
    // Truncate BEFORE validating: `isSafe` walks the string character by character, so a 64 KB
    // header that fails validation anyway would cost 64 K char scans. Capping to MAX_ID_LENGTH
    // first bounds that cost to ~128 chars regardless of input size, while keeping the same
    // accept/reject decision — a value that contains an unsafe char in the prefix would have
    // failed either way, and a value whose prefix is safe was already what we'd have returned.
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
    // Asked of AuthenticatedSubject, not of the type. An acting-member request (ADR-0129) is
    // authenticated as a named person with no token behind it; the old instanceof test logged it as
    // anonymous while orgUnitId on the very same line resolved correctly, so the log contradicted
    // itself. A misbehaving gateway is the main threat this design carries, and this is the stream
    // that would attribute it.
    return AuthenticatedSubject.of(SecurityContextHolder.getContext().getAuthentication())
        .orElse(ANONYMOUS);
  }

  /**
   * Run very late in the servlet filter chain so Spring Security has already populated the {@link
   * SecurityContextHolder}. Using {@link Ordered#LOWEST_PRECEDENCE} minus a constant lets
   * downstream filters (e.g. request logging) still read the MDC values we set here.
   */
  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE - 100;
  }
}
