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

package de.greluc.krt.profit.basetool.backend.notification.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.identity.api.events.DiscordRegistrationPendingEvent;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationFanout;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationEvent;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link NotificationEventListener}: the SSE push follows {@link
 * NotificationCreationService#createFromEvent} for exactly its recipients, once each, and is
 * skipped when none matched or creation failed (REQ-NOTIF-010, ADR-0273).
 */
@ExtendWith(MockitoExtension.class)
class NotificationEventListenerTest {

  @Mock private NotificationCreationService notificationCreationService;
  @Mock private NotificationFanout notificationFanout;
  @InjectMocks private NotificationEventListener listener;

  private static NotificationEvent event() {
    return new DiscordRegistrationPendingEvent(UUID.randomUUID(), "newbie");
  }

  @Test
  void publishesToResolvedRecipients_afterCreateReturns() {
    NotificationEvent event = event();
    Set<UUID> recipients = Set.of(UUID.randomUUID(), UUID.randomUUID());
    when(notificationCreationService.createFromEvent(event)).thenReturn(recipients);

    listener.onNotificationEvent(event);

    verify(notificationFanout).publish(recipients);
  }

  @Test
  void doesNotPublish_whenNoRecipientsResolved() {
    NotificationEvent event = event();
    when(notificationCreationService.createFromEvent(event)).thenReturn(Set.of());

    listener.onNotificationEvent(event);

    verify(notificationFanout, never()).publish(any());
  }

  @Test
  void swallowsCreationFailure_andDoesNotPublish() {
    NotificationEvent event = event();
    when(notificationCreationService.createFromEvent(event))
        .thenThrow(new RuntimeException("db down"));

    listener.onNotificationEvent(event);

    verify(notificationFanout, never()).publish(any());
  }

  @Test
  void onNotificationEvent_publishesOnce_forEveryAudienceOfOneEventTogether() {
    NotificationEvent event = event();
    UUID officer = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    when(notificationCreationService.createFromEvent(event)).thenReturn(Set.of(officer, member));

    listener.onNotificationEvent(event);

    verify(notificationFanout).publish(Set.of(officer, member));
    verifyNoMoreInteractions(notificationFanout);
  }
}
