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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
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
 * The leadership role-mismatch notice (REQ-ORG-029): a seat without OFFICER and OFFICER without a
 * seat are reported to the admins, a fitting role set or an admin is not, and a reconciliation that
 * makes the roles fit clears the notice.
 */
@ExtendWith(MockitoExtension.class)
class OrgUnitSeatNotifierTest {

  private static final UUID USER = UUID.randomUUID();

  @Mock private OrgUnitMembershipRepository membershipRepository;
  @Mock private UserRepository userRepository;
  @Mock private ApplicationEventPublisher eventPublisher;

  @InjectMocks private OrgUnitSeatNotifier notifier;

  private User member;

  @BeforeEach
  void setUp() {
    member = new User();
    member.setId(USER);
    member.setUsername("ada");
    org.mockito.Mockito.lenient()
        .when(userRepository.findPlainById(USER))
        .thenReturn(Optional.of(member));
  }

  private NoticeEvent published() {
    ArgumentCaptor<Object> captured = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher).publishEvent(captured.capture());
    return (NoticeEvent) captured.getValue();
  }

  private void roles(boolean officer, boolean admin) {
    when(userRepository.hasAnyRoleCode(USER, List.of("OFFICER"))).thenReturn(officer);
    when(userRepository.hasAnyRoleCode(USER, List.of("ADMIN"))).thenReturn(admin);
  }

  private static Role role(String code) {
    Role role = new Role();
    role.setCode(code);
    return role;
  }

  @Test
  void aNewSeatWithoutTheOfficerRoleIsReportedAsMissing() {
    when(membershipRepository.existsLeadershipSeat(USER)).thenReturn(true);
    roles(false, false);

    notifier.seatChanged(USER, "IRI", MembershipRole.STAFFELLEITER, true, null);

    NoticeEvent notice = published();
    assertThat(notice.eventType()).isEqualTo(NotificationEventType.ORG_LEADERSHIP_ROLE_MISMATCH);
    assertThat(notice.entityId()).isEqualTo(USER);
    assertThat(notice.renderParams())
        .containsEntry("member", "ada")
        .containsEntry("unit", "IRI")
        .containsEntry("seatCode", "APPOINTED")
        .containsEntry("rankCode", "STAFFELLEITER")
        .containsEntry("mismatchCode", "MISSING_OFFICER");
  }

  @Test
  void aVacatedLastSeatWithTheOfficerRoleLeftIsReportedAsSurplus() {
    when(membershipRepository.existsLeadershipSeat(USER)).thenReturn(false);
    roles(true, false);

    notifier.seatChanged(USER, "IRI", MembershipRole.STAFFELLEITER, false, null);

    NoticeEvent notice = published();
    assertThat(notice.renderParams())
        .containsEntry("seatCode", "REMOVED")
        .containsEntry("mismatchCode", "SURPLUS_OFFICER");
  }

  @Test
  void aSeatWithTheOfficerRoleClearsAnEarlierMismatch() {
    when(membershipRepository.existsLeadershipSeat(USER)).thenReturn(true);
    roles(true, false);

    notifier.seatChanged(USER, "IRI", MembershipRole.ENSIGN, true, null);

    assertThat(published().eventType())
        .isEqualTo(NotificationEventType.ORG_LEADERSHIP_ROLE_MISMATCH_CLEARED);
  }

  @Test
  void anAdminNeverMismatches() {
    when(membershipRepository.existsLeadershipSeat(USER)).thenReturn(true);
    roles(false, true);

    notifier.seatChanged(USER, "IRI", MembershipRole.OL_MEMBER, true, null);

    assertThat(published().eventType())
        .isEqualTo(NotificationEventType.ORG_LEADERSHIP_ROLE_MISMATCH_CLEARED);
  }

  @Test
  void aMemberWithoutSeatAndWithoutOfficerNeverMismatches() {
    when(membershipRepository.existsLeadershipSeat(USER)).thenReturn(false);
    roles(false, false);

    notifier.seatChanged(USER, "IRI", MembershipRole.ENSIGN, false, null);

    assertThat(published().eventType())
        .isEqualTo(NotificationEventType.ORG_LEADERSHIP_ROLE_MISMATCH_CLEARED);
  }

  @Test
  void aReconciliationThatMakesTheRolesFitClearsTheNotice() {
    when(membershipRepository.existsLeadershipSeat(USER)).thenReturn(true);

    notifier.onRolesChanged(USER, Set.of(role("OFFICER")));

    assertThat(published().eventType())
        .isEqualTo(NotificationEventType.ORG_LEADERSHIP_ROLE_MISMATCH_CLEARED);
  }

  @Test
  void aReconciliationThatLeavesTheRolesOutOfStepKeepsTheNotice() {
    when(membershipRepository.existsLeadershipSeat(USER)).thenReturn(true);

    notifier.onRolesChanged(USER, Set.of(role("KRT_MEMBER")));

    verify(eventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.any(Object.class));
  }
}
