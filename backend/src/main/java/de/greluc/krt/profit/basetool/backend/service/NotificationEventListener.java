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
 * <p>Fires only {@code AFTER_COMMIT} of the originating transaction (so a rolled-back business
 * action never produces phantom notifications) and runs on the dedicated {@link
 * AsyncConfig#NOTIFICATION_EXECUTOR} thread pool (so creation never adds latency to the request).
 * Any failure is swallowed and logged: the business transaction has already committed, so a
 * notification hiccup must not surface to the user or be retried inline.
 *
 * <p>Lives in the {@code service} package rather than {@code event}: it is the orchestration seam
 * that consumes a {@link NotificationCreationService}, so keeping it here leaves {@code event} a
 * pure payload leaf (the {@link NotificationEvent} records depend only on {@code model}) and avoids
 * an {@code event} &rarr; {@code service} package cycle.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationEventListener {

  private final NotificationCreationService notificationCreationService;
  private final NotificationFanout notificationFanout;

  /**
   * Creates notifications for an event after its originating transaction commits, then pushes the
   * real-time SSE signal to the resolved recipients.
   *
   * <p>The push runs here, <em>after</em> {@link NotificationCreationService#createFromEvent}
   * returns — i.e. after that method's own transaction has committed (#1152) — so the client's
   * unread-count refetch reads the committed rows rather than a pre-commit stale count, and the
   * blocking SSE fan-out no longer pins the creation transaction's Hikari connection. The push is
   * best-effort: a failure is swallowed because the frontend polling fallback keeps the badge
   * correct (REQ-NOTIF-010).
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
      // Push through the fan-out seam: local-only by default, cross-replica via Redis when enabled
      // (ADR-0094). Best-effort — the polling fallback keeps the badge correct (REQ-NOTIF-010).
      //
      // One call per signal, not one per event: recipients of the same event can have been told
      // different things, and a client is entitled to know which of them it was told.
      for (Map.Entry<NotificationSignal, Set<UUID>> entry : recipientsBySignal.entrySet()) {
        notificationFanout.publish(entry.getValue(), entry.getKey());
      }
    } catch (RuntimeException e) {
      log.debug("Real-time notification push failed; polling fallback remains", e);
    }
  }
}
