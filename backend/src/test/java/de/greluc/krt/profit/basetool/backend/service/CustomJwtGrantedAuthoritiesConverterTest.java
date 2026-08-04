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

package de.greluc.krt.profit.basetool.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.support.IngestGatewayProperties;
import de.greluc.krt.profit.basetool.backend.support.OrgUnitContextualAuthority;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Mockito unit tests for {@link CustomJwtGrantedAuthoritiesConverter}, focused on the epic #692 /
 * REQ-ORG-015 cascade: a Bereichsleitung / OL leadership membership must mint officer-equivalent
 * flat roles ({@code ROLE_LOGISTICIAN} / {@code ROLE_MISSION_MANAGER}) plus contextual authorities
 * for every org unit the leadership reaches downward, and a plain member must be unaffected.
 */
@ExtendWith(MockitoExtension.class)
class CustomJwtGrantedAuthoritiesConverterTest {

  @Mock private UserReconciliationService userReconciliationService;
  @Mock private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Mock private OrgUnitCascadeService orgUnitCascadeService;
  @Mock private Jwt jwt;

  /**
   * A real instance, not a mock: its default is the empty allowlist, which is the state every one
   * of these tests needs — no caller is a gateway, so the machine-identity carve-out never fires
   * and each case exercises the ordinary member path it was written for (ADR-0129).
   */
  @Spy
  private final IngestGatewayProperties ingestGatewayProperties = new IngestGatewayProperties();

  @InjectMocks private CustomJwtGrantedAuthoritiesConverter converter;

  private static final UUID USER_ID = UUID.randomUUID();
  private static final UUID BEREICH_ID = UUID.randomUUID();
  private static final UUID DESCENDANT_STAFFEL_ID = UUID.randomUUID();
  private static final UUID DESCENDANT_SK_ID = UUID.randomUUID();

  /**
   * A configured gateway is a machine: exactly one marker authority, and no registration.
   *
   * <p>The carve-out shipped untested, which is how the defect it fixes reached production in the
   * first place. Both halves are asserted, because each fails differently:
   *
   * <ul>
   *   <li>The authority set is {@code ROLE_INGEST_GATEWAY} and <em>nothing else</em>. A named
   *       authority rather than an empty set, so a misconfiguration reads as "authenticated as a
   *       machine" instead of "not authenticated" — and nothing extra, so the gateway's own bearer
   *       can reach no member surface.
   *   <li>{@code userReconciliationService} is never touched. That is the actual production
   *       failure: the gateway's first call created an {@code app_user} row for itself, stamped it
   *       PENDING, granted the default blueprints, notified the admins, and then 403'd its own
   *       account. Asserting only the authorities would still pass while all of that happened.
   * </ul>
   */
  @Test
  void grantsAConfiguredGatewayTheMachineAuthorityAndNeverRegistersIt() {
    ingestGatewayProperties.setClientIds(List.of("test-ingest-gateway"));
    when(jwt.getClaimAsString("azp")).thenReturn("test-ingest-gateway");

    Collection<GrantedAuthority> authorities = converter.convert(jwt);

    assertEquals(
        List.of(CustomJwtGrantedAuthoritiesConverter.GATEWAY_AUTHORITY),
        authorities.stream().map(GrantedAuthority::getAuthority).toList());
    verifyNoInteractions(userReconciliationService);
  }

  /**
   * A caller whose {@code azp} is not on the allowlist takes the ordinary member path.
   *
   * <p>The complement of the case above: without it, a carve-out that matched everything would
   * still pass, and every member would authenticate as a machine with no roles at all.
   */
  @Test
  void leavesANonGatewayCallerOnTheOrdinaryMemberPath() {
    ingestGatewayProperties.setClientIds(List.of("test-ingest-gateway"));
    when(jwt.getClaimAsString("azp")).thenReturn("basetool-frontend");
    when(userReconciliationService.syncUser(jwt)).thenReturn(userWithNoRoles());

    Collection<GrantedAuthority> authorities = converter.convert(jwt);

    assertFalse(
        authorities.stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(CustomJwtGrantedAuthoritiesConverter.GATEWAY_AUTHORITY::equals));
    verify(userReconciliationService).syncUser(jwt);
  }

  private User userWithNoRoles() {
    User user = new User();
    user.setId(USER_ID);
    return user;
  }

