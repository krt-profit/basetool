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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.identity.api.events.MemberDepartedEvent;
import de.greluc.krt.profit.basetool.backend.model.Bereich;
import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import de.greluc.krt.profit.basetool.backend.model.NotificationContextRole;
import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.List;
import java.util.Optional;
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
 * The departure notice (REQ-ORG-030): the leadership of every unit the departed member belonged to
 * hears it, the parent Bereich too when a seat became vacant, and the member themselves does not.
 */
@ExtendWith(MockitoExtension.class)
class OrgUnitDepartureNotifierTest {

  private static final UUID USER = UUID.randomUUID();

  @Mock private OrgUnitMembershipRepository membershipRepository;
  @Mock private OrgUnitRepository orgUnitRepository;
  @Mock private UserRepository userRepository;
  @Mock private ApplicationEventPublisher eventPublisher;

  @InjectMocks private OrgUnitDepartureNotifier notifier;

  private Bereich bereich;
  private Squadron squadron;

  @BeforeEach
  void setUp() {
    User member = new User();
    member.setId(USER);
    member.setUsername("ada");
    org.mockito.Mockito.lenient()
        .when(userRepository.findPlainById(USER))
        .thenReturn(Optional.of(member));
    bereich = new Bereich();
    bereich.setId(UUID.randomUUID());
    bereich.setName("Nord");
    squadron = new Squadron();
    squadron.setId(UUID.randomUUID());
    squadron.setName("Iridium");
    squadron.setShorthand("IRI");
    squadron.setParent(bereich);
  }

  private OrgUnitMembership membership(UUID unitId, MembershipRole role) {
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(USER, unitId));
    membership.setRole(role);
    return membership;
  }

  private List<NoticeEvent> published(int expected) {
    ArgumentCaptor<Object> captured = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher, times(expected)).publishEvent(captured.capture());
    return captured.getAllValues().stream().map(NoticeEvent.class::cast).toList();
  }

  @Test
  void aPlainMemberOnlyTellsTheLeadershipOfTheirUnit() {
    when(membershipRepository.findAllByIdUserId(USER))
        .thenReturn(List.of(membership(squadron.getId(), MembershipRole.MEMBER)));
    when(orgUnitRepository.findAllById(List.of(squadron.getId()))).thenReturn(List.of(squadron));

    notifier.onDeparture(new MemberDepartedEvent(USER, MemberDepartedEvent.REASON_DISABLED));

    NoticeEvent notice = published(1).getFirst();
    assertThat(notice.eventType()).isEqualTo(NotificationEventType.ORG_MEMBER_DEPARTED);
    assertThat(notice.actorSub()).isEqualTo(USER);
    assertThat(notice.contextOrgUnits().get(NotificationContextRole.RESPONSIBLE).id())
        .isEqualTo(squadron.getId());
    assertThat(notice.renderParams())
        .containsEntry("member", "ada")
        .containsEntry("unit", "IRI")
        .containsEntry("reasonCode", "disabled")
        .containsEntry("vacancyCode", "NO");
  }

  @Test
  void aSeatHolderAlsoTellsTheParentBereich() {
    when(membershipRepository.findAllByIdUserId(USER))
        .thenReturn(List.of(membership(squadron.getId(), MembershipRole.STAFFELLEITER)));
    when(orgUnitRepository.findAllById(List.of(squadron.getId()))).thenReturn(List.of(squadron));

    notifier.onDeparture(new MemberDepartedEvent(USER, MemberDepartedEvent.REASON_ROLE_LOST));

    List<NoticeEvent> sent = published(2);
    assertThat(sent.get(0).renderParams()).containsEntry("vacancyCode", "YES");
    assertThat(sent.get(1).contextOrgUnits().get(NotificationContextRole.RESPONSIBLE).id())
        .isEqualTo(bereich.getId());
    assertThat(sent.get(1).renderParams()).containsEntry("unit", "Nord");
  }

  @Test
  void aMemberWithoutMembershipsTellsNobody() {
    when(membershipRepository.findAllByIdUserId(USER)).thenReturn(List.of());

    notifier.onDeparture(new MemberDepartedEvent(USER, MemberDepartedEvent.REASON_REMOVED));

    verify(eventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.any(Object.class));
  }
}
