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

package de.greluc.krt.profit.basetool.backend.purchasing.api.events;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationContextRole;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import de.greluc.krt.profit.basetool.backend.notification.api.events.OrgUnitRef;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The purchasing notices carry names, numbers and codes only, and name who is told and which
 * earlier notices they retract (REQ-PUR-024, REQ-PUR-025).
 */
class PurchasingNoticesTest {

  private static final UUID ENTITY = UUID.randomUUID();
  private static final UUID UNIT = UUID.randomUUID();
  private static final UUID ACTOR = UUID.randomUUID();
  private static final UUID MEMBER = UUID.randomUUID();

  @Test
  void aReleasedNeedGoesToTheActiveDelegates() {
    UUID delegate = UUID.randomUUID();

    NoticeEvent notice =
        PurchasingNotices.needReleased(
            ENTITY, "Alpha", "Quantanium", "12.5", Set.of(delegate), ACTOR);

    assertThat(notice.eventType()).isEqualTo(NotificationEventType.PURCHASING_NEED_RELEASED);
    assertThat(notice.entityType()).isEqualTo(PurchasingNotices.NEED_ENTITY_TYPE);
    assertThat(notice.entityId()).isEqualTo(ENTITY);
    assertThat(notice.actorSub()).isEqualTo(ACTOR);
    assertThat(notice.contextRecipientUserIds()).containsExactly(delegate);
    assertThat(notice.renderParams())
        .containsOnlyKeys("unit", "entry", "amount")
        .containsEntry("unit", "Alpha")
        .containsEntry("entry", "Quantanium")
        .containsEntry("amount", "12.5");
  }

  @Test
  void aClosedNeedRetractsItsReleaseNoticeAndTellsNobody() {
    NoticeEvent notice = PurchasingNotices.needClosed(ENTITY);

    assertThat(notice.eventType()).isEqualTo(NotificationEventType.PURCHASING_NEED_CLOSED);
    assertThat(notice.entityType()).isEqualTo(PurchasingNotices.NEED_ENTITY_TYPE);
    assertThat(notice.resolvesNotificationTypes())
        .containsExactly(NotificationType.PURCHASING_NEED_RELEASED);
    assertThat(notice.contextRecipientUserIds()).isEmpty();
    assertThat(notice.renderParams()).isEmpty();
  }

  @Test
  void aRecordedDeliveryGoesToTheRaisingUnit() {
    NoticeEvent notice =
        PurchasingNotices.deliveryRecorded(
            ENTITY, 42L, UNIT, OrgUnitKind.SQUADRON, "Alpha", "Quantanium", "4", ACTOR);

    assertThat(notice.eventType()).isEqualTo(NotificationEventType.PURCHASING_DELIVERY_RECORDED);
    assertThat(notice.entityType()).isEqualTo(PurchasingNotices.DEAL_ENTITY_TYPE);
    assertThat(notice.actorSub()).isEqualTo(ACTOR);
    assertThat(notice.contextOrgUnits())
        .containsOnlyKeys(NotificationContextRole.RESPONSIBLE)
        .containsEntry(
            NotificationContextRole.RESPONSIBLE, new OrgUnitRef(UNIT, OrgUnitKind.SQUADRON));
    assertThat(notice.renderParams())
        .containsOnlyKeys("deal", "unit", "entry", "amount")
        .containsEntry("deal", "42");
  }

  @Test
  void aDecidedDeliveryTellsTheDelegateAndRetractsTheRecordedNotice() {
    NoticeEvent notice =
        PurchasingNotices.deliveryDecided(ENTITY, 42L, MEMBER, "PARTIALLY_ACCEPTED", ACTOR);

    assertThat(notice.eventType()).isEqualTo(NotificationEventType.PURCHASING_DELIVERY_DECIDED);
    assertThat(notice.contextRecipientUserId()).isEqualTo(MEMBER);
    assertThat(notice.actorSub()).isEqualTo(ACTOR);
    assertThat(notice.renderParams())
        .containsOnlyKeys("deal", "outcomeCode")
        .containsEntry("outcomeCode", "PARTIALLY_ACCEPTED");
    assertThat(notice.resolvesNotificationTypes())
        .containsExactly(NotificationType.PURCHASING_DELIVERY_RECORDED);
  }

  @Test
  void anOverdueSettlementListsTheDelegateAndTheGoverningLeadership() {
    UUID leader = UUID.randomUUID();
    UUID coordinator = UUID.randomUUID();

    NoticeEvent notice =
        PurchasingNotices.settlementOverdue(ENTITY, 42L, MEMBER, Set.of(leader, coordinator));

    assertThat(notice.eventType()).isEqualTo(NotificationEventType.PURCHASING_SETTLEMENT_OVERDUE);
    assertThat(notice.entityType()).isEqualTo(PurchasingNotices.DEAL_ENTITY_TYPE);
    assertThat(notice.actorSub()).isNull();
    assertThat(notice.contextRecipientUserIds())
        .containsExactlyInAnyOrder(MEMBER, leader, coordinator);
    assertThat(notice.renderParams()).containsOnlyKeys("deal").containsEntry("deal", "42");
  }