  private static OrgUnitMembership membership(UUID orgUnitId, OrgUnitKind kind) {
    OrgUnitMembership m = new OrgUnitMembership();
    m.setId(new OrgUnitMembershipId(USER_ID, orgUnitId));
    m.setKind(kind);
    return m;
  }

  @Test
  void convert_sameToken_memoisesAuthorities_assemblesOnce() {
    // #1141: two calls with the SAME token (same sub + issuedAt) must resolve the authorities once
    // and serve the second from the cache — no second syncUser / membership read.
    when(jwt.getSubject()).thenReturn("sub-1");
    when(jwt.getIssuedAt()).thenReturn(Instant.ofEpochSecond(1_700_000_000L));
    when(userReconciliationService.syncUser(jwt)).thenReturn(userWithNoRoles());
    when(orgUnitMembershipRepository.findAllByIdUserId(USER_ID)).thenReturn(List.of());

    Collection<GrantedAuthority> first = converter.convert(jwt);
    Collection<GrantedAuthority> second = converter.convert(jwt);

    assertEquals(first, second, "the memoised result must equal the freshly assembled one");
    verify(userReconciliationService, times(1)).syncUser(jwt);
    verify(orgUnitMembershipRepository, times(1)).findAllByIdUserId(USER_ID);
  }

  @Test
  void convert_freshlyIssuedToken_missesCache_reassembles() {
    // A re-login / token refresh carries a new issuedAt, so the key differs and the authorities are
    // re-read — a role/approval change takes effect on re-authentication (#1141).
    when(jwt.getSubject()).thenReturn("sub-1");
    when(jwt.getIssuedAt())
        .thenReturn(Instant.ofEpochSecond(1_700_000_000L), Instant.ofEpochSecond(1_700_000_300L));
    when(userReconciliationService.syncUser(jwt)).thenReturn(userWithNoRoles());
    when(orgUnitMembershipRepository.findAllByIdUserId(USER_ID)).thenReturn(List.of());

    converter.convert(jwt);
    converter.convert(jwt);

    verify(userReconciliationService, times(2)).syncUser(jwt);
  }

  @Test
  void convert_tokenWithoutIssuedAt_bypassesCache() {
    // An unkeyable token (missing issuedAt) must never be cached — always recompute, never risk
    // serving a stale result under a degenerate key (#1141).
    when(jwt.getSubject()).thenReturn("sub-1");
    when(userReconciliationService.syncUser(jwt)).thenReturn(userWithNoRoles());
    when(orgUnitMembershipRepository.findAllByIdUserId(USER_ID)).thenReturn(List.of());

    converter.convert(jwt);
    converter.convert(jwt);

    verify(userReconciliationService, times(2)).syncUser(jwt);
  }

  @Test
  void plainStaffelMember_getsNoLeadershipRolesAndNoCascade() {
    when(userReconciliationService.syncUser(jwt)).thenReturn(userWithNoRoles());
    OrgUnitMembership plain = membership(DESCENDANT_STAFFEL_ID, OrgUnitKind.SQUADRON);
    when(orgUnitMembershipRepository.findAllByIdUserId(USER_ID)).thenReturn(List.of(plain));
    when(orgUnitCascadeService.cascadedOfficerReach(any())).thenReturn(Set.of());

    Collection<GrantedAuthority> authorities = converter.convert(jwt);

    assertFalse(authorities.contains(new SimpleGrantedAuthority("ROLE_LOGISTICIAN")));
    assertFalse(authorities.contains(new SimpleGrantedAuthority("ROLE_MISSION_MANAGER")));
    assertFalse(
        authorities.contains(new OrgUnitContextualAuthority("LOGISTICIAN", DESCENDANT_STAFFEL_ID)));
  }

