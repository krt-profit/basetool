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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.greluc.krt.profit.basetool.frontend.model.dto.RegistrationStatusDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * Unit tests for {@link BackendRoleSyncFilter}: the session stamp is written only after a
 * successful role read, the approval gate redirects pending registrations, session verdicts expire
 * as designed (REQ-SEC-013), roles are reconciled both ways (ADR-0122), and a {@code 403 NO_ROLE}
 * routes the request that discovered it (REQ-SEC-053).
 */
class BackendRoleSyncFilterTest {

  private static final String ROLES_SYNCED_AT_FLAG = "BACKEND_ROLES_SYNCED_AT";
  private static final String APPROVAL_STATE_FLAG = "BACKEND_APPROVAL_STATE";
  private static final String APPROVAL_CHECKED_AT_FLAG = "BACKEND_APPROVAL_CHECKED_AT";
  private static final String SYNCED_AUTHORITIES_FLAG = "BACKEND_SYNCED_AUTHORITIES";
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
    when(request.getContextPath()).thenReturn("");
    when(request.getRequestURI()).thenReturn("/dashboard");
    when(session.getAttribute(ROLES_SYNCED_AT_FLAG)).thenReturn(null);

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
    when(backendApiClient.get(USERS_ME, UserDto.class)).thenReturn(null);

    filter.doFilterInternal(request, response, chain);

