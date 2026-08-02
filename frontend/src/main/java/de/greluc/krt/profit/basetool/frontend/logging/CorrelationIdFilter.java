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
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CorrelationIdFilter extends OncePerRequestFilter implements Ordered {

  private static final int MAX_ID_LENGTH = 128;
  private static final String ANONYMOUS = "anonymous";

  private final LoggingProperties loggingProperties;

  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain filterChain)
      throws ServletException, IOException {
    final String correlationId = resolveCorrelationId(request);
    final String userId = resolveUserId();

    MDC.put(loggingProperties.correlationIdMdcKey(), correlationId);
    MDC.put(loggingProperties.userIdMdcKey(), userId);
    response.setHeader(loggingProperties.correlationIdHeader(), correlationId);
    // Expose correlation id to the current thread so WebClient filters / downstream code
    // can propagate it towards the backend without re-reading the request.
    CorrelationContext.set(correlationId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(loggingProperties.correlationIdMdcKey());
      MDC.remove(loggingProperties.userIdMdcKey());
      CorrelationContext.clear();
    }
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
      String sub = oidc.getSubject();
      if (sub != null && !sub.isBlank()) {
        return sub;
      }
    }
    return ANONYMOUS;
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE - 100;
  }
}
