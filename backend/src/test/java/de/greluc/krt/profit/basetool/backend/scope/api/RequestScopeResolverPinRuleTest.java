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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipRole;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitCascadeService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The one pin rule of the scope kernel (REQ-ORG-032, ADR-0374): a pin counts only when the caller
 * may select its unit, every surface treats any other pin as no pin, and the effective context the
 * layout reports names one unit of any kind, all own units, every unit or none (REQ-ORG-024).
 */
@ExtendWith(MockitoExtension.class)
class RequestScopeResolverPinRuleTest {

  private static final UUID USER = UUID.randomUUID();
  private static final UUID HEL = UUID.randomUUID();
  private static final UUID ARGON = UUID.randomUUID();
  private static final UUID FOREIGN = UUID.randomUUID();

  @Mock private AuthHelperService authHelper;
  @Mock private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Mock private OrgUnitRepository orgUnitRepository;
  @Mock private OrgUnitCascadeService orgUnitCascadeService;
  @Mock private HttpServletRequest request;

  private RequestScopeResolver resolver;

  @BeforeEach
  void setUp() {
    lenient().when(authHelper.isAuthenticated()).thenReturn(true);
    lenient().when(authHelper.currentUserId()).thenReturn(Optional.of(USER));
    lenient().when(authHelper.holdsKrtMember()).thenReturn(true);
    lenient()
        .when(orgUnitCascadeService.expandWithDescendants(anyCollection()))
        .thenAnswer(invocation -> directIds(invocation.getArgument(0)));
    lenient().when(orgUnitRepository.existsByIdAndActiveTrue(any())).thenReturn(true);
    resolver =
        new RequestScopeResolver(
            authHelper,
            orgUnitMembershipRepository,
            orgUnitRepository,
            orgUnitCascadeService,
            request);
  }

  private static Set<UUID> directIds(Collection<OrgUnitMembership> memberships) {
    Set<UUID> ids = new LinkedHashSet<>();
    for (OrgUnitMembership m : memberships) {
      ids.add(m.getId().getOrgUnitId());
    }
    return ids;
  }

  private static OrgUnitMembership membership(UUID orgUnitId, OrgUnitKind kind) {
    OrgUnitMembership m = new OrgUnitMembership();
    m.setId(new OrgUnitMembershipId(USER, orgUnitId));
    m.setKind(kind);
    m.setRole(MembershipRole.MEMBER);
    return m;
  }

  private void memberOf(OrgUnitMembership... memberships) {
    when(authHelper.isAdmin()).thenReturn(false);
    when(orgUnitMembershipRepository.findAllOnActiveOrgUnitsByIdUserId(USER))
        .thenReturn(List.of(memberships));
  }

  private void memberOfHelAndArgon() {
    memberOf(membership(HEL, OrgUnitKind.SQUADRON), membership(ARGON, OrgUnitKind.SPECIAL_COMMAND));
  }

  private void pin(UUID orgUnitId) {
    when(request.getHeader(RequestScopeResolver.ACTIVE_ORG_UNIT_HEADER))
        .thenReturn(orgUnitId == null ? null : orgUnitId.toString());
  }

  @Nested
  class HonouredPin {

    @Test
    void aMembersPinOnASpecialCommandOfTheirReachIsHonoured() {
      memberOfHelAndArgon();
      pin(ARGON);

      assertThat(resolver.currentPin()).contains(ARGON);
      assertThat(resolver.currentScopePredicate())
          .isEqualTo(new ScopePredicate(false, ARGON, Set.of()));
    }

    @Test
    void aMembersPinOutsideTheirReachCountsAsNoPinOnEverySurface() {
      memberOfHelAndArgon();
      pin(FOREIGN);

      assertThat(resolver.currentPin()).isEmpty();
      assertThat(resolver.currentScopePredicate())
          .isEqualTo(new ScopePredicate(false, null, Set.of(HEL, ARGON)));
      assertThat(resolver.narrowToPin(Set.of(HEL, ARGON))).containsExactlyInAnyOrder(HEL, ARGON);
      assertThat(resolver.currentActiveContext()).isEqualTo(ActiveOrgUnitContext.ALL_MY_ORG_UNITS);
    }

    @Test
    void anAdminsPinOnAnActiveUnitIsHonoured() {
      when(authHelper.isAdmin()).thenReturn(true);
      when(orgUnitRepository.existsByIdAndActiveTrue(ARGON)).thenReturn(true);
      pin(ARGON);

      assertThat(resolver.currentPin()).contains(ARGON);
      assertThat(resolver.currentScopePredicate())
          .isEqualTo(new ScopePredicate(false, ARGON, Set.of()));
    }

    @Test
    void anAdminsPinOnAnUnknownOrInactiveUnitCountsAsNoPin() {
      when(authHelper.isAdmin()).thenReturn(true);
      when(orgUnitRepository.existsByIdAndActiveTrue(FOREIGN)).thenReturn(false);
      pin(FOREIGN);

      assertThat(resolver.currentPin()).isEmpty();
      assertThat(resolver.currentScopePredicate().adminAllScope()).isTrue();
      assertThat(resolver.currentActiveContext()).isEqualTo(ActiveOrgUnitContext.ALL_ORG_UNITS);
    }

    @Test
    void aMalformedPinCountsAsNoPin() {
      when(request.getHeader(RequestScopeResolver.ACTIVE_ORG_UNIT_HEADER)).thenReturn("not-a-uuid");

      assertThat(resolver.currentPin()).isEmpty();
    }
  }

  @Nested
  class EffectiveContext {

