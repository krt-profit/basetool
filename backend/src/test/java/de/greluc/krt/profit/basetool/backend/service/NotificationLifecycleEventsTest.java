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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.bank.api.events.BankAccountResponsibleAssignedEvent;
import de.greluc.krt.profit.basetool.backend.bank.api.events.BankBookingRequestNoticesReconciledEvent;
import de.greluc.krt.profit.basetool.backend.identity.api.events.DiscordRegistrationDecidedEvent;
import de.greluc.krt.profit.basetool.backend.joborder.api.events.JobOrderClosedEvent;
import de.greluc.krt.profit.basetool.backend.model.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of the events that close the gaps in the existing notifications (REQ-NOTIF-012,
 * REQ-NOTIF-008, REQ-BANK-034, REQ-NOTIF-023): what each clears, whom it is directed at and what it
 * renders.
 */
class NotificationLifecycleEventsTest {

  private static final UUID ID = UUID.randomUUID();
  private static final UUID ACTOR = UUID.randomUUID();

  @Test
  void aDecidedRegistrationClearsThePendingNoticesAndNotifiesNobody() {
    DiscordRegistrationDecidedEvent event = new DiscordRegistrationDecidedEvent(ID, ACTOR);

    assertThat(event.eventType()).isEqualTo(NotificationEventType.DISCORD_REGISTRATION_DECIDED);
    assertThat(event.entityType()).isEqualTo("DISCORD_REGISTRATION");
    assertThat(event.entityId()).isEqualTo(ID);
    assertThat(event.actorSub()).isEqualTo(ACTOR);
    assertThat(event.resolvesNotificationTypes())
        .containsExactly(NotificationType.DISCORD_REGISTRATION_PENDING);
    assertThat(event.contextRecipientUserId()).isNull();
    assertThat(event.renderParams()).isEmpty();
    assertThat(event.reconcileRecipients()).isEmpty();
  }

  @Test
  void aClosedOrderClearsItsCreatedAndRequesterUpdateNotices() {
    JobOrderClosedEvent event = new JobOrderClosedEvent(ID, ACTOR);

    assertThat(event.eventType()).isEqualTo(NotificationEventType.JOB_ORDER_CLOSED);
    assertThat(event.entityType()).isEqualTo("JOB_ORDER");
    assertThat(event.entityId()).isEqualTo(ID);
    assertThat(event.resolvesNotificationTypes())
        .containsExactlyInAnyOrder(
            NotificationType.JOB_ORDER_CREATED, NotificationType.JOB_ORDER_UPDATED_BY_REQUESTER);
    assertThat(event.contextOrgUnits()).isEmpty();
    assertThat(event.renderParams()).isEmpty();
  }

  @Test
  void aNewResponsibleHolderIsTheDirectedRecipientWithAccountAndPendingCount() {
    UUID holder = UUID.randomUUID();
    BankAccountResponsibleAssignedEvent event =
        new BankAccountResponsibleAssignedEvent(ID, holder, "KB-0042", 3, ACTOR);

    assertThat(event.eventType())
        .isEqualTo(NotificationEventType.BANK_ACCOUNT_RESPONSIBLE_ASSIGNED);
    assertThat(event.contextRecipientUserId()).isEqualTo(holder);
    assertThat(event.contextAccountId()).isEqualTo(ID);
    assertThat(event.entityType()).isEqualTo("BANK_ACCOUNT");
    assertThat(event.entityId()).isEqualTo(ID);
    assertThat(event.renderParams())
        .containsExactly(Map.entry("accountNo", "KB-0042"), Map.entry("pending", "3"));
    assertThat(event.resolvesNotificationTypes()).isEmpty();
  }

  @Test
  void aReconcileUsesTheCreatedRulesAndOnlyTheChangedHolders() {
    UUID former = UUID.randomUUID();
    Set<UUID> changed = new HashSet<>(Set.of(former));
    BankBookingRequestNoticesReconciledEvent event =
        new BankBookingRequestNoticesReconciledEvent(
            ID,
            UUID.randomUUID(),
            BankBookingRequestType.DEPOSIT,
            new BigDecimal("1500"),
            "KB-0042",
            "requester",
            null,
            ACTOR,
            changed);
    changed.clear();

    assertThat(event.eventType()).isEqualTo(NotificationEventType.BANK_BOOKING_REQUEST_CREATED);
    assertThat(event.entityType()).isEqualTo("BANK_BOOKING_REQUEST");
    assertThat(event.reconcileRecipients()).containsExactly(former);
    assertThat(event.resolvesNotificationTypes())
        .containsExactlyInAnyOrder(
            NotificationType.BANK_BOOKING_REQUEST_CREATED,
            NotificationType.BANK_BOOKING_REQUEST_UPDATED);
    assertThat(event.renderParams())
        .containsEntry("amount", "1500")
        .containsEntry("accountNo", "KB-0042")
        .containsEntry("requester", "requester")
        .doesNotContainKey("orgUnit");
    assertThatThrownBy(() -> event.reconcileRecipients().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
