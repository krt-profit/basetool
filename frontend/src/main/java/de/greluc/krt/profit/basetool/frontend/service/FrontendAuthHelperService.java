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

package de.greluc.krt.profit.basetool.frontend.service;

import de.greluc.krt.profit.basetool.frontend.support.Roles;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Centralised access point for the Spring Security {@link SecurityContextHolder} on the frontend
 * side.
 *
 * <p>The frontend module renders Thymeleaf pages on behalf of an OAuth2 browser session rather than
 * processing a JWT-bearing API call, so it has no JWT {@code sub} the way the backend does; the
 * helper therefore exposes only the predicates the rendering layer actually needs (is the caller
 * authenticated / anonymous? do they reach the admin or member role?). New callers should depend on
 * this bean instead of touching {@link SecurityContextHolder} directly so the request-scoped auth
 * contract stays testable through a single, mock-friendly seam.
 *
 * <p>Existing frontend touch points that still call {@link SecurityContextHolder} directly
 * (filters, exception handler, page controllers that propagate the JWT to the backend) are
 * out-of-scope for the Phase-6 follow-up — those reach into security context for low-level concerns
 * (bearer-token relay, MDC logging) where the indirection would not pay for itself.
 */
@Service
public class FrontendAuthHelperService {

  /**
   * {@code true} if the current request carries an authenticated, non-anonymous principal.
   * Anonymous tokens and missing security contexts both yield {@code false}, matching the backend's
   * {@code AuthHelperService#isAuthenticated()} semantics.
   *
   * @return whether the current request is authenticated.
   */
  public boolean isAuthenticated() {
    Authentication auth = currentAuthentication();
    return auth != null
        && auth.isAuthenticated()
        && !(auth instanceof AnonymousAuthenticationToken);
  }

  /**
   * {@code true} when the current request carries no authenticated, non-anonymous principal — the
   * negation of {@link #isAuthenticated()}, centralising the {@code @AuthenticationPrincipal
   * OidcUser principal == null} guard the page controllers used to inline. On the frontend the only
   * authenticated principal type is the Keycloak {@code OidcUser}, so a null
   * {@code @AuthenticationPrincipal} and an anonymous / missing security context are the same
   * condition; reading the request {@link Authentication} keeps the anonymous check on the same
   * source as {@link #isAuthenticated()}, {@code sec:authorize} and {@code @PreAuthorize}.
   *
   * @return whether the current request is anonymous (guest / not logged in).
   */
  public boolean isAnonymous() {
    return !isAuthenticated();
  }

  /**
   * {@code true} if the current authentication carries the {@code ROLE_ADMIN} authority directly.
   * The frontend does not configure a role hierarchy of its own — the bearer-token relay forwards
   * authorities verbatim — so this is a literal-match check rather than a reachability check.
   *
   * @return whether the current principal is an admin.
   */
  public boolean isAdmin() {
    Authentication auth = currentAuthentication();
    if (auth == null) {
      return false;
    }
    return auth.getAuthorities().stream()
        .map(Object::toString)
        .anyMatch(Roles.authority(Roles.ADMIN)::equals);
  }

  /**
   * {@code true} if the current authentication carries at least one {@link
   * Roles#MEMBER_AUTHORITIES} authority, i.e. the caller is a registered organisation member or
   * above.
   *
   * <p>Reads the authorities from the request {@link Authentication} — the SAME source {@code
   * sec:authorize} and {@code @PreAuthorize} consult — rather than from {@code
   * OidcUser#getAuthorities()}. Spring's {@code userAuthoritiesMapper} maps the Keycloak realm
   * roles onto the {@link Authentication} token, NOT onto the {@code OidcUser} principal object, so
   * a member check that reads the principal misses every {@code ROLE_*} unless {@code
   * BackendRoleSyncFilter} happened to rebuild the principal that session — which made the
   * member-only mission finance/refinery panel silently collapse whenever that one-shot sync was
   * skipped (REQ-SEC-013). Anonymous tokens, missing security contexts and role-less {@code GUEST}
   * callers all yield {@code false}.
   *
   * @return whether the current principal is a registered member or above.
   */
  public boolean isMemberOrAbove() {
    Authentication auth = currentAuthentication();
    if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
      return false;
    }
    return auth.getAuthorities().stream()
        .map(Object::toString)
        .anyMatch(Roles.MEMBER_AUTHORITIES::contains);
  }

  private Authentication currentAuthentication() {
    return SecurityContextHolder.getContext().getAuthentication();
  }
}
