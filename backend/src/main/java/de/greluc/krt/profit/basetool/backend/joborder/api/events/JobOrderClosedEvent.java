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

import de.greluc.krt.profit.basetool.backend.notification.api.NotificationContextRole;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationEvent;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationRevision;
import de.greluc.krt.profit.basetool.backend.notification.api.events.OrgUnitRef;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Clears outstanding order action prompts after completion, rejection, cancellation or deletion
 * (REQ-NOTIF-008, REQ-NOTIF-018).
 *
 * @param jobOrderId the closed order; the loose entity id of the cleared items
 * @param actorSub the member who closed it, or {@code null} when none is known
 * @param observedRevision the order revisions at closure
 * @param resolvesNotificationIds the action prompt IDs observed under the order lock
 */
public record JobOrderClosedEvent(
    @NotNull UUID jobOrderId,
    @Nullable UUID actorSub,
    @NotNull NotificationRevision observedRevision,
    @NotNull @Unmodifiable Set<UUID> resolvesNotificationIds)
    implements NotificationEvent {

  /**
   * Captures the immutable cleanup scope.
   *
   * @param jobOrderId the closed order
   * @param actorSub the member who closed it, if known
   * @param observedRevision the order revisions at closure
   * @param resolvesNotificationIds the order's observed action prompt IDs
   */
  public JobOrderClosedEvent {
    resolvesNotificationIds = Set.copyOf(resolvesNotificationIds);
  }

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
}
