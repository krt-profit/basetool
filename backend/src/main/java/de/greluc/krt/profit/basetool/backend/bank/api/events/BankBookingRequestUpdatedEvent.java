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

package de.greluc.krt.profit.basetool.backend.bank.api.events;

import de.greluc.krt.profit.basetool.backend.model.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.model.NotificationContextRole;
import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.OrgUnitRef;
import de.greluc.krt.profit.basetool.backend.util.BankAmounts;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Domain event published when a requester corrects their own pending booking request
 * (REQ-BANK-056).
 *
 * <p>Clears the open notices about the request and tells the same recipients as {@link
 * BankBookingRequestCreatedEvent} again, so nobody acts on the old amount. Carries only scalars.
 *
 * @param requestId the corrected request's id (also the notification's loose entity id)
 * @param accountId the request's bank account id ({@code ACCOUNT_GRANT} and {@code
 *     ACCOUNT_RESPONSIBLE} selector input)
 * @param type deposit, withdrawal or transfer
 * @param amount the corrected whole-aUEC amount
 * @param accountNo the account's human-readable number, for rendering
 * @param requesterHandle the requester's effective-name snapshot, for rendering
 * @param orgUnitShorthand the account's org-unit shorthand, for rendering, or {@code null}
 * @param actorSub the requester's sub, excluded from recipients
 */
public record BankBookingRequestUpdatedEvent(
    UUID requestId,
    UUID accountId,
    BankBookingRequestType type,
    BigDecimal amount,
    String accountNo,
    String requesterHandle,
    @Nullable String orgUnitShorthand,
    @Nullable UUID actorSub)
    implements BankBookingRequestEvent {

  @NotNull
  @Override
  public NotificationEventType eventType() {
    return NotificationEventType.BANK_BOOKING_REQUEST_UPDATED_BY_REQUESTER;
  }

  @NotNull
  @Unmodifiable
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

  @NotNull
  @Override
  public Map<String, String> renderParams() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("type", type.name());
    params.put("amount", BankAmounts.plain(amount));
    params.put("accountNo", accountNo);
    if (requesterHandle != null && !requesterHandle.isBlank()) {
      params.put("requester", requesterHandle);
    }
    if (orgUnitShorthand != null && !orgUnitShorthand.isBlank()) {
      params.put("orgUnit", orgUnitShorthand);
    }
    return params;
  }

  /**
   * The earlier notices show the old values, so they are cleared before the new ones are written.
   *
   * @return {@link #OPEN_REQUEST_NOTICES}
   */
  @NotNull
  @Unmodifiable
  @Override
  public Set<NotificationType> resolvesNotificationTypes() {
    return OPEN_REQUEST_NOTICES;
  }
}
