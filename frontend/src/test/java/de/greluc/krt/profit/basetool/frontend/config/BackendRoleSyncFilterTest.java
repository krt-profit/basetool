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

package de.greluc.krt.profit.basetool.frontend.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.RegistrationStatusDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * Unit tests for {@link BackendRoleSyncFilter}, covering two behaviours.
 *
 * <ul>
 *   <li>The "do not poison the session on a failed sync" contract (REQ-SEC-013): the {@code
 *       BACKEND_ROLES_SYNCED} session flag must only be set when the backend role read genuinely
 *       succeeded, so a transient backend outage on the first request of a session is retried
 *       instead of leaving the principal under-privileged until re-login.
 *   <li>The epic-#720 approval gate: a {@code PENDING}/{@code REJECTED} registration is redirected
 *       to {@code /pending-approval} on guarded paths but allowed through on the {@code
 *       isApprovalExempt} whitelist (e.g. {@code /logout}), and the resolved approval status is
 *       cached once per session in {@code BACKEND_APPROVAL_STATE} so the backend is not re-hit.
 * </ul>
 */
class BackendRoleSyncFilterTest {

  private static final String SYNC_COMPLETE_FLAG = "BACKEND_ROLES_SYNCED";
  private static final String APPROVAL_STATE_FLAG = "BACKEND_APPROVAL_STATE";
  private static final String USERS_ME = "/api/v1/users/me";
  private static final String REGISTRATION_STATUS = "/api/v1/users/me/registration-status";

  private BackendApiClient backendApiClient;
  private BackendRoleSyncFilter filter;
  private HttpServletRequest request;
  private HttpServletResponse response;
  private FilterChain chain;
  private HttpSession session;

  /** Wires fresh mocks and an authenticated OAuth2 token (officer) into the security context. */
  @BeforeEach
  void setUp() {
    backendApiClient = mock(BackendApiClient.class);
    filter = new BackendRoleSyncFilter(backendApiClient);

    request = mock(HttpServletRequest.class);
    response = mock(HttpServletResponse.class);
    chain = mock(FilterChain.class);
    session = mock(HttpSession.class);
    when(request.getSession(false)).thenReturn(session);
    when(session.getAttribute(SYNC_COMPLETE_FLAG)).thenReturn(null);

    OidcIdToken idToken = OidcIdToken.withTokenValue("token").subject("user-1").build();
    List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_OFFICER"));
    OidcUser oidcUser = new DefaultOidcUser(authorities, idToken);
    SecurityContextHolder.getContext()
        .setAuthentication(new OAuth2AuthenticationToken(oidcUser, authorities, "keycloak"));
  }

  /** Clears the per-test security context. */
  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void doFilterInternal_whenUsersMeReturnsNull_doesNotMarkSessionSynced() throws Exception {
    // Given — Resilience4j fallback hands back null (backend unavailable)
    when(backendApiClient.get(USERS_ME, UserDto.class)).thenReturn(null);

    // When
    filter.doFilterInternal(request, response, chain);

    // Then — flag stays unset so the next request retries; chain still proceeds
    verify(session, never()).setAttribute(eq(SYNC_COMPLETE_FLAG), any());
    verify(chain).doFilter(request, response);
  }

  @Test
  void doFilterInternal_whenUsersMeThrows_doesNotMarkSessionSynced() throws Exception {
    // Given — the backend call blows up
    when(backendApiClient.get(USERS_ME, UserDto.class))
        .thenThrow(new RuntimeException("backend down"));

    // When
    filter.doFilterInternal(request, response, chain);

    // Then
    verify(session, never()).setAttribute(eq(SYNC_COMPLETE_FLAG), any());
    verify(chain).doFilter(request, response);
  }

  @Test
  void doFilterInternal_whenUsersMeSucceeds_marksSessionSynced() throws Exception {
    // Given — a valid user whose roles are already present on the token (modified=false path, so no
    // SecurityContext rewrite is exercised) — the read still counts as a successful sync.
    UserDto user =
        new UserDto(
            UUID.randomUUID(),
            "officer",
            "Officer",
            "Officer",
            null,
            null,
            null,
            Set.of("Officer"),
            Set.of(),
            null,
            false,
            false,
            true,
            null,
            java.util.List.of(),
            1L,
            null,
            false);
    when(backendApiClient.get(USERS_ME, UserDto.class)).thenReturn(user);

    // When
    filter.doFilterInternal(request, response, chain);

    // Then — successful read marks the session synced
    verify(session).setAttribute(SYNC_COMPLETE_FLAG, true);
    verify(chain).doFilter(request, response);
  }

  @Test
  void pendingApproval_nonExemptPath_redirectsToWaitingPage() throws Exception {
    // Given — the backend reports a PENDING registration and the request targets a guarded page
    when(request.getContextPath()).thenReturn("");
    when(request.getRequestURI()).thenReturn("/dashboard");
    when(backendApiClient.get(REGISTRATION_STATUS, RegistrationStatusDto.class))
        .thenReturn(new RegistrationStatusDto("PENDING"));

    // When
    filter.doFilterInternal(request, response, chain);

    // Then — routed to the waiting page and the chain is short-circuited (never the guest surface,
    // never the #720 403 storm), and the resolved status is cached for the rest of the session
    verify(response).sendRedirect("/pending-approval");
    verify(chain, never()).doFilter(request, response);
    verify(session).setAttribute(APPROVAL_STATE_FLAG, "PENDING");
  }

  @Test
  void pendingApproval_exemptPath_proceeds() throws Exception {
    // Given — a PENDING user hitting an exempt path (/logout) must be able to leave, not be trapped
    when(request.getContextPath()).thenReturn("");
    when(request.getRequestURI()).thenReturn("/logout");
    when(backendApiClient.get(REGISTRATION_STATUS, RegistrationStatusDto.class))
        .thenReturn(new RegistrationStatusDto("PENDING"));

    // When
    filter.doFilterInternal(request, response, chain);

    // Then — the chain proceeds and no redirect is issued
    verify(chain).doFilter(request, response);
    verify(response, never()).sendRedirect(anyString());
  }

  @Test
  void approvalStatus_isCachedPerSession_skipsRegistrationStatusFetch() throws Exception {
    // Given — a prior request already resolved and cached the approval status on the session, and
    // roles are already synced (isolate the approval-cache behaviour from the role-sync branch)
    when(session.getAttribute(APPROVAL_STATE_FLAG)).thenReturn("ACTIVE");
    when(session.getAttribute(SYNC_COMPLETE_FLAG)).thenReturn(true);

    // When
    filter.doFilterInternal(request, response, chain);

    // Then — the backend approval endpoint is NOT hit again (resolved once per session)
    verify(backendApiClient, never()).get(REGISTRATION_STATUS, RegistrationStatusDto.class);
    verify(chain).doFilter(request, response);
  }
}
