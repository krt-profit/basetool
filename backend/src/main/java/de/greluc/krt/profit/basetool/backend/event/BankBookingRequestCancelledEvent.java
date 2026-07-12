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

package de.greluc.krt.profit.basetool.backend.event;

import de.greluc.krt.profit.basetool.backend.model.NotificationContextRole;
import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Domain event published right after a requester withdraws (cancels) their own still-pending bank
 * booking request (REQ-BANK-022, REQ-NOTIF-018). Unlike the confirm/reject events it is
 * <strong>directed at nobody</strong> — the withdrawing requester is the {@link #actorSub()} — so
 * it seeds no notification rule and creates no new notification. Its sole notification-pipeline
 * effect is to {@linkplain #resolvesNotificationTypes() clear} the now-stale {@code
 * BANK_BOOKING_REQUEST_CREATED} items the bank staff were shown for this request. Carries only
 * scalars so the after-commit listener never touches the managed request.
 *
 * @param requestId the cancelled request's id (also the notification's loose entity id)
 * @param accountId the target bank account id
 * @param actorSub the withdrawing requester's sub (the actor)
 */
public record BankBookingRequestCancelledEvent(
    UUID requestId, UUID accountId, @Nullable UUID actorSub) implements BankBookingRequestEvent {

  @Override
  public NotificationEventType eventType() {
    return NotificationEventType.BANK_BOOKING_REQUEST_CANCELLED;
  }

  @Override
  public Map<NotificationContextRole, OrgUnitRef> contextOrgUnits() {
    return Map.of();
  }

  @Override
  public UUID contextAccountId() {
    return accountId;
  }

  @Override
  public UUID entityId() {
    return requestId;
  }

  @Override
  public Map<String, String> renderParams() {
    return Map.of();
  }

  /**
   * Withdrawing the request settles its lifecycle, so the "new booking request" items the bank
   * staff were shown are now stale and get cleared (REQ-NOTIF-018).
   *
   * @return the singleton {@link NotificationType#BANK_BOOKING_REQUEST_CREATED}
   */
  @Override
  public Set<NotificationType> resolvesNotificationTypes() {
    return Set.of(NotificationType.BANK_BOOKING_REQUEST_CREATED);
  }
}
