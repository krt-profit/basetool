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

import de.greluc.krt.profit.basetool.backend.config.AsyncConfig;
import de.greluc.krt.profit.basetool.backend.event.NotificationEvent;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges published {@link NotificationEvent}s to notification creation.
 *
 * <p>Runs after the originating transaction commits, on the {@link
 * AsyncConfig#NOTIFICATION_EXECUTOR} pool; failures are logged and swallowed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationEventListener {

  private final NotificationCreationService notificationCreationService;
  private final NotificationFanout notificationFanout;

  /**
   * Creates notifications for an event, then pushes the real-time SSE signal to the recipients once
   * that creation has committed.
   *
   * <p>The push is best-effort; polling keeps the badge correct (REQ-NOTIF-010).
   *
   * @param event the published notification event
   */
  @Async(AsyncConfig.NOTIFICATION_EXECUTOR)
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onNotificationEvent(NotificationEvent event) {
    Map<NotificationSignal, Set<UUID>> recipientsBySignal;
    try {
      recipientsBySignal = notificationCreationService.createFromEvent(event);
    } catch (RuntimeException e) {
      log.error(
          "Failed to create notifications for event {} entity {}",
          event.eventType(),
          event.entityId(),
          e);
      return;
    }
    if (recipientsBySignal.isEmpty()) {
      return;
    }
    try {
      for (Map.Entry<NotificationSignal, Set<UUID>> entry : recipientsBySignal.entrySet()) {
        notificationFanout.publish(entry.getValue(), entry.getKey());
      }
    } catch (RuntimeException e) {
      log.debug("Real-time notification push failed; polling fallback remains", e);
    }
  }
}
