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

package de.greluc.krt.profit.basetool.frontend.kernel.security;

import static de.greluc.krt.profit.basetool.frontend.kernel.security.ReauthenticationHandoff.isWebSocketUpgrade;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.ReauthenticationRequiredException;
import de.greluc.krt.profit.basetool.frontend.kernel.model.HomeLocationStatusDto;
import de.greluc.krt.profit.basetool.frontend.kernel.web.SectionFallback;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Routes a member who must choose a new home location after a patch change to the home-location
 * page (REQ-PATCH-020), the way {@link TermsAcceptanceGateFilter} routes to the consent page.
 *
 * <p>This is UX only; the backend's {@code HomeLocationGateAccessFilter} is the boundary. A verdict
 * that lets the member through is cached per session for {@link #RECHECK_MILLIS}; a verdict that
 * holds the member is never cached, so the choice frees the member on the next request. Runs after
 * the consent gate.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HomeLocationGateFilter extends OncePerRequestFilter {

  /** Where a member who must choose is sent. */
  public static final String GATE_PATH = "/home-location";

  /** Response header carrying the home-location page's URL to an AJAX caller. */
  static final String GATE_HEADER = "X-Home-Location-Required";

  /** Name of the one-shot SSE event that hands an {@code EventSource} off to the page. */
  public static final String SSE_GATE_EVENT = "home-location-gate";

  /** Session attribute holding the epoch millis at which the cached "may proceed" was read. */
  static final String SESSION_CHECKED_AT = "krt.homeLocation.checkedAt";

  /** How long a verdict that lets the member through may be reused. */
  static final long RECHECK_MILLIS = 60_000L;

  /** Backend endpoint reporting whether the caller must choose. */
  private static final String STATUS_URI = "/api/v1/patch-changes/me/home-location/status";

  private final BackendApiClient backendApiClient;
  private final Environment environment;

  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain filterChain)
      throws ServletException, IOException {
    if (isExempt(request) || environment.matchesProfiles("test") || !isAuthenticated()) {
      filterChain.doFilter(request, response);
      return;
    }
    boolean mustChoose;
    try {
      mustChoose = mustChoose(request);
    } catch (ReauthenticationRequiredException _) {
      if (isWebSocketUpgrade(request)) {
        filterChain.doFilter(request, response);
        return;
      }
      ReauthenticationHandoff.send(request, response);
      return;
    }
    if (!mustChoose) {
      filterChain.doFilter(request, response);
      return;
    }
    log.debug("Home location missing; handing {} off to its page", request.getRequestURI());
    SessionGateHandoff.handOff(
        request,
        response,
        filterChain,
        new SessionGateHandoff.Gate(
            request.getContextPath() + GATE_PATH, SSE_GATE_EVENT, GATE_HEADER));
  }

  /**
   * Discards this session's cached verdict, so the next request asks the backend again; called when
   * the home-location page shows a pending prompt and after the choice.
   *
   * @param request the current request, whose session holds the cached verdict
   */
  public static void clearCachedVerdict(@NotNull HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session != null) {
      session.removeAttribute(SESSION_CHECKED_AT);
    }
  }

  /**
   * Asks the backend whether the member must choose, at most once per {@link #RECHECK_MILLIS} while
   * the answer is no. A backend failure lets the member through.
   *
   * @param request the current request, whose session carries the cached verdict
   * @return {@code true} when the member must choose first
   */
  private boolean mustChoose(@NotNull HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    long now = System.currentTimeMillis();
    if (session != null
        && session.getAttribute(SESSION_CHECKED_AT) instanceof Long millis
        && now - millis < RECHECK_MILLIS) {
      return false;
    }
    HomeLocationStatusDto status;
    try {
      status = backendApiClient.get(STATUS_URI, HomeLocationStatusDto.class);
    } catch (BackendServiceException failure) {
      SectionFallback.rethrowIfReauthenticationRequired(failure);
      log.debug("Home-location status unreadable; letting the request through.");
      return false;
    } catch (RuntimeException failure) {
      SectionFallback.rethrowIfReauthenticationRequired(failure);
      throw failure;
    }
    boolean required = status != null && status.required();
    if (!required && session != null) {
      session.setAttribute(SESSION_CHECKED_AT, now);
    }
    return required;
  }

  /**
   * Whether an authenticated, non-anonymous member is behind the request.
   *
   * @return {@code true} for an authenticated principal
   */
  private static boolean isAuthenticated() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal());
  }

  /**
   * Whether the request is exempt: the home-location page itself, the consent and waiting pages,
   * and everything {@link PublicPaths#isGateExempt} covers.
   *
   * @param request the current request
   * @return {@code true} when the gate may not redirect the request
   */
  private static boolean isExempt(@NotNull HttpServletRequest request) {
    String path = PublicPaths.relativePath(request);
    return PublicPaths.isAccessGatePage(path) || PublicPaths.isGateExempt(path);
  }
}