  @Test
  void anOverdueSettlementWithoutADelegateOrALeadershipListsNobody() {
    NoticeEvent notice = PurchasingNotices.settlementOverdue(ENTITY, 42L, null, Set.of());

    assertThat(notice.contextRecipientUserIds()).isEmpty();
  }

  @Test
  void aGlobalPauseTellsTheDelegatesWithoutAnyUnit() {
    UUID delegate = UUID.randomUUID();

    NoticeEvent notice =
        PurchasingNotices.paused(
            PurchasingNotices.GLOBAL_PAUSE_ID, null, null, null, "BUDGET", Set.of(delegate), ACTOR);

    assertThat(notice.eventType()).isEqualTo(NotificationEventType.PURCHASING_PAUSED);
    assertThat(notice.entityType()).isEqualTo(PurchasingNotices.PAUSE_ENTITY_TYPE);
    assertThat(notice.entityId()).isEqualTo(PurchasingNotices.GLOBAL_PAUSE_ID);
    assertThat(notice.contextRecipientUserIds()).containsExactly(delegate);
    assertThat(notice.contextOrgUnits()).isEmpty();
    assertThat(notice.renderParams())
        .containsOnlyKeys("scopeCode", "reasonCode")
        .containsEntry("scopeCode", PurchasingNotices.SCOPE_GLOBAL)
        .containsEntry("reasonCode", "BUDGET");
    assertThat(notice.resolvesNotificationTypes()).isEmpty();
  }

  @Test
  void aUnitPauseNamesTheUnitAndAddsItsLeadershipAsContext() {
    NoticeEvent notice =
        PurchasingNotices.paused(
            UNIT, UNIT, OrgUnitKind.SQUADRON, "Alpha", "MANUAL", Set.of(), ACTOR);

    assertThat(notice.entityId()).isEqualTo(UNIT);
    assertThat(notice.contextOrgUnits())
        .containsEntry(
            NotificationContextRole.RESPONSIBLE, new OrgUnitRef(UNIT, OrgUnitKind.SQUADRON));
    assertThat(notice.renderParams())
        .containsEntry("scopeCode", PurchasingNotices.SCOPE_UNIT)
        .containsEntry("unit", "Alpha")
        .containsEntry("reasonCode", "MANUAL");
  }

  @Test
  void aResumeRetractsThePauseNoticeAndCarriesNoReason() {
    UUID delegate = UUID.randomUUID();

    NoticeEvent notice =
        PurchasingNotices.resumed(
            PurchasingNotices.GLOBAL_PAUSE_ID, null, null, null, Set.of(delegate), ACTOR);

    assertThat(notice.eventType()).isEqualTo(NotificationEventType.PURCHASING_RESUMED);
    assertThat(notice.resolvesNotificationTypes())
        .containsExactly(NotificationType.PURCHASING_PAUSED);
    assertThat(notice.renderParams())
        .containsOnlyKeys("scopeCode")
        .containsEntry("scopeCode", PurchasingNotices.SCOPE_GLOBAL);
    assertThat(notice.contextRecipientUserIds()).containsExactly(delegate);
  }

  @Test
  void anAppointmentTellsTheMemberAndARevocationRetractsIt() {
    NoticeEvent appointed = PurchasingNotices.delegateAppointed(ENTITY, MEMBER, ACTOR);
    NoticeEvent revoked = PurchasingNotices.delegateRevoked(ENTITY, MEMBER, ACTOR);

    assertThat(appointed.eventType())
        .isEqualTo(NotificationEventType.PURCHASING_DELEGATE_APPOINTED);
    assertThat(appointed.entityType()).isEqualTo(PurchasingNotices.DELEGATE_ENTITY_TYPE);
    assertThat(appointed.contextRecipientUserId()).isEqualTo(MEMBER);
    assertThat(appointed.resolvesNotificationTypes()).isEmpty();
    assertThat(revoked.eventType()).isEqualTo(NotificationEventType.PURCHASING_DELEGATE_REVOKED);
    assertThat(revoked.contextRecipientUserId()).isEqualTo(MEMBER);
    assertThat(revoked.resolvesNotificationTypes())
        .containsExactly(NotificationType.PURCHASING_DELEGATE_APPOINTED);
  }

  @Test
  void theEntityTypeTagsAreDistinct() {
    assertThat(
            Set.of(
                PurchasingNotices.NEED_ENTITY_TYPE,
                PurchasingNotices.DEAL_ENTITY_TYPE,
                PurchasingNotices.PAUSE_ENTITY_TYPE,
                PurchasingNotices.DELEGATE_ENTITY_TYPE))
        .hasSize(4);
  }
}
