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

import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.Role;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Bereich;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Department;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipRole;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitCascadeService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Organisationsleitung;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrganisationsleitungRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.annotation.Transactional;

/**
 * A membership on a deactivated org unit confers nothing until the unit is reactivated
 * (REQ-ORG-032), against Postgres: a {@code KRT Member} who holds the Logistician and Mission
 * Manager flags and the Staffelleiter seat on the deactivated Argon, and the Logistician flag on
 * the active Hel, reaches, holds rights in and is notified for Hel alone, and no leadership cascade
 * reaches Argon.
 */
@SpringBootTest
@Transactional
class DeactivatedOrgUnitConfersNothingIntegrationTest {

  @Autowired private OrgUnitRepository orgUnitRepository;
  @Autowired private OrganisationsleitungRepository organisationsleitungRepository;
  @Autowired private OrgUnitMembershipRepository membershipRepository;
  @Autowired private OrgUnitMembershipQueryService membershipQueryService;
  @Autowired private OrgUnitCascadeService cascadeService;
  @Autowired private CustomJwtGrantedAuthoritiesConverter authoritiesConverter;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;

  private Bereich area;
  private Squadron hel;
  private Squadron argon;
  private User member;

  @BeforeEach
  void seed() {
    String token = UUID.randomUUID().toString().substring(0, 8);
    area = new Bereich();
    area.setName("Deactivation area " + token);
    area.setShorthand("A" + token);
    area.setDepartment(Department.PROFIT);
    orgUnitRepository.save(area);
    hel = squadron("Hel " + token, "H" + token, true);
    argon = squadron("Argon " + token, "R" + token, false);
    member = krtMember();
    membership(member, hel, MembershipRole.MEMBER, true, false);
    membership(member, argon, MembershipRole.STAFFELLEITER, true, true);
  }

  @Test
  void theMembershipReadsLeaveTheDeactivatedUnitOut() {
    assertThat(membershipRepository.findAllOnActiveOrgUnitsByIdUserId(member.getId()))
        .extracting(m -> m.getId().getOrgUnitId())
        .containsExactly(hel.getId());
    assertThat(membershipRepository.findActiveOrgUnitIdsByUserId(member.getId()))
        .containsExactly(hel.getId());
    assertThat(membershipQueryService.findDirectMembershipOrgUnitIds(member.getId()))
        .containsExactly(hel.getId());
    assertThat(membershipQueryService.listDirectMembershipOptions(member.getId()))
        .extracting(OrgUnitMembershipOptionDto::orgUnitId)
        .containsExactly(hel.getId());
    assertThat(membershipQueryService.findLogisticianOrgUnitIds(member.getId()))
        .containsExactly(hel.getId());
  }

  @Test
  void theDeactivatedUnitsFlagsAndSeatMintNoAuthority() {
    assertThat(authoritiesConverter.assembleFor(member))
        .contains(
            new OrgUnitContextualAuthority(Roles.LOGISTICIAN, hel.getId()),
            new SimpleGrantedAuthority(Roles.authority(Roles.LOGISTICIAN)))
        .doesNotContain(
            new OrgUnitContextualAuthority(Roles.LOGISTICIAN, argon.getId()),
            new OrgUnitContextualAuthority(Roles.MISSION_MANAGER, argon.getId()),
            new SimpleGrantedAuthority(Roles.authority(Roles.MISSION_MANAGER)));
  }

  @Test
  void noLeadershipCascadeReachesTheDeactivatedUnit() {
    OrgUnitMembership bereichsleitung =
        membership(krtMember(), area, MembershipRole.BEREICHSLEITER, false, false);

    assertThat(cascadeService.cascadedOfficerReach(List.of(bereichsleitung)))
        .contains(area.getId(), hel.getId())
        .doesNotContain(argon.getId());
    assertThat(cascadeService.unitsBelow(Set.of(area.getId())))
        .contains(hel.getId())
        .doesNotContain(argon.getId());
    Organisationsleitung organisationsleitung =
        organisationsleitungRepository.findAll().stream()
            .findFirst()
            .orElseGet(() -> {
              Organisationsleitung created = new Organisationsleitung();
              created.setName("Deactivation OL " + area.getShorthand());
              created.setShorthand("O" + area.getShorthand());
              return organisationsleitungRepository.save(created);
            });
    assertThat(cascadeService.unitsBelow(Set.of(organisationsleitung.getId())))
        .contains(hel.getId())
        .doesNotContain(argon.getId());
  }

  @Test
  void theDeactivatedUnitsFlagsAndSeatNotifyNobody() {
    assertThat(membershipRepository.findLogisticianUserIdsByOrgUnit(hel.getId()))
        .contains(member.getId());
    assertThat(membershipRepository.findLogisticianUserIdsByOrgUnit(argon.getId())).isEmpty();
    assertThat(membershipRepository.findMissionManagerUserIdsByOrgUnit(argon.getId())).isEmpty();
    assertThat(membershipRepository.findLeadershipUserIdsByOrgUnit(argon.getId())).isEmpty();
    assertThat(
            membershipRepository.findUserIdsByActiveOrgUnitIdsAndRole(
                List.of(argon.getId()), MembershipRole.STAFFELLEITER))
        .isEmpty();
    assertThat(
            membershipRepository.findUserIdsByOrgUnitAndRole(
                argon.getId(), MembershipRole.STAFFELLEITER))
        .as("the stored seat stays for the holder audit and a reactivation")
        .contains(member.getId());
    assertThat(membershipRepository.findUserIdsByRole(MembershipRole.STAFFELLEITER))
        .doesNotContain(member.getId());
    assertThat(membershipRepository.findAllLeadershipUserIds()).doesNotContain(member.getId());
  }

  private Squadron squadron(String name, String shorthand, boolean active) {
    Squadron squadron = new Squadron();
    squadron.setName(name);
    squadron.setShorthand(shorthand);
    squadron.setParent(area);
    squadron.setActive(active);
    return orgUnitRepository.save(squadron);
  }

  private User krtMember() {
    Role krtMember = roleRepository.findByCode(Roles.KRT_MEMBER).orElseThrow();
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("deactivation-it-" + user.getId());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    user.setRoles(new HashSet<>(Set.of(krtMember)));
    return userRepository.saveAndFlush(user);
  }

  private OrgUnitMembership membership(
      User user,
      OrgUnit orgUnit,
      MembershipRole role,
      boolean logistician,
      boolean missionManager) {
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(user.getId(), orgUnit.getId()));
    membership.setUser(user);
    membership.setRole(role);
    membership.setLogistician(logistician);
    membership.setMissionManager(missionManager);
    membership.setJoinedAt(Instant.now());
    return membershipRepository.saveAndFlush(membership);
  }
}
