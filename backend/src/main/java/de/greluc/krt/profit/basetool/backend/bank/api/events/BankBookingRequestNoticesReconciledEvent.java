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

import de.greluc.krt.profit.basetool.backend.bank.api.BankAmounts;
import de.greluc.krt.profit.basetool.backend.bank.api.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.model.NotificationContextRole;
import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.OrgUnitRef;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Domain event published for every open request on an account whose responsible holders changed
 * (REQ-BANK-034, REQ-NOTIF-023).
 *
 * <p>It re-decides the request's open notice for the members who became or stopped being
 * responsible only: against the rules of {@link
 * NotificationEventType#BANK_BOOKING_REQUEST_CREATED}, a new holder gets the notice and a former
 * holder whom no other selector still reaches loses it. Every other recipient keeps what they have.
 *
 * @param requestId the open request (also the notification's loose entity id)
 * @param accountId the request's account ({@code ACCOUNT_GRANT} and {@code ACCOUNT_RESPONSIBLE}
 *     selector input)
 * @param type deposit, withdrawal or transfer
 * @param amount the request's current whole-aUEC amount
 * @param accountNo the account's human-readable number, for rendering
 * @param requesterHandle the requester's effective-name snapshot, for rendering
 * @param orgUnitShorthand the account's org-unit shorthand, for rendering, or {@code null}
 * @param actorSub the requester's sub, kept out of the notice as on creation
 * @param changedHolders the members who became or stopped being responsible holders
 */
public record BankBookingRequestNoticesReconciledEvent(
    UUID requestId,
    UUID accountId,
    BankBookingRequestType type,
    BigDecimal amount,
    String accountNo,
    String requesterHandle,
    @Nullable String orgUnitShorthand,
    @Nullable UUID actorSub,
    @NotNull @Unmodifiable Set<UUID> changedHolders)
    implements BankBookingRequestEvent {

  /**
   * Copies the changed holders so the event stays immutable.
   *
   * @param requestId the open request
   * @param accountId the request's account
   * @param type the movement kind
   * @param amount the current amount
   * @param accountNo the account number
   * @param requesterHandle the requester's handle
   * @param orgUnitShorthand the account's org-unit shorthand, or {@code null}
   * @param actorSub the requester's sub
   * @param changedHolders the members who became or stopped being responsible holders
   */
  public BankBookingRequestNoticesReconciledEvent {
    changedHolders = Set.copyOf(changedHolders);
  }

  /**
   * The rules of a newly created request decide who should hold its notice.
   *
   * @return {@link NotificationEventType#BANK_BOOKING_REQUEST_CREATED}
   */
  @NotNull
  @Override
  public NotificationEventType eventType() {
    return NotificationEventType.BANK_BOOKING_REQUEST_CREATED;
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
   * A member holding any open-request notice for the request counts as told.
   *
   * @return {@link #OPEN_REQUEST_NOTICES}
   */
  @NotNull
  @Unmodifiable
  @Override
  public Set<NotificationType> resolvesNotificationTypes() {
    return OPEN_REQUEST_NOTICES;
  }

  /**
   * Only the changed holders are reconciled.
   *
   * @return {@link #changedHolders()}
   */
  @NotNull
  @Unmodifiable
  @Override
  public Set<UUID> reconcileRecipients() {
    return changedHolders;
  }
}
