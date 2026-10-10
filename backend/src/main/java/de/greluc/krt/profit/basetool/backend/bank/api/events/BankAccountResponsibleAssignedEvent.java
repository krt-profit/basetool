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

import de.greluc.krt.profit.basetool.backend.model.NotificationContextRole;
import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationEvent;
import de.greluc.krt.profit.basetool.backend.notification.api.events.OrgUnitRef;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Domain event published once per member who became a responsible holder of a bank account
 * (REQ-BANK-034); the default rule notifies that member.
 *
 * @param accountId the account (also the notification's loose entity id)
 * @param holderId the new responsible holder, the single recipient
 * @param accountNo the account's human-readable number, for rendering
 * @param pending how many open requests on the account await a responsible holder's approval
 * @param actorSub the member whose change made them responsible, or {@code null}
 */
public record BankAccountResponsibleAssignedEvent(
    @NotNull UUID accountId,
    @NotNull UUID holderId,
    @NotNull String accountNo,
    int pending,
    @Nullable UUID actorSub)
    implements NotificationEvent {

  /** Loose entity-type tag stored on the produced notification. */
  public static final String ENTITY_TYPE = "BANK_ACCOUNT";

  @NotNull
  @Override
  public NotificationEventType eventType() {
    return NotificationEventType.BANK_ACCOUNT_RESPONSIBLE_ASSIGNED;
  }

  @NotNull
  @Unmodifiable
  @Override
  public Map<NotificationContextRole, OrgUnitRef> contextOrgUnits() {
    return Map.of();
  }

  @NotNull
  @Override
  public UUID contextAccountId() {
    return accountId;
  }

  /**
   * The single recipient the {@code EVENT_RECIPIENT} selector resolves to — the new holder.
   *
   * @return the new holder's id
   */
  @NotNull
  @Override
  public UUID contextRecipientUserId() {
    return holderId;
  }

  @NotNull
  @Override
  public String entityType() {
    return ENTITY_TYPE;
  }

  @NotNull
  @Override
  public UUID entityId() {
    return accountId;
  }

  @NotNull
  @Override
  public Map<String, String> renderParams() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("accountNo", accountNo);
    params.put("pending", Integer.toString(pending));
    return params;
  }
}
