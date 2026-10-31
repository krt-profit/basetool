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
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitCascadeService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.StaffelMembershipResolver;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority;
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
 * the Organisationsleitung covers every unit, a grant in a Staffel covers that Staffel, and a pin
 * must lie in the covered set. The grants are the contextual authorities the JWT converter mints
 * for a direct flag, which name the granting unit alone.
 */
class GrantCascadeTest {

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

    final OwnerScopeService scope;

    Chain(String role, Set<UUID> grants, boolean admin, UUID pin) {
      AuthHelperService authHelper = mock(AuthHelperService.class);
      when(authHelper.isAdmin()).thenReturn(admin);
      when(authHelper.isAuthenticated()).thenReturn(true);
      List<GrantedAuthority> authorities =
          grants.stream()
              .<GrantedAuthority>map(unit -> new OrgUnitContextualAuthority(role, unit))
              .toList();
      when(authHelper.currentAuthentication())
          .thenReturn(
              Optional.of(new UsernamePasswordAuthenticationToken("caller", "n/a", authorities)));

      OrgUnitRepository orgUnits = mock(OrgUnitRepository.class);
      when(orgUnits.findOrganisationsleitungIdsAmong(anyCollection()))
          .thenAnswer(call ->
              ((java.util.Collection<UUID>) call.getArgument(0))
                  .stream().filter(OL::equals).toList());
      when(orgUnits.findAllOrgUnitIds()).thenReturn(ALL);
      when(orgUnits.findChildOrgUnitIdsIn(anyCollection()))
          .thenAnswer(call ->
              ((java.util.Collection<UUID>) call.getArgument(0))
                  .stream()
                      .flatMap(parent -> CHILDREN.getOrDefault(parent, List.of()).stream())
                      .toList());
      when(orgUnits.findLeadershipOrgUnitIdsAmong(anyCollection()))
          .thenAnswer(call ->
              ((java.util.Collection<UUID>) call.getArgument(0))
                  .stream()
                      .filter(id -> id.equals(OL) || id.equals(BEREICH_X) || id.equals(BEREICH_Y))
                      .toList());

      MockHttpServletRequest request = new MockHttpServletRequest();
      if (pin != null) {
        request.addHeader(RequestScopeResolver.ACTIVE_ORG_UNIT_HEADER, pin.toString());
      }
      RequestScopeResolver resolver =
          new RequestScopeResolver(
              authHelper,
              mock(OrgUnitMembershipRepository.class),
              orgUnits,
              new OrgUnitCascadeService(orgUnits),
              new StaffelMembershipResolver(mock(SquadronRepository.class), orgUnits),
              request);
      scope =
          new OwnerScopeService(
              resolver,
              new AccessGateService(resolver, authHelper),
              mock(OrgUnitStampingService.class));
    }
  }

  private static Set<UUID> unitsWhere(Predicate<UUID> gate) {
    return ALL.stream().filter(gate).collect(java.util.stream.Collectors.toSet());
  }

  @ParameterizedTest
  @ValueSource(strings = {Roles.LOGISTICIAN, Roles.MISSION_MANAGER})
  void aBereichGrantCoversTheBereichItsStaffelnAndItsSpezialkommandosOnly(String role) {
    Chain chain = new Chain(role, Set.of(BEREICH_X), false, null);

    assertThat(unitsWhere(unit -> chain.scope.hasRoleInOrgUnit(unit, role)))
        .containsExactlyInAnyOrder(BEREICH_X, STAFFEL_X1, SK_X2);
  }

  @ParameterizedTest
  @ValueSource(strings = {Roles.LOGISTICIAN, Roles.MISSION_MANAGER})
  void anOrganisationsleitungGrantCoversEveryUnit(String role) {
    Chain chain = new Chain(role, Set.of(OL), false, null);

    assertThat(unitsWhere(unit -> chain.scope.hasRoleInOrgUnit(unit, role)))
        .containsExactlyInAnyOrderElementsOf(ALL);
  }

  @ParameterizedTest
  @ValueSource(strings = {Roles.LOGISTICIAN, Roles.MISSION_MANAGER})
  void aStaffelGrantCoversThatStaffelOnly(String role) {
    Chain chain = new Chain(role, Set.of(STAFFEL_X1), false, null);

    assertThat(unitsWhere(unit -> chain.scope.hasRoleInOrgUnit(unit, role)))
        .containsExactly(STAFFEL_X1);
  }

  @Test
  void aGrantOfTheOtherRoleCoversNothing() {
    Chain chain = new Chain(Roles.MISSION_MANAGER, Set.of(OL), false, null);

    assertThat(unitsWhere(unit -> chain.scope.hasRoleInOrgUnit(unit, Roles.LOGISTICIAN))).isEmpty();
  }

  @Test
  void theLogisticianGateAndListScopeFollowTheCascadeUnpinned() {
    Chain chain = new Chain(Roles.LOGISTICIAN, Set.of(BEREICH_X), false, null);

    assertThat(unitsWhere(chain.scope::canActAsLogisticianIn))
        .containsExactlyInAnyOrder(BEREICH_X, STAFFEL_X1, SK_X2);
    assertThat(chain.scope.currentLogisticianOrgUnitIds())
        .containsExactlyInAnyOrder(BEREICH_X, STAFFEL_X1, SK_X2);
    assertThat(chain.scope.currentLogisticianScope().memberOrgUnitIds())
        .containsExactlyInAnyOrder(BEREICH_X, STAFFEL_X1, SK_X2);
  }

  @Test
  void pinnedToAUnitBelowTheGrantTheLogisticianActsThereOnly() {
    Chain chain = new Chain(Roles.LOGISTICIAN, Set.of(BEREICH_X), false, SK_X2);

    assertThat(unitsWhere(chain.scope::canActAsLogisticianIn)).containsExactly(SK_X2);
    assertThat(chain.scope.currentLogisticianOrgUnitIds()).containsExactly(SK_X2);
  }

  @Test
  void pinnedOutsideTheGrantTheLogisticianActsNowhere() {
    Chain chain = new Chain(Roles.LOGISTICIAN, Set.of(BEREICH_X), false, STAFFEL_Y1);

    assertThat(unitsWhere(chain.scope::canActAsLogisticianIn)).isEmpty();
    assertThat(chain.scope.currentLogisticianOrgUnitIds()).isEmpty();
  }

  @Test
  void theMissionManagerFlagFollowsThePinThroughTheCascade() {
    assertThat(
            new Chain(Roles.MISSION_MANAGER, Set.of(BEREICH_X), false, STAFFEL_X1)
                .scope.canActAsMissionManagerInActiveContext())
        .isTrue();
    assertThat(
            new Chain(Roles.MISSION_MANAGER, Set.of(BEREICH_X), false, STAFFEL_Y1)
                .scope.canActAsMissionManagerInActiveContext())
        .isFalse();
    assertThat(
            new Chain(Roles.MISSION_MANAGER, Set.of(BEREICH_X), false, null)
                .scope.canActAsMissionManagerInActiveContext())
        .isTrue();
  }

  @Test
  void aMissionManagerBelowTheBereichManagesItsMissionsButNotAnOwnerlessOne() {
    Chain bereich = new Chain(Roles.MISSION_MANAGER, Set.of(BEREICH_X), false, null);
    Chain staffel = new Chain(Roles.MISSION_MANAGER, Set.of(STAFFEL_X1), false, null);

    assertThat(bereich.scope.canManageMissionsOf(SK_X2)).isTrue();
    assertThat(bereich.scope.canManageMissionsOf(STAFFEL_Y1)).isFalse();
    assertThat(bereich.scope.canManageMissionsOf(null)).isTrue();
    assertThat(staffel.scope.canManageMissionsOf(null)).isFalse();
  }

  @Test
  void anAdminPassesEveryUnitWithoutAnyGrant() {
    Chain chain = new Chain(Roles.LOGISTICIAN, Set.of(), true, null);

    assertThat(unitsWhere(unit -> chain.scope.hasRoleInOrgUnit(unit, Roles.LOGISTICIAN)))
        .containsExactlyInAnyOrderElementsOf(ALL);
  }
}