    @Test
    void anUnpinnedMemberOfSeveralUnitsIsInAllMyOrgUnits() {
      memberOfHelAndArgon();
      pin(null);

      assertThat(resolver.currentActiveContext()).isEqualTo(ActiveOrgUnitContext.ALL_MY_ORG_UNITS);
    }

    @Test
    void anUnpinnedMemberOfOneUnitIsInThatUnit() {
      memberOf(membership(ARGON, OrgUnitKind.SPECIAL_COMMAND));
      pin(null);

      assertThat(resolver.currentActiveContext()).isEqualTo(ActiveOrgUnitContext.orgUnit(ARGON));
    }

    @Test
    void aPinOnTheSpecialCommandIsTheContextNotTheHomeStaffel() {
      memberOfHelAndArgon();
      pin(ARGON);

      assertThat(resolver.currentActiveContext()).isEqualTo(ActiveOrgUnitContext.orgUnit(ARGON));
    }

    @Test
    void aMemberOfNoUnitHasNoContext() {
      memberOf();
      pin(null);

      assertThat(resolver.currentActiveContext()).isEqualTo(ActiveOrgUnitContext.NONE);
    }

    @Test
    void anUnpinnedAdminIsInAllOrgUnits() {
      when(authHelper.isAdmin()).thenReturn(true);
      pin(null);

      assertThat(resolver.currentActiveContext()).isEqualTo(ActiveOrgUnitContext.ALL_ORG_UNITS);
    }

    @Test
    void theCurrentOrgUnitIsThePinnedUnitOfAnyKind() {
      memberOfHelAndArgon();
      pin(ARGON);
      SpecialCommand argon = new SpecialCommand();
      argon.setId(ARGON);
      when(orgUnitRepository.findById(ARGON)).thenReturn(Optional.of(argon));

      Optional<OrgUnit> current = resolver.currentOrgUnit();

      assertThat(current).containsSame(argon);
    }

    @Test
    void thereIsNoCurrentOrgUnitInAllMyOrgUnits() {
      memberOfHelAndArgon();
      pin(null);

      assertThat(resolver.currentOrgUnit()).isEmpty();
    }
  }

  @Nested
  class Selectable {

    @Test
    void aMemberMaySelectTheUnitsOfTheirReachOnly() {
      memberOfHelAndArgon();

      assertThat(resolver.isSelectable(HEL)).isTrue();
      assertThat(resolver.isSelectable(ARGON)).isTrue();
      assertThat(resolver.isSelectable(FOREIGN)).isFalse();
    }

    @Test
    void aDeactivatedUnitOfTheReachIsNotSelectable() {
      memberOfHelAndArgon();
      when(orgUnitRepository.existsByIdAndActiveTrue(ARGON)).thenReturn(false);

      assertThat(resolver.isSelectable(ARGON)).isFalse();
    }

    @Test
    void withoutKrtMemberNoMembershipReachesAnyUnit() {
      UUID bereich = UUID.randomUUID();
      UUID staffelBelow = UUID.randomUUID();
      OrgUnitMembership seat = membership(bereich, OrgUnitKind.BEREICH);
      seat.setRole(MembershipRole.BEREICHSLEITER);
      when(authHelper.isAdmin()).thenReturn(false);
      lenient()
          .when(orgUnitMembershipRepository.findAllOnActiveOrgUnitsByIdUserId(USER))
          .thenReturn(List.of(seat));
      when(authHelper.holdsKrtMember()).thenReturn(false);
      lenient()
          .when(orgUnitCascadeService.expandWithDescendants(anyCollection()))
          .thenReturn(Set.of(bereich, staffelBelow));

      assertThat(resolver.currentMemberOrgUnitIds()).isEmpty();
      assertThat(resolver.isSelectable(bereich)).isFalse();
      assertThat(resolver.isSelectable(staffelBelow)).isFalse();
    }

    @Test
    void anAdminMaySelectEveryActiveUnit() {
      when(authHelper.isAdmin()).thenReturn(true);
      when(orgUnitRepository.existsByIdAndActiveTrue(FOREIGN)).thenReturn(true);

      assertThat(resolver.isSelectable(FOREIGN)).isTrue();
    }

    @Test
    void aBereichPinNarrowsTheCascadedReachToTheBereichAlone() {
      UUID bereich = UUID.randomUUID();
      UUID staffelBelow = UUID.randomUUID();
      OrgUnitMembership seat = membership(bereich, OrgUnitKind.BEREICH);
      seat.setRole(MembershipRole.BEREICHSLEITER);
      memberOf(seat);
      when(orgUnitCascadeService.expandWithDescendants(anyCollection()))
          .thenReturn(Set.of(bereich, staffelBelow));
      pin(bereich);

      assertThat(resolver.narrowToPin(Set.of(bereich, staffelBelow))).containsExactly(bereich);
      assertThat(resolver.isInActiveContext(staffelBelow)).isFalse();
    }

    @Test
    void aPinLeavesOnlyThePinnedUnitInTheActiveContext() {
      memberOfHelAndArgon();
      pin(ARGON);

      assertThat(resolver.isInActiveContext(ARGON)).isTrue();
      assertThat(resolver.isInActiveContext(HEL)).isFalse();
    }

    @Test
    void withoutAnHonouredPinEveryUnitIsInTheActiveContext() {
      memberOfHelAndArgon();
      pin(FOREIGN);

      assertThat(resolver.isInActiveContext(HEL)).isTrue();
      assertThat(resolver.isInActiveContext(FOREIGN)).isTrue();
    }
  }
}
