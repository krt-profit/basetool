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
import de.greluc.krt.profit.basetool.backend.util.BankAmounts;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Domain event published right after a bank employee confirms a booking request (epic #666 F2,
 * REQ-BANK-026). It is directed at the requesting officer/lead so they learn the outcome: the
 * {@code EVENT_RECIPIENT} selector resolves to {@link #contextRecipientSub()} (the requester),
 * while the confirming employee is the {@link #actorSub()} (excluded when the rule sets {@code
 * excludeActor}). It additionally carries the account id ({@link #contextAccountId()}) so the
 * {@code ACCOUNT_RESPONSIBLE} selector can notify the account's responsible holder (REQ-BANK-034).
 * Carries only scalars so the after-commit listener never touches the managed request.
 *
 * @param requestId the confirmed request's id (also the notification's loose entity id)
 * @param accountId the target bank account id ({@code ACCOUNT_RESPONSIBLE} selector input)
 * @param accountNo the target account's human-readable number, for rendering
 * @param amount the requested whole-aUEC amount, for rendering
 * @param requesterSub the requesting officer/lead's sub — the directed recipient
 * @param actorSub the confirming employee's sub
 */
public record BankBookingRequestConfirmedEvent(
    UUID requestId,
    UUID accountId,
    String accountNo,
    BigDecimal amount,
    @Nullable UUID requesterSub,
    @Nullable UUID actorSub)
    implements BankBookingRequestEvent {

  @Override
  public NotificationEventType eventType() {
    return NotificationEventType.BANK_BOOKING_REQUEST_CONFIRMED;
  }

  @Override
  public Map<NotificationContextRole, OrgUnitRef> contextOrgUnits() {
    return Map.of();
  }

  @Override
  public UUID contextRecipientSub() {
    return requesterSub;
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
    Map<String, String> params = new LinkedHashMap<>();
    params.put("accountNo", accountNo);
    params.put("amount", BankAmounts.plain(amount));
    return params;
  }

  /**
   * Confirming the request settles its lifecycle, so the "new booking request" items the bank staff
   * were shown are now stale and get cleared (REQ-NOTIF-018).
   *
   * @return the singleton {@link NotificationType#BANK_BOOKING_REQUEST_CREATED}
   */
  @Override
  public Set<NotificationType> resolvesNotificationTypes() {
    return Set.of(NotificationType.BANK_BOOKING_REQUEST_CREATED);
  }
}
