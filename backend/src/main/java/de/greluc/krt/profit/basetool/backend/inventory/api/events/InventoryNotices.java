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

import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationContextRole;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.ActorRef;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import de.greluc.krt.profit.basetool.backend.notification.api.events.OrgUnitRef;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The notices that tell a member somebody else booked out or sold part of their Lager stock
 * (REQ-INV-056) or moved it into another pool (REQ-INV-059), and the notice that a minimum stock
 * level turned low (REQ-NOTIF-030), built from scalars so they can be delivered after the commit.
 */
public final class InventoryNotices {

  /** Loose entity-type tag stored on the produced notification. */
  public static final String ENTITY_TYPE = InventoryTransferredFromUserEvent.ENTITY_TYPE;

  /** Loose entity-type tag of the notices about one minimum stock level. */
  public static final String STOCK_LEVEL_ENTITY_TYPE = "INVENTORY_STOCK_LEVEL";

  private InventoryNotices() {}

  /**
   * A minimum stock level turned low.
   *
   * @param levelId the level, the notification's loose entity id
   * @param unitId the unit whose pool the level watches, the context unit the rule resolves
   * @param unitKind the unit's kind
   * @param unitName the unit's name
   * @param entry the material or item name
   * @param tierCode the tier's code, or {@code null} for any quality
   * @param minimum the amount below which the level is low
   * @param allocated the free stock allocated to the level
   */
  public record StockLevelLow(
      @NotNull UUID levelId,
      @NotNull UUID unitId,
      @NotNull OrgUnitKind unitKind,
      @NotNull String unitName,
      @NotNull String entry,
      @Nullable String tierCode,
      double minimum,
      double allocated) {}

  /**
   * A minimum stock level turned low; the unit's direct Logisticians and seat holders are told.
   *
   * @param level the level and its figures
   * @return the event
   */
  @NotNull
  public static NoticeEvent stockLevelLow(@NotNull StockLevelLow level) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("unit", level.unitName());
    params.put(
        "entry",
        level.tierCode() == null ? level.entry() : level.entry() + " · " + level.tierCode());
    params.put("minimum", amount(level.minimum()));
    params.put("amount", amount(level.allocated()));
    return NoticeEvent.builder()
        .eventType(NotificationEventType.INVENTORY_STOCK_LEVEL_LOW)
        .entityType(STOCK_LEVEL_ENTITY_TYPE)
        .entityId(level.levelId())
        .contextOrgUnits(
            Map.of(
                NotificationContextRole.RESPONSIBLE,
                new OrgUnitRef(level.unitId(), level.unitKind())))
        .renderParams(params)
        .build();
  }

  private static String amount(double value) {
    return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
  }

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

  /**
   * Somebody other than the holder moved a member's shared stock into another pool (REQ-INV-059).
   *
   * @param holderId the member who holds the stock, the single recipient
   * @param rowId the moved source row, the notification's loose entity id
   * @param actor who did it
   * @param fromUnit the name of the unit whose pool the stock left
   * @param toUnit the name of the unit whose pool it entered
   * @param lot the moved lot
   * @return the event
   */
  @NotNull
  public static NoticeEvent poolChangedByOther(
      @NotNull UUID holderId,
      @NotNull UUID rowId,
      @NotNull ActorRef actor,
      @NotNull String fromUnit,
      @NotNull String toUnit,
      @NotNull TransferredLot lot) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("actor", actor.name());
    params.put("fromUnit", fromUnit);
    params.put("toUnit", toUnit);
    params.put("lots", TransferredLot.formatAll(List.of(lot)));
    return NoticeEvent.builder()
        .eventType(NotificationEventType.INVENTORY_POOL_CHANGED_BY_OTHER)
        .actorSub(actor.id())
        .entityType(ENTITY_TYPE)
        .entityId(rowId)
        .contextRecipientUserId(holderId)
        .renderParams(params)
        .build();
  }
}
