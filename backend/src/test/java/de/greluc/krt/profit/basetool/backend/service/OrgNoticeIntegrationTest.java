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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.identity.api.events.MemberDepartedEvent;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import de.greluc.krt.profit.basetool.backend.model.Notification;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.notification.api.events.OrgUnitRef;
import de.greluc.krt.profit.basetool.backend.orgunit.api.events.OrgNotices;
import de.greluc.krt.profit.basetool.backend.repository.NotificationRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * The organisation notices end to end against Postgres (REQ-ORG-029, -030): the seat queries, the
 * mismatch reaching the admins until the roles fit, and the departure reaching the leadership of
 * the member's unit and not the member. Runs in a rolled-back transaction.
 */
@SpringBootTest
@Transactional
class OrgNoticeIntegrationTest {

  private static final UUID ADMIN = UUID.fromString("44444444-4444-4444-4444-4444444492c1");
  private static final UUID LEADER = UUID.fromString("44444444-4444-4444-4444-4444444492c2");
  private static final UUID MEMBER = UUID.fromString("44444444-4444-4444-4444-4444444492c3");

  @Autowired private NotificationCreationService creationService;
  @Autowired private NotificationRepository notificationRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private OrgUnitMembershipRepository membershipRepository;

  private Squadron squadron;

  @BeforeEach
  void seed() {
    Role admin = roleRepository.findByNameIgnoreCase("Admin").orElseThrow();
    Role officer = roleRepository.findByNameIgnoreCase("Officer").orElseThrow();
    user(ADMIN, "org-admin", Set.of(admin));
    user(LEADER, "org-leader", Set.of(officer));
    user(MEMBER, "org-member", Set.of());
    squadron = new Squadron();
    squadron.setName("Org Notice " + UUID.randomUUID());
    squadron.setShorthand("ON" + UUID.randomUUID().toString().substring(0, 6));
    squadron = squadronRepository.saveAndFlush(squadron);
    membership(LEADER, MembershipRole.STAFFELLEITER);
    membership(MEMBER, MembershipRole.MEMBER);
  }

  private void user(UUID id, String name, Set<Role> roles) {
    User user = new User();
    user.setId(id);
    user.setUsername(name);
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    user.setRoles(new HashSet<>(roles));
    userRepository.saveAndFlush(user);
  }

  private void membership(UUID userId, MembershipRole role) {
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(userId, squadron.getId()));
    membership.setUser(userRepository.findById(userId).orElseThrow());
    membership.setKind(OrgUnitKind.SQUADRON);
    membership.setRole(role);
    membership.setJoinedAt(Instant.now());
    membershipRepository.saveAndFlush(membership);
  }

  private List<NotificationType> inbox(UUID member) {
    return notificationRepository
        .findAllByRecipientUserId(member, PageRequest.of(0, 50))
        .map(Notification::getType)
        .getContent();
  }

  @Test
  void theSeatQueryTellsLeadersFromPlainMembers() {
    assertThat(membershipRepository.existsLeadershipSeat(LEADER)).isTrue();
    assertThat(membershipRepository.existsLeadershipSeat(MEMBER)).isFalse();
  }

  @Test
  void theRoleQueryFindsHeldRolesOnly() {
    assertThat(userRepository.hasAnyRoleCode(LEADER, List.of("OFFICER"))).isTrue();
    assertThat(userRepository.hasAnyRoleCode(MEMBER, List.of("OFFICER", "ADMIN"))).isFalse();
    assertThat(userRepository.hasAnyRoleCode(ADMIN, List.of("ADMIN"))).isTrue();
  }

  @Test
  void aMismatchReachesTheAdminsAndGoesWhenTheRolesFit() {
    creationService.createFromEvent(
        OrgNotices.leadershipRoleMismatch(
            MEMBER, "org-member", "IRI", "APPOINTED", "ENSIGN", "MISSING_OFFICER", LEADER));
    assertThat(inbox(ADMIN)).containsExactly(NotificationType.ORG_LEADERSHIP_ROLE_MISMATCH);

    creationService.createFromEvent(OrgNotices.leadershipRolesAgree(MEMBER));

    assertThat(inbox(ADMIN)).isEmpty();
  }

  @Test
  void aSecondMismatchOfTheSameMemberReplacesTheFirst() {
    creationService.createFromEvent(
        OrgNotices.leadershipRoleMismatch(
            MEMBER, "org-member", "IRI", "APPOINTED", "ENSIGN", "MISSING_OFFICER", null));
    creationService.createFromEvent(
        OrgNotices.leadershipRoleMismatch(
            MEMBER, "org-member", "IRI", "REMOVED", "ENSIGN", "SURPLUS_OFFICER", null));

    assertThat(inbox(ADMIN)).containsExactly(NotificationType.ORG_LEADERSHIP_ROLE_MISMATCH);
  }

  @Test
  void aDepartureReachesTheLeadershipOfTheMembersUnitButNotTheMember() {
    creationService.createFromEvent(
        OrgNotices.memberDeparted(
            MEMBER,
            "org-member",
            new OrgUnitRef(squadron.getId(), OrgUnitKind.SQUADRON),
            "IRI",
            MemberDepartedEvent.REASON_DISABLED,
            false));

    assertThat(inbox(LEADER)).containsExactly(NotificationType.ORG_MEMBER_DEPARTED);
    assertThat(inbox(MEMBER)).isEmpty();
  }
}
