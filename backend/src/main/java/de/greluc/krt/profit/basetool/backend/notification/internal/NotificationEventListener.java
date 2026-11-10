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

import de.greluc.krt.profit.basetool.backend.notification.api.NotificationFanout;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationCause;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationEvent;
import de.greluc.krt.profit.basetool.backend.platform.api.AsyncConfig;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Delivers original events and logical causes after commit through the existing async executor. */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationEventListener {
  private final NotificationCreationService notificationCreationService;
  private final NotificationFanout notificationFanout;

  /**
   * Creates an original event's notices and signals its changed recipients after their commit.
   *
   * @param event the committed original notification event
   */
  @Async(AsyncConfig.NOTIFICATION_EXECUTOR)
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onNotificationEvent(@NotNull NotificationEvent event) {
    Set<UUID> recipients;
    try {
      recipients = notificationCreationService.createFromEvent(event);
    } catch (RuntimeException failure) {
      log.error(
          "Failed to create notifications for event {} entity {}",
          event.eventType(),
          event.entityId(),
          failure);
      return;
    }
    fanout(recipients);
  }

  /**
   * Delivers a committed cause once and signals the union of changed recipients.
   *
   * @param cause the committed logical revision and original events
   */
  @Async(AsyncConfig.NOTIFICATION_EXECUTOR)
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onNotificationCause(@NotNull NotificationCause cause) {
    Set<UUID> recipients;
    try {
      recipients = notificationCreationService.createFromCause(cause);
    } catch (RuntimeException failure) {
      log.error("Failed to create notifications for logical cause {}", cause.id(), failure);
      return;
    }
    fanout(recipients);
  }

  private void fanout(@NotNull Set<UUID> recipients) {
    if (recipients.isEmpty()) {
      return;
    }
    try {
      notificationFanout.publish(recipients);
    } catch (RuntimeException failure) {
      log.debug("Real-time notification push failed; polling fallback remains", failure);
    }
  }
}
