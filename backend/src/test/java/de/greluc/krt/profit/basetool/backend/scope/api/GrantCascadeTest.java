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

package de.greluc.krt.profit.basetool.backend.scope.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipRole;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitCascadeService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

/**
 * The Logistician and Mission Manager grant cascade (REQ-SEC-005) over the real scope chain: a
 * grant in a Bereich covers the Bereich and the Staffeln and Spezialkommandos below it, a grant in
 * the Organisationsleitung covers every unit, a grant in a Staffel covers that Staffel, and an
 * honoured pin must lie in the covered set (REQ-ORG-032). The grants are the contextual authorities
 * the JWT converter mints for a direct flag, which name the granting unit alone.
 */
class GrantCascadeTest {

  private static final UUID CALLER = UUID.randomUUID();
  private static final UUID OL = UUID.randomUUID();
  private static final UUID BEREICH_X = UUID.randomUUID();
  private static final UUID STAFFEL_X1 = UUID.randomUUID();
  private static final UUID SK_X2 = UUID.randomUUID();
  private static final UUID BEREICH_Y = UUID.randomUUID();
  private static final UUID STAFFEL_Y1 = UUID.randomUUID();
  private static final List<UUID> ALL =
      List.of(OL, BEREICH_X, STAFFEL_X1, SK_X2, BEREICH_Y, STAFFEL_Y1);

  /** The org tree: OL above the Bereiche X and Y; X holds a Staffel and an SK, Y a Staffel. */
  private static final java.util.Map<UUID, List<UUID>> CHILDREN =
      java.util.Map.of(
          OL, List.of(BEREICH_X, BEREICH_Y),
          BEREICH_X, List.of(STAFFEL_X1, SK_X2),
          BEREICH_Y, List.of(STAFFEL_Y1));

  /** The real gate chain for one caller over the org tree. */
  private static final class Chain {

    final RequestScopeResolver requestScopeResolver;
    final AccessGateService accessGateService;

    Chain(String role, Set<UUID> grants, boolean admin, UUID pin) {
      this(role, grants, admin, pin, Set.of());
    }

    Chain(String role, Set<UUID> grants, boolean admin, UUID pin, Set<UUID> memberOf) {
      AuthHelperService authHelper = mock(AuthHelperService.class);
      when(authHelper.isAdmin()).thenReturn(admin);
      when(authHelper.isAuthenticated()).thenReturn(true);
      when(authHelper.holdsKrtMember()).thenReturn(true);
      when(authHelper.currentUserId()).thenReturn(Optional.of(CALLER));
      List<GrantedAuthority> authorities =
          grants.stream()
              .<GrantedAuthority>map(unit -> new OrgUnitContextualAuthority(role, unit))
              .toList();
      when(authHelper.currentAuthentication())
          .thenReturn(
              Optional.of(new UsernamePasswordAuthenticationToken("caller", "n/a", authorities)));

      OrgUnitRepository orgUnits = mock(OrgUnitRepository.class);
      when(orgUnits.existsByIdAndActiveTrue(any())).thenReturn(true);
      when(orgUnits.findOrganisationsleitungIdsAmong(anyCollection()))
          .thenAnswer(
              call -> call.<Collection<UUID>>getArgument(0).stream().filter(OL::equals).toList());
      when(orgUnits.findAllActiveOrgUnitIds()).thenReturn(ALL);
      when(orgUnits.findActiveChildOrgUnitIdsIn(anyCollection()))
          .thenAnswer(call ->
              call.<Collection<UUID>>getArgument(0).stream()
                  .flatMap(parent -> CHILDREN.getOrDefault(parent, List.of()).stream())
                  .toList());
      when(orgUnits.findLeadershipOrgUnitIdsAmong(anyCollection()))
          .thenAnswer(call ->
              call.<Collection<UUID>>getArgument(0).stream()
                  .filter(id -> id.equals(OL) || id.equals(BEREICH_X) || id.equals(BEREICH_Y))
                  .toList());

      OrgUnitMembershipRepository memberships = mock(OrgUnitMembershipRepository.class);
      when(memberships.findAllOnActiveOrgUnitsByIdUserId(CALLER))
          .thenReturn(memberOf.stream().map(GrantCascadeTest::plainMembership).toList());

      MockHttpServletRequest request = new MockHttpServletRequest();
      if (pin != null) {
        request.addHeader(RequestScopeResolver.ACTIVE_ORG_UNIT_HEADER, pin.toString());
      }
      RequestScopeResolver resolver =
          new RequestScopeResolver(
              authHelper, memberships, orgUnits, new OrgUnitCascadeService(orgUnits), request);
      requestScopeResolver = resolver;
      accessGateService = new AccessGateService(resolver, authHelper);
    }
  }

