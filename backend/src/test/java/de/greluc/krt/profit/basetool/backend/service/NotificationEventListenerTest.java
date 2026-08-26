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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.event.DiscordRegistrationPendingEvent;
import de.greluc.krt.profit.basetool.backend.event.NotificationEvent;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link NotificationEventListener}: the real-time SSE push must fire from here —
 * AFTER {@link NotificationCreationService#createFromEvent} returns (i.e. after its transaction
 * commits), targeting exactly the recipients it resolved (#1152) — and must never be attempted when
 * no recipient matched or creation failed (so a hiccup stays best-effort, REQ-NOTIF-010).
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
    when(notificationCreationService.createFromEvent(event))
        .thenReturn(Map.of(NotificationSignal.refreshOnly(), recipients));

    listener.onNotificationEvent(event);

    // The push is the recipients createFromEvent returned — fired after it (its tx) has committed.
    verify(notificationFanout).publish(recipients, NotificationSignal.refreshOnly());
  }

  @Test
  void doesNotPublish_whenNoRecipientsResolved() {
    NotificationEvent event = event();
    when(notificationCreationService.createFromEvent(event)).thenReturn(Map.of());

    listener.onNotificationEvent(event);

    verify(notificationFanout, never()).publish(any(), any());
  }

  @Test
  void swallowsCreationFailure_andDoesNotPublish() {
    NotificationEvent event = event();
    when(notificationCreationService.createFromEvent(event))
        .thenThrow(new RuntimeException("db down"));

    // The business transaction has already committed; a notification hiccup must not surface.
    listener.onNotificationEvent(event);

    verify(notificationFanout, never()).publish(any(), any());
  }

  @Test
  void onNotificationEvent_publishesOncePerSignal_soEachAudienceIsToldItsOwnKind() {
    // Given one event that raised two different notification types for two different audiences --
    // the case a single flattened recipient set cannot express.
    NotificationEvent event = event();
    Set<UUID> officers = Set.of(UUID.randomUUID());
    Set<UUID> members = Set.of(UUID.randomUUID(), UUID.randomUUID());
    NotificationSignal toOfficers =
        new NotificationSignal(
            NotificationType.DISCORD_REGISTRATION_PENDING, "USER", UUID.randomUUID(), Map.of());
    NotificationSignal toMembers = NotificationSignal.refreshOnly();
    when(notificationCreationService.createFromEvent(event))
        .thenReturn(Map.of(toOfficers, officers, toMembers, members));

    // When the event is handled
    listener.onNotificationEvent(event);

    // Then each audience is pushed what IT was told, rather than one push describing the event.
    verify(notificationFanout).publish(officers, toOfficers);
    verify(notificationFanout).publish(members, toMembers);
  }
}
