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
import de.greluc.krt.profit.basetool.backend.notification.api.events.OrgUnitRef;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * An order provision reported on behalf of one linked holder.
 *
 * @param entityId the affected order
 * @param holderId the credited order-local identity
 * @param contextRecipientUserId the affected account at booking
 * @param actorSub the account that reported the provision
 * @param displayId the order's running number
 */
public record JobOrderMoneyProvisionedEvent(
    UUID entityId, UUID holderId, UUID contextRecipientUserId, UUID actorSub, int displayId)
    implements NotificationEvent {

  @Override
  public NotificationEventType eventType() {
    return NotificationEventType.JOB_ORDER_MONEY_PROVISIONED;
  }

  @Override
  public String entityType() {
    return JobOrderCreatedEvent.ENTITY_TYPE;
  }

  @Override
  public boolean admitsRecipient(@NotNull UUID recipientUserId) {
    return contextRecipientUserId.equals(recipientUserId);
  }

  @Override
  public Map<NotificationContextRole, OrgUnitRef> contextOrgUnits() {
    return Map.of();
  }

  @Override
  public Map<String, String> renderParams() {
    return Map.of("displayId", Integer.toString(displayId));
  }
}
