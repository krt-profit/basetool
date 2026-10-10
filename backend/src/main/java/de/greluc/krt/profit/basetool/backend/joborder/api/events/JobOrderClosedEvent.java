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

package de.greluc.krt.profit.basetool.backend.joborder.api.events;

import de.greluc.krt.profit.basetool.backend.model.NotificationContextRole;
import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationEvent;
import de.greluc.krt.profit.basetool.backend.notification.api.events.OrgUnitRef;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Published when a job order is completed, rejected or deleted (REQ-NOTIF-008, REQ-NOTIF-018).
 *
 * <p>Directed at nobody: its only effect is to clear the open "new order" and "order changed by the
 * requester" items, so an inbox does not keep pointing at an order that is settled or gone.
 *
 * @param jobOrderId the closed order; the loose entity id of the cleared items
 * @param actorSub the member who closed it, or {@code null} when none is known
 */
public record JobOrderClosedEvent(@NotNull UUID jobOrderId, @Nullable UUID actorSub)
    implements NotificationEvent {

  @NotNull
  @Override
  public NotificationEventType eventType() {
    return NotificationEventType.JOB_ORDER_CLOSED;
  }

  @NotNull
  @Unmodifiable
  @Override
  public Map<NotificationContextRole, OrgUnitRef> contextOrgUnits() {
    return Map.of();
  }

  @NotNull
  @Override
  public String entityType() {
    return JobOrderCreatedEvent.ENTITY_TYPE;
  }

  @NotNull
  @Override
  public UUID entityId() {
    return jobOrderId;
  }

  @NotNull
  @Unmodifiable
  @Override
  public Map<String, String> renderParams() {
    return Map.of();
  }

  /**
   * The order needs no more action, so its open order notices are stale.
   *
   * @return {@link NotificationType#JOB_ORDER_CREATED} and {@link
   *     NotificationType#JOB_ORDER_UPDATED_BY_REQUESTER}
   */
  @NotNull
  @Unmodifiable
  @Override
  public Set<NotificationType> resolvesNotificationTypes() {
    return Set.of(
        NotificationType.JOB_ORDER_CREATED, NotificationType.JOB_ORDER_UPDATED_BY_REQUESTER);
  }
}
