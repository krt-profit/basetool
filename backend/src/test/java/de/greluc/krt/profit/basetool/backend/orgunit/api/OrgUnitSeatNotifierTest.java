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

package de.greluc.krt.profit.basetool.backend.orgunit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.identity.api.Role;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The "seat holder without {@code KRT Member}" notice (REQ-ORG-029): a seat holder without the role
 * is reported to the admins, whether a seat changed or a role reconciliation took the role away,
 * and the notice clears once the member holds the role again or no seat any more.
 */
@ExtendWith(MockitoExtension.class)
class OrgUnitSeatNotifierTest {

  private static final UUID USER = UUID.randomUUID();
  private static final UUID UNIT = UUID.randomUUID();

  @Mock private OrgUnitMembershipRepository membershipRepository;
  @Mock private OrgUnitRepository orgUnitRepository;
  @Mock private UserRepository userRepository;
  @Mock private ApplicationEventPublisher eventPublisher;

  @InjectMocks private OrgUnitSeatNotifier notifier;

  @BeforeEach
  void setUp() {
    User member = new User();
    member.setId(USER);
    member.setUsername("ada");
    lenient().when(userRepository.findPlainById(USER)).thenReturn(Optional.of(member));
  }

  private NoticeEvent published() {
    ArgumentCaptor<Object> captured = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher).publishEvent(captured.capture());
    return (NoticeEvent) captured.getValue();
  }

  private void krtMember(boolean holdsRole) {
    when(userRepository.hasAnyRoleCode(USER, List.of("KRT_MEMBER"))).thenReturn(holdsRole);
  }

  private void holdsSeatIn(String shorthand, MembershipRole rank) {
    OrgUnitMembership plain = new OrgUnitMembership();
    plain.setId(new OrgUnitMembershipId(USER, UUID.randomUUID()));
    plain.setRole(MembershipRole.MEMBER);
    OrgUnitMembership seat = new OrgUnitMembership();
    seat.setId(new OrgUnitMembershipId(USER, UNIT));
    seat.setRole(rank);
    when(membershipRepository.findAllByIdUserId(USER)).thenReturn(List.of(plain, seat));
    OrgUnit unit = new Squadron();
    unit.setShorthand(shorthand);
    when(orgUnitRepository.findById(UNIT)).thenReturn(Optional.of(unit));
  }

  private static Role role(String code) {
    Role role = new Role();
    role.setCode(code);
    return role;
  }

  @Test
  void aSeatHolderWithoutKrtMemberIsReportedAfterASeatChange() {
    when(membershipRepository.existsLeadershipSeat(USER)).thenReturn(true);
    krtMember(false);

    notifier.seatChanged(USER, "ALP", MembershipRole.STAFFELLEITER, true, null);

    NoticeEvent notice = published();
    assertThat(notice.eventType()).isEqualTo(NotificationEventType.ORG_LEADERSHIP_ROLE_MISMATCH);
    assertThat(notice.entityId()).isEqualTo(USER);
    assertThat(notice.renderParams())
        .containsEntry("member", "ada")
        .containsEntry("unit", "ALP")
        .containsEntry("seatCode", "APPOINTED")
        .containsEntry("rankCode", "STAFFELLEITER")
        .containsEntry("mismatchCode", "MISSING_KRT_MEMBER");
  }

  @Test
  void aRemovedSeatWithAnotherSeatLeftAndNoKrtMemberIsStillReported() {
    when(membershipRepository.existsLeadershipSeat(USER)).thenReturn(true);
    krtMember(false);

    notifier.seatChanged(USER, "ALP", MembershipRole.ENSIGN, false, null);

    assertThat(published().renderParams())
        .containsEntry("seatCode", "REMOVED")
        .containsEntry("mismatchCode", "MISSING_KRT_MEMBER");
  }

  @Test
  void aSeatHolderWithKrtMemberClearsAnEarlierNotice() {
    when(membershipRepository.existsLeadershipSeat(USER)).thenReturn(true);
    krtMember(true);

    notifier.seatChanged(USER, "ALP", MembershipRole.ENSIGN, true, null);

    assertThat(published().eventType())
        .isEqualTo(NotificationEventType.ORG_LEADERSHIP_ROLE_MISMATCH_CLEARED);
  }

  @Test
  void aRemovedLastSeatClearsTheNoticeEvenWithoutKrtMember() {
    when(membershipRepository.existsLeadershipSeat(USER)).thenReturn(false);

    notifier.seatChanged(USER, "ALP", MembershipRole.STAFFELLEITER, false, null);

    assertThat(published().eventType())
        .isEqualTo(NotificationEventType.ORG_LEADERSHIP_ROLE_MISMATCH_CLEARED);
  }

  @Test
  void anAdminSeatHolderWithoutKrtMemberIsReportedToo() {
    when(membershipRepository.existsLeadershipSeat(USER)).thenReturn(true);
    krtMember(false);

    notifier.seatChanged(USER, "OL", MembershipRole.OL_MEMBER, true, null);

    assertThat(published().eventType())
        .isEqualTo(NotificationEventType.ORG_LEADERSHIP_ROLE_MISMATCH);
  }

  @Test
  void aSeatHolderWhoLosesKrtMemberInAReconciliationIsReported() {
    holdsSeatIn("ALP", MembershipRole.STAFFELLEITER);

    notifier.onRolesChanged(USER, Set.of(role("BANK_STAFF")));

    NoticeEvent notice = published();
    assertThat(notice.eventType()).isEqualTo(NotificationEventType.ORG_LEADERSHIP_ROLE_MISMATCH);
    assertThat(notice.actorSub()).isNull();
    assertThat(notice.renderParams())
        .containsEntry("member", "ada")
        .containsEntry("unit", "ALP")
        .containsEntry("seatCode", "ROLES_RECONCILED")
        .containsEntry("rankCode", "STAFFELLEITER")
        .containsEntry("mismatchCode", "MISSING_KRT_MEMBER");
  }

  @Test
  void aReconciliationThatRestoresKrtMemberClearsTheNotice() {
    notifier.onRolesChanged(USER, Set.of(role("KRT_MEMBER")));

    assertThat(published().eventType())
        .isEqualTo(NotificationEventType.ORG_LEADERSHIP_ROLE_MISMATCH_CLEARED);
  }

  @Test
  void aReconciliationOfAMemberWithoutSeatAndWithoutKrtMemberClearsTheNotice() {
    OrgUnitMembership plain = new OrgUnitMembership();
    plain.setId(new OrgUnitMembershipId(USER, UNIT));
    plain.setRole(MembershipRole.MEMBER);
    when(membershipRepository.findAllByIdUserId(USER)).thenReturn(List.of(plain));

    notifier.onRolesChanged(USER, Set.of());

    assertThat(published().eventType())
        .isEqualTo(NotificationEventType.ORG_LEADERSHIP_ROLE_MISMATCH_CLEARED);
    verify(orgUnitRepository, never()).findById(any());
  }
}
