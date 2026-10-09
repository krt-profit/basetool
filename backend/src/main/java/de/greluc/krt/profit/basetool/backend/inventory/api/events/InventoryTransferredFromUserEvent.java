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
 * Domain event published once per Lager transfer action and previous owner when someone other than
 * that member moved their stock onto another member (REQ-INV-055); the default rule notifies the
 * previous owner.
 *
 * @param previousOwnerId the member the stock was taken from, the single recipient
 * @param actorSub the member who made the transfer
 * @param actorName the actor's effective name, for rendering
 * @param newOwnerName the effective name of the member the stock went to, for rendering
 * @param entityId the first moved source row, the notification's loose entity id
 * @param lots the moved lots with their source locations, in booking order; never empty
 */
public record InventoryTransferredFromUserEvent(
    @NotNull UUID previousOwnerId,
    @NotNull UUID actorSub,
    @NotNull String actorName,
    @NotNull String newOwnerName,
    @NotNull UUID entityId,
    @NotNull @Unmodifiable List<TransferredLot> lots)
    implements NotificationEvent {

  /** Loose entity-type tag stored on the produced notification. */
  public static final String ENTITY_TYPE = "INVENTORY_ITEM";

  /**
   * Copies the lots so the event stays immutable.
   *
   * @param previousOwnerId the member the stock was taken from
   * @param actorSub the member who made the transfer
   * @param actorName the actor's effective name
   * @param newOwnerName the effective name of the new owner
   * @param entityId the first moved source row
   * @param lots the moved lots
   */
  public InventoryTransferredFromUserEvent {
    lots = List.copyOf(lots);
  }

  @NotNull
  @Override
  public NotificationEventType eventType() {
    return NotificationEventType.INVENTORY_TRANSFERRED_FROM_USER;
  }

  @NotNull
  @Unmodifiable
  @Override
  public Map<NotificationContextRole, OrgUnitRef> contextOrgUnits() {
    return Map.of();
  }

  /**
   * The single recipient the {@code EVENT_RECIPIENT} selector resolves to — the previous owner.
   *
   * @return the previous owner's id
   */
  @NotNull
  @Override
  public UUID contextRecipientUserId() {
    return previousOwnerId;
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
    params.put("newOwner", newOwnerName);
    params.put("count", Integer.toString(lots.size()));
    params.put("lots", TransferredLot.formatAll(lots));
    return params;
  }
}