  @Test
  void bereichsleiter_getsFlatRolesAndCascadedContextualAuthorities() {
    when(userReconciliationService.syncUser(jwt)).thenReturn(userWithNoRoles());
    OrgUnitMembership lead = membership(BEREICH_ID, OrgUnitKind.BEREICH);
    lead.setRole(MembershipRole.BEREICHSLEITER);
    when(orgUnitMembershipRepository.findAllByIdUserId(USER_ID)).thenReturn(List.of(lead));
    when(orgUnitCascadeService.cascadedOfficerReach(any()))
        .thenReturn(Set.of(BEREICH_ID, DESCENDANT_STAFFEL_ID, DESCENDANT_SK_ID));

    Collection<GrantedAuthority> authorities = converter.convert(jwt);

    // Officer-equivalent flat roles (back-compat for role-only @PreAuthorize gates).
    assertTrue(authorities.contains(new SimpleGrantedAuthority("ROLE_LOGISTICIAN")));
    assertTrue(authorities.contains(new SimpleGrantedAuthority("ROLE_MISSION_MANAGER")));
    // Contextual authorities for every cascaded unit, both roles.
    for (UUID reached : List.of(BEREICH_ID, DESCENDANT_STAFFEL_ID, DESCENDANT_SK_ID)) {
      assertTrue(authorities.contains(new OrgUnitContextualAuthority("LOGISTICIAN", reached)));
      assertTrue(authorities.contains(new OrgUnitContextualAuthority("MISSION_MANAGER", reached)));
    }
  }

  @Test
  void olMember_getsFlatRolesAndContextualAuthoritiesForEveryReachedUnit() {
    when(userReconciliationService.syncUser(jwt)).thenReturn(userWithNoRoles());
    UUID olId = UUID.randomUUID();
    OrgUnitMembership ol = membership(olId, OrgUnitKind.ORGANISATIONSLEITUNG);
    ol.setRole(MembershipRole.OL_MEMBER);
    when(orgUnitMembershipRepository.findAllByIdUserId(USER_ID)).thenReturn(List.of(ol));
    // The cascade service resolves OL reach to the concrete union of every org unit.
    when(orgUnitCascadeService.cascadedOfficerReach(any()))
        .thenReturn(Set.of(olId, BEREICH_ID, DESCENDANT_STAFFEL_ID));

    Collection<GrantedAuthority> authorities = converter.convert(jwt);

    assertTrue(authorities.contains(new SimpleGrantedAuthority("ROLE_LOGISTICIAN")));
    assertTrue(authorities.contains(new SimpleGrantedAuthority("ROLE_MISSION_MANAGER")));
    assertTrue(
        authorities.contains(new OrgUnitContextualAuthority("LOGISTICIAN", DESCENDANT_STAFFEL_ID)));
  }

  @Test
  void staffelleiter_getsFlatRolesAndOwnSquadronContextualOnly_noCascade() {
    // REQ-ROLE-002: a squadron leadership rank confers officer-equivalent reach over its OWN
    // squadron only, exactly as SK_LEAD does — the flat back-compat roles plus the own-unit
    // contextual authorities, but NO downward cascade and no contextual authority for any foreign
    // unit. The cascade service yields nothing for a squadron rank (verified in
    // OrgUnitCascadeService
    // tests), so the only reach is the own-squadron contextual minted by the per-row loop.
    when(userReconciliationService.syncUser(jwt)).thenReturn(userWithNoRoles());
    UUID squadronId = UUID.randomUUID();
    OrgUnitMembership lead = membership(squadronId, OrgUnitKind.SQUADRON);
    lead.setRole(MembershipRole.STAFFELLEITER);
    when(orgUnitMembershipRepository.findAllByIdUserId(USER_ID)).thenReturn(List.of(lead));
    when(orgUnitCascadeService.cascadedOfficerReach(any())).thenReturn(Set.of());

    Collection<GrantedAuthority> authorities = converter.convert(jwt);

    // Officer-equivalent flat roles, exactly as SK_LEAD (back-compat for role-only @PreAuthorize).
    assertTrue(authorities.contains(new SimpleGrantedAuthority("ROLE_LOGISTICIAN")));
    assertTrue(authorities.contains(new SimpleGrantedAuthority("ROLE_MISSION_MANAGER")));
    // Own-squadron contextual authorities (minted by the per-row loop, not the cascade).
    assertTrue(authorities.contains(new OrgUnitContextualAuthority("LOGISTICIAN", squadronId)));
    assertTrue(authorities.contains(new OrgUnitContextualAuthority("MISSION_MANAGER", squadronId)));
    // ...and NOTHING for any other unit — no downward cascade, no cross-unit contextual reach.
    UUID foreignUnit = UUID.randomUUID();
    assertFalse(authorities.contains(new OrgUnitContextualAuthority("LOGISTICIAN", foreignUnit)));
    assertFalse(
        authorities.contains(new OrgUnitContextualAuthority("MISSION_MANAGER", foreignUnit)));
  }

