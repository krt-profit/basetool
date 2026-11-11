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
import org.jetbrains.annotations.Nullable;

/**
 * An order movement affecting one linked person other than its recorder.
 *
 * @param entityId the affected order
 * @param holderId the affected holder, when the subject is a holder
 * @param recipientId the affected requester designation, when the subject is a payee
 * @param contextRecipientUserId the affected account at booking
 * @param actorSub the account that reported the payment
 * @param sourceAccount linked sender whose identity belongs to the cause lock set
 * @param destinationAccount linked receiver whose identity belongs to the cause lock set
 * @param groupedWithCompletion whether the order cause already owns its row guard
 * @param displayId the order's running number
 */
public record JobOrderMoneyMovedEvent(
    UUID entityId,
    @Nullable UUID holderId,
    @Nullable UUID recipientId,
    UUID contextRecipientUserId,
    UUID actorSub,
    @Nullable UUID sourceAccount,
    @Nullable UUID destinationAccount,
    boolean groupedWithCompletion,
    int displayId)
    implements NotificationEvent {

  @Override
  public NotificationEventType eventType() {
    return NotificationEventType.JOB_ORDER_MONEY_MOVED;
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
