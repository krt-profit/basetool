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
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.notification.api.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.purchasing.api.DealStatus;
import de.greluc.krt.profit.basetool.backend.supply.api.NeedRef;
import de.greluc.krt.profit.basetool.backend.supply.api.NeedStatus;
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

/**
 * What purchasing does when the supply module releases, withdraws or fulfils a need (REQ-PUR-020,
 * REQ-PUR-024).
 */
@ExtendWith(MockitoExtension.class)
class PurchasingNeedObserverTest {

  private static final UUID NEED = UUID.randomUUID();
  private static final UUID UNIT = UUID.randomUUID();
  private static final UUID ACTOR = UUID.randomUUID();

  @Mock private PurchasingDelegateRepository delegateRepository;
  @Mock private PurchasingDealRepository dealRepository;
  @Mock private PurchasingDealService dealService;
  @Mock private PurchasingSettingsService settingsService;
  @Mock private OrgUnitRepository orgUnitRepository;
  @Mock private ApplicationEventPublisher eventPublisher;

  private PurchasingNeedObserver observer;

  @BeforeEach
  void setUp() {
    observer =
        new PurchasingNeedObserver(
            delegateRepository,
            dealRepository,
            dealService,
            settingsService,
            orgUnitRepository,
            eventPublisher);
  }

  @Test
  void aReleasedNeedIsAnnouncedToTheActiveDelegates() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    Squadron unit = new Squadron();
    unit.setId(UNIT);
    unit.setName("Iridium");
    when(settingsService.isEnabled()).thenReturn(true);
    when(delegateRepository.findActiveUserIds(any(Instant.class)))
        .thenReturn(List.of(first, second));
    when(orgUnitRepository.findById(UNIT)).thenReturn(Optional.of(unit));

    observer.onReleasedForPurchase(need(10.0, 4.0), ACTOR);

    NoticeEvent notice = publishedNotice();
    assertThat(notice.eventType()).isEqualTo(NotificationEventType.PURCHASING_NEED_RELEASED);
    assertThat(notice.entityId()).isEqualTo(NEED);
    assertThat(notice.actorSub()).isEqualTo(ACTOR);
    assertThat(notice.contextRecipientUserIds()).containsExactlyInAnyOrder(first, second);
    assertThat(notice.renderParams())
        .containsEntry("unit", "Iridium")
        .containsEntry("entry", "Quantanium")
        .containsEntry("amount", "6");
  }

  @Test
  void aReleasedNeedIsNotAnnouncedWhenNoDelegateIsActive() {
    when(settingsService.isEnabled()).thenReturn(true);
    when(delegateRepository.findActiveUserIds(any(Instant.class))).thenReturn(List.of());

    observer.onReleasedForPurchase(need(10.0, 0.0), ACTOR);

    verifyNoInteractions(eventPublisher);
    verifyNoInteractions(orgUnitRepository);
  }

  @Test
  void aReleasedNeedIsNotAnnouncedWhileTheSurfaceIsOff() {
    when(settingsService.isEnabled()).thenReturn(false);

    observer.onReleasedForPurchase(need(10.0, 0.0), ACTOR);

    verifyNoInteractions(delegateRepository);
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void aWithdrawnNeedCancelsItsDealsWithoutMoneyAndRetractsTheNotices() {
    PurchasingDeal draft = deal(DealStatus.DRAFT);
    PurchasingDeal submitted = deal(DealStatus.SUBMITTED);
    PurchasingDeal lockedDraft = deal(DealStatus.DRAFT);
    lockedDraft.setId(draft.getId());
    PurchasingDeal lockedSubmitted = deal(DealStatus.SUBMITTED);
    lockedSubmitted.setId(submitted.getId());
    when(dealRepository.findByNeedIdAndStatusIn(
            eq(NEED), eq(List.of(DealStatus.DRAFT, DealStatus.SUBMITTED))))
        .thenReturn(List.of(draft, submitted));
    when(dealService.lock(draft.getId())).thenReturn(lockedDraft);
    when(dealService.lock(submitted.getId())).thenReturn(lockedSubmitted);
    when(settingsService.isEnabled()).thenReturn(true);

    observer.onWithdrawn(NEED);

    verify(dealService).cancelWithinTransaction(same(lockedDraft), eq("NEED_WITHDRAWN"));
    verify(dealService).cancelWithinTransaction(same(lockedSubmitted), eq("NEED_WITHDRAWN"));
    NoticeEvent notice = publishedNotice();
    assertThat(notice.eventType()).isEqualTo(NotificationEventType.PURCHASING_NEED_CLOSED);
    assertThat(notice.entityId()).isEqualTo(NEED);
    assertThat(notice.resolvesNotificationTypes())
        .isEqualTo(Set.of(NotificationType.PURCHASING_NEED_RELEASED));
  }

  @Test
  void aWithdrawnNeedStillCancelsItsDealsWhileTheSurfaceIsOff() {
    PurchasingDeal draft = deal(DealStatus.DRAFT);
    when(dealRepository.findByNeedIdAndStatusIn(
            eq(NEED), eq(List.of(DealStatus.DRAFT, DealStatus.SUBMITTED))))
        .thenReturn(List.of(draft));
    when(dealService.lock(draft.getId())).thenReturn(draft);
    when(settingsService.isEnabled()).thenReturn(false);

    observer.onWithdrawn(NEED);

    verify(dealService).cancelWithinTransaction(same(draft), eq("NEED_WITHDRAWN"));
    verify(eventPublisher, never()).publishEvent(any(Object.class));
  }

  @Test
  void aFulfilledNeedRetractsItsReleaseNotices() {
    when(settingsService.isEnabled()).thenReturn(true);

    observer.onFulfilled(NEED);

    NoticeEvent notice = publishedNotice();
    assertThat(notice.eventType()).isEqualTo(NotificationEventType.PURCHASING_NEED_CLOSED);
    assertThat(notice.entityId()).isEqualTo(NEED);
    assertThat(notice.resolvesNotificationTypes())
        .containsExactly(NotificationType.PURCHASING_NEED_RELEASED);
  }

  @Test
  void aFulfilledNeedPublishesNothingWhileTheSurfaceIsOff() {
    when(settingsService.isEnabled()).thenReturn(false);

    observer.onFulfilled(NEED);

    verifyNoInteractions(eventPublisher);
  }

  private static NeedRef need(double amount, double fulfilled) {
    return new NeedRef(
        NEED,
        UNIT,
        UUID.randomUUID(),
        null,
        null,
        0,
        "Quantanium",
        false,
        amount,
        fulfilled,
        0.0,
        true,
        null,
        NeedStatus.OPEN);
  }

  private static PurchasingDeal deal(DealStatus status) {
    PurchasingDeal deal = new PurchasingDeal();
    deal.setId(UUID.randomUUID());
    deal.setNeedId(NEED);
    deal.setStatus(status);
    return deal;
  }

  private NoticeEvent publishedNotice() {
    ArgumentCaptor<NoticeEvent> notice = ArgumentCaptor.forClass(NoticeEvent.class);
    verify(eventPublisher).publishEvent(notice.capture());
    return notice.getValue();
  }
}
