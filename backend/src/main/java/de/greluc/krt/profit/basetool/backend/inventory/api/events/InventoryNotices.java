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

import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.ActorRef;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * The notice that tells a member somebody else booked out or sold part of their Lager stock
 * (REQ-INV-056), built from scalars so it can be delivered after the commit.
 */
public final class InventoryNotices {

  /** Loose entity-type tag stored on the produced notification. */
  public static final String ENTITY_TYPE = InventoryTransferredFromUserEvent.ENTITY_TYPE;

  private InventoryNotices() {}

  /**
   * Somebody other than the owner discarded or sold part of a member's stock.
   *
   * @param ownerId the member whose stock it was, the single recipient
   * @param rowId the booked-out source row, the notification's loose entity id
   * @param actor who did it
   * @param actionCode {@code DISCARDED} or {@code SOLD}
   * @param lots the lots the action took, in booking order; never empty
   * @return the event
   */
  @NotNull
  public static NoticeEvent bookedOutByOther(
      @NotNull UUID ownerId,
      @NotNull UUID rowId,
      @NotNull ActorRef actor,
      @NotNull String actionCode,
      @NotNull List<TransferredLot> lots) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("actor", actor.name());
    params.put("actionCode", actionCode);
    params.put("count", Integer.toString(lots.size()));
    params.put("lots", TransferredLot.formatAll(lots));
    return NoticeEvent.builder()
        .eventType(NotificationEventType.INVENTORY_BOOKED_OUT_BY_OTHER)
        .actorSub(actor.id())
        .entityType(ENTITY_TYPE)
        .entityId(rowId)
        .contextRecipientUserId(ownerId)
        .renderParams(params)
        .build();
  }
}