  private static Set<UUID> unitsWhere(Predicate<UUID> gate) {
    return ALL.stream().filter(gate).collect(java.util.stream.Collectors.toSet());
  }

  private static OrgUnitMembership plainMembership(UUID orgUnitId) {
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(CALLER, orgUnitId));
    membership.setKind(kindOf(orgUnitId));
    membership.setRole(MembershipRole.MEMBER);
    return membership;
  }

  private static OrgUnitKind kindOf(UUID orgUnitId) {
    if (orgUnitId.equals(OL)) {
      return OrgUnitKind.ORGANISATIONSLEITUNG;
    }
    if (CHILDREN.containsKey(orgUnitId)) {
      return OrgUnitKind.BEREICH;
    }
    return orgUnitId.equals(SK_X2) ? OrgUnitKind.SPECIAL_COMMAND : OrgUnitKind.SQUADRON;
  }

  @ParameterizedTest
  @ValueSource(strings = {Roles.LOGISTICIAN, Roles.MISSION_MANAGER})
  void aBereichGrantCoversTheBereichItsStaffelnAndItsSpezialkommandosOnly(String role) {
    Chain chain = new Chain(role, Set.of(BEREICH_X), false, null);

    assertThat(unitsWhere(unit -> chain.accessGateService.hasRoleInOrgUnit(unit, role)))
        .containsExactlyInAnyOrder(BEREICH_X, STAFFEL_X1, SK_X2);
  }

  @ParameterizedTest
  @ValueSource(strings = {Roles.LOGISTICIAN, Roles.MISSION_MANAGER})
  void anOrganisationsleitungGrantCoversEveryUnit(String role) {
    Chain chain = new Chain(role, Set.of(OL), false, null);

    assertThat(unitsWhere(unit -> chain.accessGateService.hasRoleInOrgUnit(unit, role)))
        .containsExactlyInAnyOrderElementsOf(ALL);
  }

  @ParameterizedTest
  @ValueSource(strings = {Roles.LOGISTICIAN, Roles.MISSION_MANAGER})
  void aStaffelGrantCoversThatStaffelOnly(String role) {
    Chain chain = new Chain(role, Set.of(STAFFEL_X1), false, null);

    assertThat(unitsWhere(unit -> chain.accessGateService.hasRoleInOrgUnit(unit, role)))
        .containsExactly(STAFFEL_X1);
  }

  @Test
  void aGrantOfTheOtherRoleCoversNothing() {
    Chain chain = new Chain(Roles.MISSION_MANAGER, Set.of(OL), false, null);

    assertThat(
            unitsWhere(unit -> chain.accessGateService.hasRoleInOrgUnit(unit, Roles.LOGISTICIAN)))
        .isEmpty();
  }

  @Test
  void theLogisticianGateAndListScopeFollowTheCascadeUnpinned() {
    Chain chain = new Chain(Roles.LOGISTICIAN, Set.of(BEREICH_X), false, null);

    assertThat(unitsWhere(chain.accessGateService::canActAsLogisticianIn))
        .containsExactlyInAnyOrder(BEREICH_X, STAFFEL_X1, SK_X2);
    assertThat(chain.accessGateService.currentLogisticianOrgUnitIds())
        .containsExactlyInAnyOrder(BEREICH_X, STAFFEL_X1, SK_X2);
    assertThat(chain.accessGateService.currentLogisticianScope().memberOrgUnitIds())
        .containsExactlyInAnyOrder(BEREICH_X, STAFFEL_X1, SK_X2);
  }

  @Test
  void pinnedToAUnitBelowTheGrantTheLogisticianActsThereOnly() {
    Chain chain =
        new Chain(Roles.LOGISTICIAN, Set.of(BEREICH_X), false, SK_X2, Set.of(BEREICH_X, SK_X2));

    assertThat(unitsWhere(chain.accessGateService::canActAsLogisticianIn)).containsExactly(SK_X2);
    assertThat(chain.accessGateService.currentLogisticianOrgUnitIds()).containsExactly(SK_X2);
  }

  @Test
  void pinnedToAnOwnUnitOutsideTheGrantTheLogisticianActsNowhere() {
    Chain chain =
        new Chain(
            Roles.LOGISTICIAN, Set.of(BEREICH_X), false, STAFFEL_Y1, Set.of(BEREICH_X, STAFFEL_Y1));

    assertThat(unitsWhere(chain.accessGateService::canActAsLogisticianIn)).isEmpty();
    assertThat(chain.accessGateService.currentLogisticianOrgUnitIds()).isEmpty();
  }

  @Test
  void aBereichPinIsTheBereichAloneNotTheUnitsBelowIt() {
    Chain chain =
        new Chain(Roles.LOGISTICIAN, Set.of(BEREICH_X), false, BEREICH_X, Set.of(BEREICH_X));

    assertThat(unitsWhere(chain.accessGateService::canActAsLogisticianIn))
        .containsExactly(BEREICH_X);
    assertThat(
            unitsWhere(unit -> chain.accessGateService.hasRoleInOrgUnit(unit, Roles.LOGISTICIAN)))
        .containsExactly(BEREICH_X);
  }

  @Test
  void aPinnedAdminActsInThePinnedUnitOnly() {
    Chain chain = new Chain(Roles.MISSION_MANAGER, Set.of(), true, STAFFEL_X1);

    assertThat(
            unitsWhere(
                unit -> chain.accessGateService.hasRoleInOrgUnit(unit, Roles.MISSION_MANAGER)))
        .containsExactly(STAFFEL_X1);
    assertThat(chain.accessGateService.canManageMissionsOf(STAFFEL_Y1)).isFalse();
    assertThat(chain.accessGateService.canManageMissionsOf(null)).isFalse();
  }

  @Test
  void aPinOutsideTheCallersUnitsIsNoPinAndTheGrantCoversItsWholeCascade() {
    Chain chain =
        new Chain(Roles.LOGISTICIAN, Set.of(BEREICH_X), false, STAFFEL_Y1, Set.of(BEREICH_X));

    assertThat(unitsWhere(chain.accessGateService::canActAsLogisticianIn))
        .containsExactlyInAnyOrder(BEREICH_X, STAFFEL_X1, SK_X2);
    assertThat(chain.accessGateService.currentLogisticianOrgUnitIds())
        .containsExactlyInAnyOrder(BEREICH_X, STAFFEL_X1, SK_X2);
  }

  @Test
  void theMissionManagerFlagFollowsThePinThroughTheCascade() {
    Set<UUID> memberOf = Set.of(BEREICH_X, STAFFEL_X1, STAFFEL_Y1);
    assertThat(
            new Chain(Roles.MISSION_MANAGER, Set.of(BEREICH_X), false, STAFFEL_X1, memberOf)
                .requestScopeResolver.canActAsMissionManagerInActiveContext())
        .isTrue();
    assertThat(
            new Chain(Roles.MISSION_MANAGER, Set.of(BEREICH_X), false, STAFFEL_Y1, memberOf)
                .requestScopeResolver.canActAsMissionManagerInActiveContext())
        .isFalse();
    assertThat(
            new Chain(Roles.MISSION_MANAGER, Set.of(BEREICH_X), false, null, memberOf)
                .requestScopeResolver.canActAsMissionManagerInActiveContext())
        .isTrue();
  }

  @Test
  void aMissionManagerBelowTheBereichManagesItsMissionsButNotAnOwnerlessOne() {
    Chain bereich = new Chain(Roles.MISSION_MANAGER, Set.of(BEREICH_X), false, null);
    Chain staffel = new Chain(Roles.MISSION_MANAGER, Set.of(STAFFEL_X1), false, null);

    assertThat(bereich.accessGateService.canManageMissionsOf(SK_X2)).isTrue();
    assertThat(bereich.accessGateService.canManageMissionsOf(STAFFEL_Y1)).isFalse();
    assertThat(bereich.accessGateService.canManageMissionsOf(null)).isTrue();
    assertThat(staffel.accessGateService.canManageMissionsOf(null)).isFalse();
  }

  @Test
  void anAdminPassesEveryUnitWithoutAnyGrant() {
    Chain chain = new Chain(Roles.LOGISTICIAN, Set.of(), true, null);

    assertThat(
            unitsWhere(unit -> chain.accessGateService.hasRoleInOrgUnit(unit, Roles.LOGISTICIAN)))
        .containsExactlyInAnyOrderElementsOf(ALL);
  }
}
