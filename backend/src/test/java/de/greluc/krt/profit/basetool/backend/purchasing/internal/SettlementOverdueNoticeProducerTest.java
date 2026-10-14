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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.notification.api.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipRole;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;

/** The overdue-settlement notice, raised once per deal by an atomic marker (REQ-PUR-018). */
@ExtendWith(MockitoExtension.class)
class SettlementOverdueNoticeProducerTest {

  private static final Instant NOW = Instant.parse("2026-10-14T08:00:00Z");
  private static final UUID AREA = UUID.randomUUID();
  private static final UUID DELEGATE = UUID.randomUUID();
  private static final UUID LEADER = UUID.randomUUID();
  private static final UUID COORDINATOR = UUID.randomUUID();

  @Mock private PurchasingDealRepository dealRepository;
  @Mock private PurchasingSettingsService settingsService;
  @Mock private OrgUnitMembershipRepository membershipRepository;
  @Mock private ApplicationEventPublisher eventPublisher;

  private SettlementOverdueNoticeProducer producer;

  @BeforeEach
  void setUp() {
    producer =
        new SettlementOverdueNoticeProducer(
            dealRepository, settingsService, membershipRepository, eventPublisher);
  }

  @Test
  void theKindNamesTheNoticeForTheProducedCounter() {
    assertThat(producer.kind()).isEqualTo("purchasing_settlement_overdue");
  }

  @Test
  void nothingIsAnnouncedWhileThePurchasingSurfaceIsOff() {
    when(settingsService.isEnabled()).thenReturn(false);

    assertThat(producer.produce(NOW, 10)).isZero();

    verifyNoInteractions(dealRepository);
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void onlyTheDealsWhoseMarkerUpdateChangedTheRowAreAnnounced() {
    PurchasingDeal first = deal(7L);
    PurchasingDeal lost = deal(8L);
    PurchasingDeal third = deal(9L);
    when(settingsService.isEnabled()).thenReturn(true);
    when(settingsService.governingAreaIds()).thenReturn(List.of(AREA));
    when(membershipRepository.findUserIdsByOrgUnitIdsAndRole(
            List.of(AREA), MembershipRole.BEREICHSLEITER))
        .thenReturn(Set.of(LEADER));
    when(membershipRepository.findUserIdsByOrgUnitIdsAndRole(
            List.of(AREA), MembershipRole.BEREICHSKOORDINATOR))
        .thenReturn(Set.of(COORDINATOR));
    when(dealRepository.findOverdueNotYetAnnouncedIds(eq(NOW), any(Limit.class)))
        .thenReturn(List.of(first.getId(), lost.getId(), third.getId()));
    when(dealRepository.markOverdueNotified(first.getId(), NOW)).thenReturn(1);
    when(dealRepository.markOverdueNotified(lost.getId(), NOW)).thenReturn(0);
    when(dealRepository.markOverdueNotified(third.getId(), NOW)).thenReturn(1);
    when(dealRepository.findById(first.getId())).thenReturn(Optional.of(first));
    when(dealRepository.findById(third.getId())).thenReturn(Optional.of(third));

    int raised = producer.produce(NOW, 5);

    assertThat(raised).isEqualTo(2);
    ArgumentCaptor<Limit> limit = ArgumentCaptor.forClass(Limit.class);
    verify(dealRepository).findOverdueNotYetAnnouncedIds(eq(NOW), limit.capture());
    assertThat(limit.getValue().max()).isEqualTo(5);
    verify(dealRepository, never()).findById(lost.getId());
    ArgumentCaptor<NoticeEvent> notices = ArgumentCaptor.forClass(NoticeEvent.class);
    verify(eventPublisher, times(2)).publishEvent(notices.capture());
    assertThat(notices.getAllValues())
        .extracting(NoticeEvent::entityId)
        .containsExactly(first.getId(), third.getId());
    NoticeEvent notice = notices.getAllValues().getFirst();
    assertThat(notice.eventType()).isEqualTo(NotificationEventType.PURCHASING_SETTLEMENT_OVERDUE);
    assertThat(notice.contextRecipientUserIds())
        .containsExactlyInAnyOrder(DELEGATE, LEADER, COORDINATOR);
    assertThat(notice.renderParams()).containsEntry("deal", "7");
  }

  @Test
  void aDealThatVanishedAfterTheMarkerIsNotCounted() {
    UUID id = UUID.randomUUID();
    when(settingsService.isEnabled()).thenReturn(true);
    when(settingsService.governingAreaIds()).thenReturn(List.of());
    when(dealRepository.findOverdueNotYetAnnouncedIds(eq(NOW), any(Limit.class)))
        .thenReturn(List.of(id));
    when(dealRepository.markOverdueNotified(id, NOW)).thenReturn(1);
    when(dealRepository.findById(id)).thenReturn(Optional.empty());

    assertThat(producer.produce(NOW, 5)).isZero();

    verifyNoInteractions(eventPublisher);
  }

  @Test
  void withoutAGoverningAreaOnlyTheDelegateIsListed() {
    PurchasingDeal deal = deal(3L);
    when(settingsService.isEnabled()).thenReturn(true);
    when(settingsService.governingAreaIds()).thenReturn(List.of());
    when(dealRepository.findOverdueNotYetAnnouncedIds(eq(NOW), any(Limit.class)))
        .thenReturn(List.of(deal.getId()));
    when(dealRepository.markOverdueNotified(deal.getId(), NOW)).thenReturn(1);
    when(dealRepository.findById(deal.getId())).thenReturn(Optional.of(deal));

    assertThat(producer.produce(NOW, 5)).isEqualTo(1);

    ArgumentCaptor<NoticeEvent> notice = ArgumentCaptor.forClass(NoticeEvent.class);
    verify(eventPublisher).publishEvent(notice.capture());
    assertThat(notice.getValue().contextRecipientUserIds()).containsExactly(DELEGATE);
    verifyNoInteractions(membershipRepository);
  }

  private static PurchasingDeal deal(long no) {
    PurchasingDeal deal = new PurchasingDeal();
    deal.setId(UUID.randomUUID());
    deal.setDealNo(no);
    deal.setDelegateUserId(DELEGATE);
    return deal;
  }
}
