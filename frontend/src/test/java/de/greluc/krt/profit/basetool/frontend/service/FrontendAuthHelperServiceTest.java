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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for {@link FrontendAuthHelperService}, focused on the {@code isMemberOrAbove}
 * predicate that gates the member-only mission finance/refinery fetches (REQ-SEC-013). The
 * regression these guard against: the check must read the request {@link
 * org.springframework.security.core.Authentication} authorities (where Spring's {@code
 * userAuthoritiesMapper} puts the Keycloak {@code ROLE_*}), not an {@code OidcUser} principal's own
 * authorities.
 */
class FrontendAuthHelperServiceTest {

  private final FrontendAuthHelperService service = new FrontendAuthHelperService();

  /**
   * Clears the per-test security context so one test's authentication cannot leak into the next.
   */
  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  /** Sets the given role authorities on the current thread's security context. */
  private void authenticateWith(String... authorities) {
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken("user", "pw", authorities));
  }

  // The three isAnonymous() cases stood here. The method is gone with the caller it described
  // (ADR-0159): nothing anonymous reaches a frontend handler any more, so every branch that asked
  // the question was dead code. What the cases actually pinned — that Spring's anonymous token is
  // authenticated and must not be mistaken for a member — survives in isAuthenticated()'s own
  // tests below, which is where the distinction always lived.

  @Test
  void isMemberOrAbove_withSquadronMemberRole_returnsTrue() {
    // Given
    authenticateWith("ROLE_KRT_MEMBER");
    // When / Then
    assertTrue(service.isMemberOrAbove(), "a squadron member is a member or above");
  }

  @Test
  void isMemberOrAbove_withAdminRole_returnsTrue() {
    // Given
    authenticateWith("ROLE_ADMIN");
    // When / Then
    assertTrue(service.isMemberOrAbove(), "an admin is a member or above");
  }

  @Test
  void isMemberOrAbove_withOnlyTheNoRoleMarker_returnsFalse() {
    // Given — the marker that replaced the deleted GUEST role (V239 / REQ-SEC-053) carries no
    // member authority
    authenticateWith("ROLE_NO_ROLE");
    // When / Then
    assertFalse(service.isMemberOrAbove(), "a role-less account is not a member");
  }

  @Test
  void isMemberOrAbove_withNoAuthentication_returnsFalse() {
    // Given — empty security context (anonymous browser hitting a permitAll page)
    SecurityContextHolder.clearContext();
    // When / Then
    assertFalse(service.isMemberOrAbove(), "missing authentication is not a member");
  }

  @Test
  void isMemberOrAbove_withAnonymousToken_returnsFalse() {
    // Given
    SecurityContextHolder.getContext()
        .setAuthentication(
            new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
    // When / Then
    assertFalse(service.isMemberOrAbove(), "an anonymous token is not a member");
  }

  @Test
  void isMemberOrAbove_readsTokenAuthorities_notPrincipalObject() {
    // Given — the exact regression shape: a member ROLE_* present on the Authentication token even
    // though no OidcUser principal object is involved. Reading the token must still see it.
    authenticateWith("ROLE_OFFICER");
    // When / Then
    assertTrue(service.isMemberOrAbove(), "officer authority on the token counts as member");
  }
}
