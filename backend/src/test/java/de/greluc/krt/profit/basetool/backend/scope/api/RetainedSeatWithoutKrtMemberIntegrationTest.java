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

import de.greluc.krt.profit.basetool.backend.identity.api.Role;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Bereich;
import de.greluc.krt.profit.basetool.backend.orgunit.api.BereichRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipRole;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * A leadership seat whose holder lost {@code KRT Member} confers no officer, Logistician or Mission
 * Manager reach, through the real converter, cascade, resolver and gates (REQ-SEC-015, ADR-0300);
 * the explicit grant flags stay in force.
 */
@SpringBootTest
class RetainedSeatWithoutKrtMemberIntegrationTest {

  @Autowired private CustomJwtGrantedAuthoritiesConverter converter;
  @Autowired private AccessGateService accessGateService;
  @Autowired private RequestScopeResolver requestScopeResolver;
  @Autowired private OrgUnitMembershipQueryService membershipQueryService;
  @Autowired private RoleRepository roleRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private BereichRepository bereichRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;

  private OrgUnit bereich;
  private OrgUnit squadron;

  /** What a caller reaches in one unit. */
  private record Reach(boolean officer, boolean missionManager, boolean logistician) {}

  @BeforeEach
  void seedUnits() {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    Bereich area = new Bereich();
    area.setName("Seat-Bereich-" + tag);
    area.setShorthand("SB" + tag);
    bereich = bereichRepository.save(area);
    Squadron staffel = new Squadron();
    staffel.setName("Seat-Staffel-" + tag);
    staffel.setShorthand("SS" + tag);
    staffel.setProfitEligible(true);
    staffel.setParent(area);
    squadron = squadronRepository.save(staffel);
  }

  @AfterEach
  void clearContexts() {
    SecurityContextHolder.clearContext();
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void aSquadronSeatWithKrtMemberReachesItsUnitAsOfficerMissionManagerAndLogistician() {
    User holder = holder(squadron, MembershipRole.STAFFELLEITER, false, false);

    assertThat(reachOf(holder, true, squadron)).isEqualTo(new Reach(true, true, true));
  }

  @Test
  void aSquadronSeatWithoutKrtMemberConfersNoReachAtAll() {
    User holder = holder(squadron, MembershipRole.STAFFELLEITER, false, false);

    assertThat(reachOf(holder, false, squadron)).isEqualTo(new Reach(false, false, false));
  }

  @Test
  void anAreaSeatWithKrtMemberReachesTheUnitBelowIt() {
    User holder = holder(bereich, MembershipRole.BEREICHSLEITER, false, false);

    assertThat(reachOf(holder, true, squadron)).isEqualTo(new Reach(true, true, true));
  }

  @Test
  void anAreaSeatWithoutKrtMemberReachesNeitherItsBereichNorTheUnitBelowIt() {
    User holder = holder(bereich, MembershipRole.BEREICHSLEITER, false, false);

    assertThat(reachOf(holder, false, squadron)).isEqualTo(new Reach(false, false, false));
    assertThat(reachOf(holder, false, bereich)).isEqualTo(new Reach(false, false, false));
  }

  @Test
  void explicitFlagsOnAPlainMembershipStayInForceWithoutKrtMember() {
    User logistician = holder(squadron, MembershipRole.MEMBER, true, false);
    User missionManager = holder(squadron, MembershipRole.MEMBER, false, true);

    assertThat(reachOf(logistician, false, squadron)).isEqualTo(new Reach(false, false, true));
    assertThat(reachOf(missionManager, false, squadron)).isEqualTo(new Reach(false, true, false));
  }

  @Test
  void explicitFlagsOnARetainedSeatStayInForceButTheRankAddsNothingWithoutKrtMember() {
    User holder = holder(squadron, MembershipRole.STAFFELLEITER, true, false);

    assertThat(reachOf(holder, false, squadron)).isEqualTo(new Reach(false, false, true));
  }

  @Test
  void theOversightSurfacesAndTheLeitungScopeOpenForASeatOnlyWithKrtMember() {
    User holder = holder(squadron, MembershipRole.STAFFELLEITER, false, false);

    authenticate(holder, true);
    assertThat(requestScopeResolver.canAccessBlueprintOverview()).isTrue();
    assertThat(requestScopeResolver.currentOversightScope().memberOrgUnitIds())
        .contains(squadron.getId());

    authenticate(holder, false);
    assertThat(requestScopeResolver.canAccessBlueprintOverview()).isFalse();
    assertThat(requestScopeResolver.currentOversightScope().memberOrgUnitIds()).isEmpty();
  }

  @Test
  @Transactional
  void theExchangeLogisticianUnitsFollowTheSameRule() {
    User seat = holder(bereich, MembershipRole.BEREICHSLEITER, false, false);
    User flagged = holder(squadron, MembershipRole.MEMBER, true, false);

    assertThat(membershipQueryService.findLogisticianOrgUnitIds(seat.getId())).isEmpty();
    assertThat(membershipQueryService.findLogisticianOrgUnitIds(flagged.getId()))
        .containsExactly(squadron.getId());

    withStoredKrtMember(seat);
    assertThat(membershipQueryService.findLogisticianOrgUnitIds(seat.getId()))
        .contains(bereich.getId(), squadron.getId());
  }

  private void withStoredKrtMember(User user) {
    user.getRoles().add(roleRepository.findByCodeIgnoreCase(Roles.KRT_MEMBER).orElseThrow());
    userRepository.save(user);
  }

  private Reach reachOf(User holder, boolean krtMember, OrgUnit unit) {
    authenticate(holder, krtMember);
    return new Reach(
        accessGateService.canActAsOfficerIn(unit.getId()),
        accessGateService.canManageMissionsOf(unit.getId()),
        accessGateService.canActAsLogisticianIn(unit.getId()));
  }

  private void authenticate(User holder, boolean krtMember) {
    User view = new User();
    view.setId(holder.getId());
    view.setApprovalStatus(holder.getApprovalStatus());
    view.setRoles(krtMember ? Set.of(codeRole(Roles.KRT_MEMBER)) : Set.of());
    List<GrantedAuthority> authorities = new ArrayList<>(converter.assembleFor(view));
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(
                Jwt.withTokenValue("retained-seat")
                    .header("alg", "none")
                    .subject(holder.getId().toString())
                    .build(),
                authorities));
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
  }

  private User holder(
      OrgUnit unit, MembershipRole rank, boolean logistician, boolean missionManager) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("retained-seat-" + user.getId());
    user = userRepository.save(user);
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(user.getId(), unit.getId()));
    membership.setUser(user);
    membership.setKind(unit.getKind());
    membership.setRole(rank);
    membership.setLogistician(logistician);
    membership.setMissionManager(missionManager);
    membership.setJoinedAt(Instant.now());
    orgUnitMembershipRepository.save(membership);
    return user;
  }

  private static Role codeRole(String code) {
    Role role = new Role();
    role.setCode(code);
    role.setName(code);
    role.setPermissions(Set.of());
    return role;
  }
}
