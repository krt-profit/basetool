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
 * Unit tests for {@link BackendRoleSyncFilter}, covering three behaviours.
 *
 * <ul>
 *   <li>The "do not poison the session on a failed sync" contract (REQ-SEC-013): the {@code
 *       BACKEND_ROLES_SYNCED_AT} session stamp must only be written when the backend role read
 *       genuinely succeeded, so a transient backend outage on the first request of a session is
 *       retried instead of leaving the principal under-privileged.
 *   <li>The epic-#720 approval gate: a {@code PENDING}/{@code REJECTED} registration is redirected
 *       to {@code /pending-approval} on guarded paths but allowed through on the {@code
 *       isApprovalExempt} whitelist (e.g. {@code /logout}).
 *   <li>The staleness bounds that keep both pieces of session state honest (REQ-SEC-013): a
 *       terminal {@code ACTIVE} verdict is cached for good, a non-terminal one expires so an
 *       approval reaches a live session without a re-login (and immediately re-syncs the roles it
 *       unlocks), the role sync itself repeats on its own interval, and static assets skip the
 *       whole filter body so neither refresh costs a read per page asset.
 *   <li>The two-way reconciliation (ADR-0122): a {@code ROLE_*} the backend no longer reports is
 *       revoked, a permission only when a previous sync asserted it, the login-owned {@code
 *       OIDC_USER} / {@code SCOPE_*} authorities survive every sync, and a response with no
 *       role/permission list revokes nothing.
 *   <li>The role-less gate (REQ-SEC-053): a {@code 403 NO_ROLE} from the role read is a verdict of
 *       its own, and it routes the request that discovered it rather than the one after it.
 * </ul>
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
    // Every request now runs through the static-asset short-circuit, so the path must be readable.
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
    // Given — Resilience4j fallback hands back null (backend unavailable)
    when(backendApiClient.get(USERS_ME, UserDto.class)).thenReturn(null);

    // When
    filter.doFilterInternal(request, response, chain);

    // Then — flag stays unset so the next request retries; chain still proceeds
    verify(session, never()).setAttribute(eq(ROLES_SYNCED_AT_FLAG), any());
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
    verify(session, never()).setAttribute(eq(ROLES_SYNCED_AT_FLAG), any());
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

    // Then — successful read stamps the session with the sync time
    verify(session).setAttribute(eq(ROLES_SYNCED_AT_FLAG), any(Long.class));
    verify(chain).doFilter(request, response);
  }

  @Test
  void doFilterInternal_whenBackendServiceException_logsAtDebugNotError() throws Exception {
    // REQ-OBS-001: a relayed BackendServiceException was already logged once at the
    // BackendApiClient
    // boundary. syncRoles re-runs on every request until it succeeds, so re-logging it at ERROR
    // here
    // would turn one backend outage into a per-request ERROR storm and trip LogbackErrorSpike.
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
    // Given — the backend reports a PENDING registration and the request targets a guarded page
    when(request.getContextPath()).thenReturn("");
    when(request.getRequestURI()).thenReturn("/dashboard");
    when(backendApiClient.get(REGISTRATION_STATUS, RegistrationStatusDto.class))
        .thenReturn(new RegistrationStatusDto("PENDING"));

    // When
    filter.doFilterInternal(request, response, chain);

    // Then — routed to the waiting page and the chain is short-circuited (never the guest surface,
    // never the #720 403 storm), and the resolved status is cached with its read time so the
    // re-check interval can expire it
    verify(response).sendRedirect("/pending-approval");
    verify(chain, never()).doFilter(request, response);
    verify(session).setAttribute(APPROVAL_STATE_FLAG, "PENDING");
    verify(session).setAttribute(eq(APPROVAL_CHECKED_AT_FLAG), any(Long.class));
  }

  @Test
  void pendingApproval_statusPollPath_proceeds() throws Exception {
    // Given — the waiting page's own status poll must reach its handler, not be answered with a
    // redirect to the HTML page it is polling from
    when(request.getRequestURI()).thenReturn("/pending-approval/status");
    when(backendApiClient.get(REGISTRATION_STATUS, RegistrationStatusDto.class))
        .thenReturn(new RegistrationStatusDto("PENDING"));

    // When
    filter.doFilterInternal(request, response, chain);

    // Then
    verify(chain).doFilter(request, response);
    verify(response, never()).sendRedirect(anyString());
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
  void approvalStatus_whenActive_isCachedForGood_skipsRegistrationStatusFetch() throws Exception {
    // Given — a prior request resolved ACTIVE, which is terminal (the backend only ever decides a
    // still-PENDING registration), and roles are already synced (isolates the approval branch)
    when(session.getAttribute(APPROVAL_STATE_FLAG)).thenReturn("ACTIVE");
    when(session.getAttribute(APPROVAL_CHECKED_AT_FLAG)).thenReturn(staleStamp());
    when(session.getAttribute(ROLES_SYNCED_AT_FLAG)).thenReturn(freshStamp());

    // When
    filter.doFilterInternal(request, response, chain);

    // Then — never re-read, not even long after the re-check interval has passed
    verify(backendApiClient, never()).get(REGISTRATION_STATUS, RegistrationStatusDto.class);
    verify(chain).doFilter(request, response);
  }

  @Test
  void approvalStatus_whenPendingAndFresh_isNotRefetched() throws Exception {
    // Given — the PENDING verdict was read moments ago; a page load must not re-hit the backend for
    // every request inside the re-check interval
    when(session.getAttribute(APPROVAL_STATE_FLAG)).thenReturn("PENDING");
    when(session.getAttribute(APPROVAL_CHECKED_AT_FLAG)).thenReturn(freshStamp());

    // When
    filter.doFilterInternal(request, response, chain);

    // Then — cached verdict still routes to the waiting page, without a backend read
    verify(backendApiClient, never()).get(REGISTRATION_STATUS, RegistrationStatusDto.class);
    verify(response).sendRedirect("/pending-approval");
  }

  @Test
  void approvalStatus_whenPendingAndStale_picksUpApprovalWithoutRelogin() throws Exception {
    // Given — the session still carries the PENDING verdict cached before the admin decided, but it
    // has aged past the re-check interval and the backend now reports ACTIVE. This is the exact
    // situation that used to strand an approved member on the waiting page until they logged out
    // and back in: the verdict was pinned for the session's whole 720h lifetime.
    when(session.getAttribute(APPROVAL_STATE_FLAG)).thenReturn("PENDING");
    when(session.getAttribute(APPROVAL_CHECKED_AT_FLAG)).thenReturn(staleStamp());
    when(session.getAttribute(ROLES_SYNCED_AT_FLAG)).thenReturn(freshStamp());
    when(backendApiClient.get(REGISTRATION_STATUS, RegistrationStatusDto.class))
        .thenReturn(new RegistrationStatusDto("ACTIVE"));

    // When
    filter.doFilterInternal(request, response, chain);

    // Then — the user is let through on this very request, the new verdict is cached, and the
    // role-sync stamp is dropped so the authorities the approval unlocks are pulled immediately
    // instead of up to a re-sync interval later (the second forced re-login).
    verify(response, never()).sendRedirect(anyString());
    verify(session).setAttribute(APPROVAL_STATE_FLAG, "ACTIVE");
    verify(session).removeAttribute(ROLES_SYNCED_AT_FLAG);
    verify(chain).doFilter(request, response);
  }

  @Test
  void roleSync_whenStampIsFresh_skipsBackendRead() throws Exception {
    // Given — an approved session that synced its roles moments ago
    when(session.getAttribute(APPROVAL_STATE_FLAG)).thenReturn("ACTIVE");
    when(session.getAttribute(ROLES_SYNCED_AT_FLAG)).thenReturn(freshStamp());

    // When
    filter.doFilterInternal(request, response, chain);

    // Then — no per-request role read
    verify(backendApiClient, never()).get(USERS_ME, UserDto.class);
    verify(chain).doFilter(request, response);
  }

  @Test
  void roleSync_whenStampIsStale_reReadsBackendRoles() throws Exception {
    // Given — an approved session whose synced roles have aged past the re-sync interval. Without
    // this refresh a role/unit granted after login stayed invisible to the frontend's authority
    // gates until the user started a new session — the second half of the double-re-login report.
    when(session.getAttribute(APPROVAL_STATE_FLAG)).thenReturn("ACTIVE");
    when(session.getAttribute(ROLES_SYNCED_AT_FLAG)).thenReturn(staleStamp());
    when(backendApiClient.get(USERS_ME, UserDto.class)).thenReturn(null);

    // When
    filter.doFilterInternal(request, response, chain);

    // Then — the backend roles are read again
    verify(backendApiClient).get(USERS_ME, UserDto.class);
    verify(chain).doFilter(request, response);
  }

  @Test
  void staticAsset_skipsFilterBodyEntirely() throws Exception {
    // Given — a CSS request on a session that has resolved nothing yet. Both refreshes are
    // TTL-driven now, so without this short-circuit every asset of a page load would be a candidate
    // for a backend read.
    when(request.getRequestURI()).thenReturn("/css/styles.css");

    // When
    filter.doFilterInternal(request, response, chain);

    // Then — no backend traffic at all, and the asset is served
    verify(backendApiClient, never()).get(anyString(), eq(RegistrationStatusDto.class));
    verify(backendApiClient, never()).get(anyString(), eq(UserDto.class));
    verify(chain).doFilter(request, response);
  }

  @Test
  void roleSync_dropsRoleTheBackendNoLongerGrants() throws Exception {
    // Given — the token still carries ROLE_ADMIN from login, but the backend (whose local mirror
    // is what its own @PreAuthorize gates read) now only reports Officer. Keeping ROLE_ADMIN would
    // render admin UI that 403s on every click for the rest of the 720h session.
    authenticateWith("ROLE_OFFICER", "ROLE_ADMIN");
    when(backendApiClient.get(USERS_ME, UserDto.class))
        .thenReturn(userDto(Set.of("Officer"), Set.of()));

    // When
    filter.doFilterInternal(request, response, chain);

    // Then
    assertThat(currentAuthorities()).contains("ROLE_OFFICER").doesNotContain("ROLE_ADMIN");
  }

  @Test
  void roleSync_dropsPermissionItPreviouslyGranted() throws Exception {
    // Given — a permission this filter granted on an earlier sync, which the backend no longer
    // reports. It is revocable precisely because the session records that WE asserted it.
    authenticateWith("ROLE_OFFICER", "HANGAR_WRITE");
    when(session.getAttribute(SYNCED_AUTHORITIES_FLAG))
        .thenReturn(new ArrayList<>(List.of("ROLE_OFFICER", "HANGAR_WRITE")));
    when(backendApiClient.get(USERS_ME, UserDto.class))
        .thenReturn(userDto(Set.of("Officer"), Set.of()));

    // When
    filter.doFilterInternal(request, response, chain);

    // Then
    assertThat(currentAuthorities()).contains("ROLE_OFFICER").doesNotContain("HANGAR_WRITE");
  }

  @Test
  void roleSync_neverDropsLoginOwnedAuthorities() throws Exception {
    // Given — OIDC_USER / SCOPE_* are granted by the login, not by this filter, and carry no
    // prefix that would tell them apart from a permission. They must survive a sync that asserts
    // neither, which the "only revoke what we asserted" rule guarantees structurally.
    authenticateWith("ROLE_OFFICER", "OIDC_USER", "SCOPE_openid", "SCOPE_profile");
    when(backendApiClient.get(USERS_ME, UserDto.class))
        .thenReturn(userDto(Set.of("Officer"), Set.of()));

    // When
    filter.doFilterInternal(request, response, chain);

    // Then
    assertThat(currentAuthorities())
        .contains("ROLE_OFFICER", "OIDC_USER", "SCOPE_openid", "SCOPE_profile");
  }

  @Test
  void roleSync_whenBackendReportsNoRoleList_revokesNothing() throws Exception {
    // Given — a response that says nothing about roles must never be read as "everything is
    // revoked"; the caller keeps what it has and the next sync decides.
    authenticateWith("ROLE_OFFICER", "ROLE_ADMIN");
    when(backendApiClient.get(USERS_ME, UserDto.class)).thenReturn(userDto(null, null));

    // When
    filter.doFilterInternal(request, response, chain);

    // Then
    assertThat(currentAuthorities()).contains("ROLE_OFFICER", "ROLE_ADMIN");
  }

  @Test
  void roleSync_grantsMembershipDerivedRoles() throws Exception {
    // Given — the two org-unit membership flags are asserted as flat roles, alongside the catalog
    // roles and the flattened permissions.
    authenticateWith("ROLE_OFFICER");
    when(backendApiClient.get(USERS_ME, UserDto.class))
        .thenReturn(userDto(Set.of("Officer"), Set.of("HANGAR_READ"), true, true));

    // When
    filter.doFilterInternal(request, response, chain);

    // Then
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
    // The seam PendingApprovalPageController uses to break the redirect loop: it sends an ACTIVE
    // caller off the waiting page, but that page is only reachable because this filter believes the
    // caller is NOT approved — and it serves that belief from the session for a full recheck
    // interval without a backend read. Leaving either attribute behind leaves the two disagreeing,
    // and the browser bounces between "/" and "/pending-approval" at full speed.
    BackendRoleSyncFilter.forgetApprovalVerdict(session);

    verify(session).removeAttribute(APPROVAL_STATE_FLAG);
    verify(session).removeAttribute(APPROVAL_CHECKED_AT_FLAG);
  }

  @Test
  void forgetApprovalVerdict_leavesTheRoleSyncStampAlone() {
    // Only the approval verdict is stale; discarding the role-sync stamp too would buy an extra
    // /api/v1/users/me read per redirect for nothing.
    BackendRoleSyncFilter.forgetApprovalVerdict(session);

    verify(session, never()).removeAttribute(ROLES_SYNCED_AT_FLAG);
    verify(session, never()).removeAttribute(SYNCED_AUTHORITIES_FLAG);
  }

  @Test
  void forgetApprovalVerdict_withoutASession_isANoOp() {
    // getSession(false) yields null for a session-less request; there is no verdict to forget.
    assertThatCode(() -> BackendRoleSyncFilter.forgetApprovalVerdict(null))
        .doesNotThrowAnyException();
  }

  @Test
  void noRole_isDiscoveredByTheRoleRead_andRoutesThatSameRequest() throws Exception {
    // Given - an approved account holding no role. The registration status says ACTIVE (it IS
    // approved), so the approval gate above lets it through; the refusal only surfaces one step
    // later, when the role read comes back 403 NO_ROLE.
    Map<String, Object> attributes = statefulSession();
    when(backendApiClient.get(REGISTRATION_STATUS, RegistrationStatusDto.class))
        .thenReturn(new RegistrationStatusDto("ACTIVE"));
    when(backendApiClient.get(USERS_ME, UserDto.class)).thenThrow(noRoleRefusal());

    // When
    filter.doFilterInternal(request, response, chain);

    // Then - the discovering request is the one that gets routed. Serving it and routing only the
    // next navigation would render the dashboard to a member who may see nothing on it: every
    // fragment on that page answers 403 NO_ROLE, so the page arrives empty and the account-status
    // copy explaining why arrives one click late.
    verify(response).sendRedirect("/pending-approval");
    verify(chain, never()).doFilter(request, response);
    assertThat(attributes).containsEntry(APPROVAL_STATE_FLAG, "NO_ROLE");
    assertThat(attributes).containsKey(APPROVAL_CHECKED_AT_FLAG);
    // The sync did not succeed, so its stamp stays unset and the next request retries (REQ-SEC-013)
    // - which is what lets an administrator's role grant reach the session without a re-login.
    assertThat(attributes).doesNotContainKey(ROLES_SYNCED_AT_FLAG);
  }

  @Test
  void noRole_onTheDiscoveringRequest_stillLetsAnExemptPathThrough() throws Exception {
    // Given - the same discovery, but on /logout. A role-less member must be able to leave; the
    // account-status page is a dead end, not a trap.
    statefulSession();
    when(request.getRequestURI()).thenReturn("/logout");
    when(backendApiClient.get(REGISTRATION_STATUS, RegistrationStatusDto.class))
        .thenReturn(new RegistrationStatusDto("ACTIVE"));
    when(backendApiClient.get(USERS_ME, UserDto.class)).thenThrow(noRoleRefusal());

    // When
    filter.doFilterInternal(request, response, chain);

    // Then
    verify(chain).doFilter(request, response);
    verify(response, never()).sendRedirect(anyString());
  }

  @Test
  void noRole_cachedVerdict_routesWithoutAskingTheBackendAgain() throws Exception {
    // Given - a session that already knows, stamped just now. The verdict is cached precisely so
    // the next navigation costs no round trip; without it every click of a role-less member would
    // repeat the same two refusals.
    when(session.getAttribute(APPROVAL_STATE_FLAG)).thenReturn("NO_ROLE");
    when(session.getAttribute(APPROVAL_CHECKED_AT_FLAG)).thenReturn(freshStamp());

    // When
    filter.doFilterInternal(request, response, chain);

    // Then
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
   * Turns the mocked session into a map-backed one, so an attribute written during the filter run
   * is readable later in that same run. The role-less routing depends on exactly that: {@code
   * syncRoles} records its verdict as a session attribute and {@code doFilterInternal} reads it
   * back to decide whether to redirect, which a plain {@code mock(HttpSession.class)} - where
   * {@code setAttribute} is silently discarded - would never let happen.
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