  @Test
  void pendingRegistration_getsOnlyPendingApprovalAndNeverConsultsMembership() {
    // REQ-SEC-017: a PENDING registration is granted NO authorities except ROLE_PENDING_APPROVAL —
    // the entire assembly is short-circuited, so membership/cascade are never consulted and
    // ROLE_GUEST is not carried.
    User pending = userWithNoRoles();
    pending.setApprovalStatus(ApprovalStatus.PENDING);
    when(userReconciliationService.syncUser(jwt)).thenReturn(pending);

    Collection<GrantedAuthority> authorities = converter.convert(jwt);

    assertEquals(
        List.of(new SimpleGrantedAuthority("ROLE_PENDING_APPROVAL")), List.copyOf(authorities));
    assertFalse(authorities.contains(new SimpleGrantedAuthority("ROLE_GUEST")));
    verifyNoInteractions(orgUnitMembershipRepository, orgUnitCascadeService);
  }

  @Test
  void rejectedRegistration_getsOnlyPendingApprovalAuthority() {
    // A REJECTED account is treated like PENDING — no authorities, routed to the waiting page.
    User rejected = userWithNoRoles();
    rejected.setApprovalStatus(ApprovalStatus.REJECTED);
    when(userReconciliationService.syncUser(jwt)).thenReturn(rejected);

    Collection<GrantedAuthority> authorities = converter.convert(jwt);

    assertEquals(
        List.of(new SimpleGrantedAuthority("ROLE_PENDING_APPROVAL")), List.copyOf(authorities));
    verifyNoInteractions(orgUnitMembershipRepository, orgUnitCascadeService);
  }

  @Test
  void memberlessUser_getsNoMembershipDerivedAuthorities() {
    when(userReconciliationService.syncUser(jwt)).thenReturn(userWithNoRoles());
    when(orgUnitMembershipRepository.findAllByIdUserId(USER_ID)).thenReturn(List.of());
    // No memberships → the converter short-circuits before consulting the cascade.
    lenient().when(orgUnitCascadeService.cascadedOfficerReach(any())).thenReturn(Set.of());

    Collection<GrantedAuthority> authorities = converter.convert(jwt);

    assertFalse(authorities.contains(new SimpleGrantedAuthority("ROLE_LOGISTICIAN")));
    assertFalse(authorities.contains(new SimpleGrantedAuthority("ROLE_MISSION_MANAGER")));
  }

  @Test
  void convert_retriesOnOptimisticLockThenSucceeds() {
    // Concurrency invariant: a transient ObjectOptimisticLockingFailureException from concurrent
    // first-time logins by the same sub must NOT fail the authentication — the retry loop re-runs
    // syncUser and the second attempt succeeds, so the authorities resolve normally. A regression
    // that rethrew immediately (no retry) would deny a legitimate concurrent login.
    when(userReconciliationService.syncUser(jwt))
        .thenThrow(new ObjectOptimisticLockingFailureException(User.class, USER_ID))
        .thenReturn(userWithNoRoles());
    when(orgUnitMembershipRepository.findAllByIdUserId(USER_ID)).thenReturn(List.of());

    Collection<GrantedAuthority> authorities = converter.convert(jwt);

    assertNotNull(authorities, "the retried sync must resolve to a real authority collection");
    verify(userReconciliationService, times(2)).syncUser(jwt);
  }

  @Test
  void convert_exhaustsRetries_throwsAuthenticationServiceException() {
    // After MAX_SYNC_ATTEMPTS (3) consecutive optimistic-lock failures the converter must reject
    // the
    // authentication with AuthenticationServiceException rather than fall through with null/empty
    // authorities (which would silently treat the caller as unauthenticated). Exactly three
    // attempts
    // are made before giving up.
    when(userReconciliationService.syncUser(jwt))
        .thenThrow(new ObjectOptimisticLockingFailureException(User.class, USER_ID));

    assertThrows(AuthenticationServiceException.class, () -> converter.convert(jwt));
    verify(userReconciliationService, times(3)).syncUser(jwt);
  }
}