    verify(session, never()).setAttribute(eq(ROLES_SYNCED_AT_FLAG), any());
    verify(chain).doFilter(request, response);
  }

  @Test
  void doFilterInternal_whenUsersMeThrows_doesNotMarkSessionSynced() throws Exception {
    when(backendApiClient.get(USERS_ME, UserDto.class))
        .thenThrow(new RuntimeException("backend down"));

    filter.doFilterInternal(request, response, chain);

    verify(session, never()).setAttribute(eq(ROLES_SYNCED_AT_FLAG), any());
    verify(chain).doFilter(request, response);
  }

  @Test
  void doFilterInternal_whenUsersMeSucceeds_marksSessionSynced() throws Exception {
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

    filter.doFilterInternal(request, response, chain);

    verify(session).setAttribute(eq(ROLES_SYNCED_AT_FLAG), any(Long.class));
    verify(chain).doFilter(request, response);
  }

  @Test
  void doFilterInternal_whenBackendServiceException_logsAtDebugNotError() throws Exception {
    Logger logger = (Logger) LoggerFactory.getLogger(BackendRoleSyncFilter.class);
    Level original = logger.getLevel();
    logger.setLevel(Level.DEBUG);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      when(backendApiClient.get(USERS_ME, UserDto.class))
          .thenThrow(new BackendServiceException("backend down", null, 503));

      filter.doFilterInternal(request, response, chain);

      assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.ERROR);
      assertThat(appender.list)
          .anyMatch(
              e ->
                  e.getLevel() == Level.DEBUG
                      && e.getFormattedMessage().contains("Backend role sync deferred"));
      verify(session, never()).setAttribute(eq(ROLES_SYNCED_AT_FLAG), any());
      verify(chain).doFilter(request, response);
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(original);
    }
  }

  @Test
  void pendingApproval_nonExemptPath_redirectsToWaitingPage() throws Exception {
    when(request.getContextPath()).thenReturn("");
    when(request.getRequestURI()).thenReturn("/dashboard");
    when(backendApiClient.get(REGISTRATION_STATUS, RegistrationStatusDto.class))
        .thenReturn(new RegistrationStatusDto("PENDING"));

    filter.doFilterInternal(request, response, chain);

    verify(response).sendRedirect("/pending-approval");
    verify(chain, never()).doFilter(request, response);
    verify(session).setAttribute(APPROVAL_STATE_FLAG, "PENDING");
    verify(session).setAttribute(eq(APPROVAL_CHECKED_AT_FLAG), any(Long.class));
  }

  @Test
  void pendingApproval_statusPollPath_proceeds() throws Exception {
    when(request.getRequestURI()).thenReturn("/pending-approval/status");
    when(backendApiClient.get(REGISTRATION_STATUS, RegistrationStatusDto.class))
        .thenReturn(new RegistrationStatusDto("PENDING"));

    filter.doFilterInternal(request, response, chain);

    verify(chain).doFilter(request, response);
    verify(response, never()).sendRedirect(anyString());
  }

  @Test
  void pendingApproval_exemptPath_proceeds() throws Exception {
    when(request.getContextPath()).thenReturn("");
    when(request.getRequestURI()).thenReturn("/logout");
    when(backendApiClient.get(REGISTRATION_STATUS, RegistrationStatusDto.class))
        .thenReturn(new RegistrationStatusDto("PENDING"));

    filter.doFilterInternal(request, response, chain);

    verify(chain).doFilter(request, response);
    verify(response, never()).sendRedirect(anyString());
  }

  @Test
  void approvalStatus_whenActive_isCachedForGood_skipsRegistrationStatusFetch() throws Exception {
    when(session.getAttribute(APPROVAL_STATE_FLAG)).thenReturn("ACTIVE");
    when(session.getAttribute(APPROVAL_CHECKED_AT_FLAG)).thenReturn(staleStamp());
    when(session.getAttribute(ROLES_SYNCED_AT_FLAG)).thenReturn(freshStamp());

    filter.doFilterInternal(request, response, chain);

    verify(backendApiClient, never()).get(REGISTRATION_STATUS, RegistrationStatusDto.class);
    verify(chain).doFilter(request, response);
  }

  @Test
  void approvalStatus_whenPendingAndFresh_isNotRefetched() throws Exception {
    when(session.getAttribute(APPROVAL_STATE_FLAG)).thenReturn("PENDING");
    when(session.getAttribute(APPROVAL_CHECKED_AT_FLAG)).thenReturn(freshStamp());

    filter.doFilterInternal(request, response, chain);

    verify(backendApiClient, never()).get(REGISTRATION_STATUS, RegistrationStatusDto.class);
    verify(response).sendRedirect("/pending-approval");
  }

  @Test
  void approvalStatus_whenPendingAndStale_picksUpApprovalWithoutRelogin() throws Exception {
    when(session.getAttribute(APPROVAL_STATE_FLAG)).thenReturn("PENDING");
    when(session.getAttribute(APPROVAL_CHECKED_AT_FLAG)).thenReturn(staleStamp());
    when(session.getAttribute(ROLES_SYNCED_AT_FLAG)).thenReturn(freshStamp());
    when(backendApiClient.get(REGISTRATION_STATUS, RegistrationStatusDto.class))
        .thenReturn(new RegistrationStatusDto("ACTIVE"));

    filter.doFilterInternal(request, response, chain);

    verify(response, never()).sendRedirect(anyString());
    verify(session).setAttribute(APPROVAL_STATE_FLAG, "ACTIVE");
    verify(session).removeAttribute(ROLES_SYNCED_AT_FLAG);
    verify(chain).doFilter(request, response);
  }

  @Test
  void roleSync_whenStampIsFresh_skipsBackendRead() throws Exception {
    when(session.getAttribute(APPROVAL_STATE_FLAG)).thenReturn("ACTIVE");
    when(session.getAttribute(ROLES_SYNCED_AT_FLAG)).thenReturn(freshStamp());

    filter.doFilterInternal(request, response, chain);

    verify(backendApiClient, never()).get(USERS_ME, UserDto.class);
    verify(chain).doFilter(request, response);
  }

  @Test
  void roleSync_whenStampIsStale_reReadsBackendRoles() throws Exception {
    when(session.getAttribute(APPROVAL_STATE_FLAG)).thenReturn("ACTIVE");
    when(session.getAttribute(ROLES_SYNCED_AT_FLAG)).thenReturn(staleStamp());
    when(backendApiClient.get(USERS_ME, UserDto.class)).thenReturn(null);

    filter.doFilterInternal(request, response, chain);

    verify(backendApiClient).get(USERS_ME, UserDto.class);
    verify(chain).doFilter(request, response);
  }

  @Test
  void staticAsset_skipsFilterBodyEntirely() throws Exception {
    when(request.getRequestURI()).thenReturn("/css/styles.css");

    filter.doFilterInternal(request, response, chain);

    verify(backendApiClient, never()).get(anyString(), eq(RegistrationStatusDto.class));
    verify(backendApiClient, never()).get(anyString(), eq(UserDto.class));
    verify(chain).doFilter(request, response);
  }

  /** Verifies that public documents skip the filter body entirely. */
  @Test
  void publicDocument_skipsFilterBodyEntirely() throws Exception {
    for (String path :
        new String[] {"/robots.txt", "/.well-known/assetlinks.json", "/manifest.webmanifest"}) {
      when(request.getRequestURI()).thenReturn(path);

      filter.doFilterInternal(request, response, chain);
    }

    verify(backendApiClient, never()).get(anyString(), eq(RegistrationStatusDto.class));
    verify(backendApiClient, never()).get(anyString(), eq(UserDto.class));
    verify(chain, times(3)).doFilter(request, response);
  }

  @Test
  void roleSync_dropsRoleTheBackendNoLongerGrants() throws Exception {
    authenticateWith("ROLE_OFFICER", "ROLE_ADMIN");
    when(backendApiClient.get(USERS_ME, UserDto.class))
        .thenReturn(userDto(Set.of("Officer"), Set.of()));

    filter.doFilterInternal(request, response, chain);

    assertThat(currentAuthorities()).contains("ROLE_OFFICER").doesNotContain("ROLE_ADMIN");
  }

  @Test
  void roleSync_dropsPermissionItPreviouslyGranted() throws Exception {
    authenticateWith("ROLE_OFFICER", "HANGAR_WRITE");
    when(session.getAttribute(SYNCED_AUTHORITIES_FLAG))
        .thenReturn(new ArrayList<>(List.of("ROLE_OFFICER", "HANGAR_WRITE")));
    when(backendApiClient.get(USERS_ME, UserDto.class))
        .thenReturn(userDto(Set.of("Officer"), Set.of()));

    filter.doFilterInternal(request, response, chain);

    assertThat(currentAuthorities()).contains("ROLE_OFFICER").doesNotContain("HANGAR_WRITE");
  }

  @Test
  void roleSync_neverDropsLoginOwnedAuthorities() throws Exception {
    authenticateWith("ROLE_OFFICER", "OIDC_USER", "SCOPE_openid", "SCOPE_profile");
    when(backendApiClient.get(USERS_ME, UserDto.class))
        .thenReturn(userDto(Set.of("Officer"), Set.of()));

    filter.doFilterInternal(request, response, chain);

    assertThat(currentAuthorities())
        .contains("ROLE_OFFICER", "OIDC_USER", "SCOPE_openid", "SCOPE_profile");
  }

  @Test
  void roleSync_whenBackendReportsNoRoleList_revokesNothing() throws Exception {
    authenticateWith("ROLE_OFFICER", "ROLE_ADMIN");
    when(backendApiClient.get(USERS_ME, UserDto.class)).thenReturn(userDto(null, null));

    filter.doFilterInternal(request, response, chain);

    assertThat(currentAuthorities()).contains("ROLE_OFFICER", "ROLE_ADMIN");
  }

  @Test
  void roleSync_grantsMembershipDerivedRoles() throws Exception {
    authenticateWith("ROLE_OFFICER");
    when(backendApiClient.get(USERS_ME, UserDto.class))
        .thenReturn(userDto(Set.of("Officer"), Set.of("HANGAR_READ"), true, true));

    filter.doFilterInternal(request, response, chain);

    assertThat(currentAuthorities())
        .contains("ROLE_OFFICER", "ROLE_LOGISTICIAN", "ROLE_MISSION_MANAGER", "HANGAR_READ");
  }

  @Test
  void roleSync_recordsWhatItAssertedForTheNextReconciliation() throws Exception {
    authenticateWith("ROLE_OFFICER");
    when(backendApiClient.get(USERS_ME, UserDto.class))
        .thenReturn(userDto(Set.of("Officer"), Set.of("HANGAR_READ")));

    filter.doFilterInternal(request, response, chain);

    ArgumentCaptor<ArrayList<String>> asserted = ArgumentCaptor.captor();
    verify(session).setAttribute(eq(SYNCED_AUTHORITIES_FLAG), asserted.capture());
    assertThat(asserted.getValue()).containsExactlyInAnyOrder("ROLE_OFFICER", "HANGAR_READ");
  }

  /** Replaces the security context with an OAuth2 token carrying exactly {@code authorities}. */
  private void authenticateWith(String... authorities) {
    List<SimpleGrantedAuthority> granted =
        java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
    OidcIdToken idToken = OidcIdToken.withTokenValue("token").subject("user-1").build();
    OidcUser oidcUser = new DefaultOidcUser(granted, idToken);
    SecurityContextHolder.getContext()
        .setAuthentication(new OAuth2AuthenticationToken(oidcUser, granted, "keycloak"));
  }

  /** The authority names currently on the security context, after the filter has reconciled it. */
  private static java.util.Set<String> currentAuthorities() {
    return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
        .map(org.springframework.security.core.GrantedAuthority::getAuthority)
        .collect(java.util.stream.Collectors.toSet());
  }

  /** A backend user carrying the given roles and permissions, with both membership flags off. */
  private static UserDto userDto(Set<String> roles, Set<String> permissions) {
    return userDto(roles, permissions, false, false);
  }

  /** A backend user carrying the given roles, permissions and org-unit membership flags. */
  private static UserDto userDto(
      Set<String> roles, Set<String> permissions, boolean logistician, boolean missionManager) {
    return new UserDto(
        UUID.randomUUID(),
        "officer",
        "Officer",
        "Officer",
        null,
        null,
        null,
        roles,
        permissions,
        null,
        logistician,
        missionManager,
        true,
        null,
        List.of(),
        1L,
        null,
        false);
  }

  @Test
  void forgetApprovalVerdict_dropsBothVerdictAttributes() {
    BackendRoleSyncFilter.forgetApprovalVerdict(session);

    verify(session).removeAttribute(APPROVAL_STATE_FLAG);
    verify(session).removeAttribute(APPROVAL_CHECKED_AT_FLAG);
  }

  @Test
  void forgetApprovalVerdict_leavesTheRoleSyncStampAlone() {
    BackendRoleSyncFilter.forgetApprovalVerdict(session);

    verify(session, never()).removeAttribute(ROLES_SYNCED_AT_FLAG);
    verify(session, never()).removeAttribute(SYNCED_AUTHORITIES_FLAG);
  }

  @Test
  void forgetApprovalVerdict_withoutASession_isANoOp() {
    assertThatCode(() -> BackendRoleSyncFilter.forgetApprovalVerdict(null))
        .doesNotThrowAnyException();
  }

  @Test
  void noRole_isDiscoveredByTheRoleRead_andRoutesThatSameRequest() throws Exception {
    Map<String, Object> attributes = statefulSession();
    when(backendApiClient.get(REGISTRATION_STATUS, RegistrationStatusDto.class))
        .thenReturn(new RegistrationStatusDto("ACTIVE"));
    when(backendApiClient.get(USERS_ME, UserDto.class)).thenThrow(noRoleRefusal());

    filter.doFilterInternal(request, response, chain);

    verify(response).sendRedirect("/pending-approval");
    verify(chain, never()).doFilter(request, response);
    assertThat(attributes).containsEntry(APPROVAL_STATE_FLAG, "NO_ROLE");
    assertThat(attributes).containsKey(APPROVAL_CHECKED_AT_FLAG);
    assertThat(attributes).doesNotContainKey(ROLES_SYNCED_AT_FLAG);
  }

  @Test
  void noRole_onTheDiscoveringRequest_stillLetsAnExemptPathThrough() throws Exception {
    statefulSession();
    when(request.getRequestURI()).thenReturn("/logout");
    when(backendApiClient.get(REGISTRATION_STATUS, RegistrationStatusDto.class))
        .thenReturn(new RegistrationStatusDto("ACTIVE"));
    when(backendApiClient.get(USERS_ME, UserDto.class)).thenThrow(noRoleRefusal());

    filter.doFilterInternal(request, response, chain);

    verify(chain).doFilter(request, response);
    verify(response, never()).sendRedirect(anyString());
  }

  @Test
  void noRole_cachedVerdict_routesWithoutAskingTheBackendAgain() throws Exception {
    when(session.getAttribute(APPROVAL_STATE_FLAG)).thenReturn("NO_ROLE");
    when(session.getAttribute(APPROVAL_CHECKED_AT_FLAG)).thenReturn(freshStamp());

    filter.doFilterInternal(request, response, chain);

    verify(response).sendRedirect("/pending-approval");
    verify(chain, never()).doFilter(request, response);
    verify(backendApiClient, never()).get(REGISTRATION_STATUS, RegistrationStatusDto.class);
    verify(backendApiClient, never()).get(USERS_ME, UserDto.class);
  }

  /**
   * The refusal the backend answers every {@code /api/v1} call with once the caller holds no role
   * at all (REQ-SEC-053): a 403 carrying the {@code NO_ROLE} problem code, not a 401 and not an
   * unmarked 403.
   *
   * @return a fresh exception instance shaped like the relayed RFC-7807 problem
   */
  private static BackendServiceException noRoleRefusal() {
    return new BackendServiceException(
        "no role", null, 403, "NO_ROLE", null, List.of(), "Your account holds no role.");
  }

  /**
   * Makes the mocked session map-backed, so an attribute written during the filter run is readable
   * later in the same run.
   *
   * @return the live backing map, for asserting what the filter wrote
   */
  private Map<String, Object> statefulSession() {
    Map<String, Object> attributes = new HashMap<>();
    when(session.getAttribute(anyString())).thenAnswer(call -> attributes.get(call.getArgument(0)));
    doAnswer(
            call -> {
              attributes.put(call.getArgument(0), call.getArgument(1));
              return null;
            })
        .when(session)
        .setAttribute(anyString(), any());
    doAnswer(
            call -> {
              attributes.remove(call.getArgument(0));
              return null;
            })
        .when(session)
        .removeAttribute(anyString());
    return attributes;
  }

  /** An epoch-millis stamp young enough that neither refresh interval has elapsed. */
  private static Long freshStamp() {
    return System.currentTimeMillis();
  }

  /** An epoch-millis stamp old enough that both refresh intervals have elapsed. */
  private static Long staleStamp() {
    return System.currentTimeMillis() - 600_000L;
  }
}
