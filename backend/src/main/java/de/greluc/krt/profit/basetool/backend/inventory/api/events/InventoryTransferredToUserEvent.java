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

package de.greluc.krt.profit.basetool.backend.inventory.api.events;

import de.greluc.krt.profit.basetool.backend.model.NotificationContextRole;
import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationEvent;
import de.greluc.krt.profit.basetool.backend.notification.api.events.OrgUnitRef;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Domain event published once per Lager transfer action and new owner when stock was booked onto a
 * member other than the actor (REQ-INV-055); the default rule notifies that member.
 *
 * @param newOwnerId the member the stock was booked onto, the single recipient
 * @param actorSub the member who made the transfer
 * @param actorName the actor's effective name, for rendering
 * @param entityId the first moved source row, the notification's loose entity id
 * @param lots the moved lots with their target locations, in booking order; never empty
 */
public record InventoryTransferredToUserEvent(
    @NotNull UUID newOwnerId,
    @NotNull UUID actorSub,
    @NotNull String actorName,
    @NotNull UUID entityId,
    @NotNull @Unmodifiable List<TransferredLot> lots)
    implements NotificationEvent {

  /** Loose entity-type tag stored on the produced notification. */
  public static final String ENTITY_TYPE = "INVENTORY_ITEM";

  /**
   * Copies the lots so the event stays immutable.
   *
   * @param newOwnerId the member the stock was booked onto
   * @param actorSub the member who made the transfer
   * @param actorName the actor's effective name
   * @param entityId the first moved source row
   * @param lots the moved lots
   */
  public InventoryTransferredToUserEvent {
    lots = List.copyOf(lots);
  }

  @NotNull
  @Override
  public NotificationEventType eventType() {
    return NotificationEventType.INVENTORY_TRANSFERRED_TO_USER;
  }

  @NotNull
  @Unmodifiable
  @Override
  public Map<NotificationContextRole, OrgUnitRef> contextOrgUnits() {
    return Map.of();
  }

  /**
   * The single recipient the {@code EVENT_RECIPIENT} selector resolves to — the new owner.
   *
   * @return the new owner's id
   */
  @NotNull
  @Override
  public UUID contextRecipientUserId() {
    return newOwnerId;
  }

  @NotNull
  @Override
  public String entityType() {
    return ENTITY_TYPE;
  }

  @NotNull
  @Override
  public Map<String, String> renderParams() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("actor", actorName);
    params.put("count", Integer.toString(lots.size()));
    params.put("lots", TransferredLot.formatAll(lots));
    return params;
  }
}
