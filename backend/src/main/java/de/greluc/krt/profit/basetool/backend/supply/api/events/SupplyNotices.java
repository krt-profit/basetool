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

package de.greluc.krt.profit.basetool.backend.supply.api.events;

import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationContextRole;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import de.greluc.krt.profit.basetool.backend.notification.api.events.OrgUnitRef;
import de.greluc.krt.profit.basetool.backend.supply.api.DeclineReason;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The donation notices (REQ-SUP-016), built from scalars so they can be delivered after the commit.
 * They carry names and amounts only, never the donor's note (REQ-SUP-014).
 */
public final class SupplyNotices {

  /** Loose entity-type tag of the notices about one donation; the entity id is the donation. */
  public static final String DONATION_ENTITY_TYPE = "SUPPLY_DONATION";

  /** Loose entity-type tag of the notices about one need; the entity id is the need. */
  public static final String NEED_ENTITY_TYPE = "SUPPLY_NEED";

  private static final Set<NotificationType> PLEDGE_NOTICES =
      Set.of(NotificationType.SUPPLY_DONATION_PLEDGED);

  private SupplyNotices() {}

  /**
   * A member pledged a donation; the receiving unit's lead and Logisticians are told.
   *
   * @param donationId the donation
   * @param receivingUnitId the receiving unit
   * @param receivingUnitKind the receiving unit's kind
   * @param unitName the receiving unit's name
   * @param entry the material or item name
   * @param amount the pledged amount
   * @param donorName the donor's display name
   * @param actorSub the donor, kept out of the recipients
   * @return the event
   */
  @NotNull
  public static NoticeEvent pledged(
      @NotNull UUID donationId,
      @NotNull UUID receivingUnitId,
      @NotNull OrgUnitKind receivingUnitKind,
      @NotNull String unitName,
      @NotNull String entry,
      @NotNull String amount,
      @NotNull String donorName,
      @Nullable UUID actorSub) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("unit", unitName);
    params.put("entry", entry);
    params.put("amount", amount);
    params.put("donor", donorName);
    return NoticeEvent.builder()
        .eventType(NotificationEventType.SUPPLY_DONATION_PLEDGED)
        .actorSub(actorSub)
        .entityType(DONATION_ENTITY_TYPE)
        .entityId(donationId)
        .contextOrgUnits(
            Map.of(
                NotificationContextRole.RESPONSIBLE,
                new OrgUnitRef(receivingUnitId, receivingUnitKind)))
        .renderParams(params)
        .build();
  }

  /**
   * A receiving unit confirmed a donation; the donor is told and the pledge notices go.
   *
   * @param donationId the donation
   * @param donorId the donor, or {@code null} for a donation without one
   * @param unitName the receiving unit's name
   * @param entry the material or item name
   * @param receivedAmount the received amount
   * @param actorSub the confirming member
   * @return the event
   */
  @NotNull
  public static NoticeEvent received(
      @NotNull UUID donationId,
      @Nullable UUID donorId,
      @NotNull String unitName,
      @NotNull String entry,
      @NotNull String receivedAmount,
      @Nullable UUID actorSub) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("unit", unitName);
    params.put("entry", entry);
    params.put("amount", receivedAmount);
    return NoticeEvent.builder()
        .eventType(NotificationEventType.SUPPLY_DONATION_RECEIVED)
        .actorSub(actorSub)
        .entityType(DONATION_ENTITY_TYPE)
        .entityId(donationId)
        .contextRecipientUserId(donorId)
        .renderParams(params)
        .resolvesNotificationTypes(PLEDGE_NOTICES)
        .build();
  }

  /**
   * A receiving unit declined a pledge; the donor is told and the pledge notices go.
   *
   * @param donationId the donation
   * @param donorId the donor
   * @param unitName the receiving unit's name
   * @param entry the material or item name
   * @param reason why the unit declined
   * @param actorSub the declining member
   * @return the event
   */
  @NotNull
  public static NoticeEvent declined(
      @NotNull UUID donationId,
      @Nullable UUID donorId,
      @NotNull String unitName,
      @NotNull String entry,
      @NotNull DeclineReason reason,
      @Nullable UUID actorSub) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("unit", unitName);
    params.put("entry", entry);
    params.put("reasonCode", reason.name());
    return NoticeEvent.builder()
        .eventType(NotificationEventType.SUPPLY_DONATION_DECLINED)
        .actorSub(actorSub)
        .entityType(DONATION_ENTITY_TYPE)
        .entityId(donationId)
        .contextRecipientUserId(donorId)
        .renderParams(params)
        .resolvesNotificationTypes(PLEDGE_NOTICES)
        .build();
  }

  /**
   * The job order linked to an open need has stock covering the need's bucket; the raising unit's
   * lead and Logisticians are told (REQ-SUP-024).
   *
   * @param needId the need
   * @param unitId the raising unit
   * @param unitKind the raising unit's kind
   * @param unitName the raising unit's name
   * @param entry the material name
   * @param orderNumber the linked order's number
   * @return the event
   */
  @NotNull
  public static NoticeEvent orderCovered(
      @NotNull UUID needId,
      @NotNull UUID unitId,
      @NotNull OrgUnitKind unitKind,
      @NotNull String unitName,
      @NotNull String entry,
      @NotNull String orderNumber) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("unit", unitName);
    params.put("entry", entry);
    params.put("order", orderNumber);
    return NoticeEvent.builder()
        .eventType(NotificationEventType.SUPPLY_NEED_ORDER_COVERED)
        .entityType(NEED_ENTITY_TYPE)
        .entityId(needId)
        .contextOrgUnits(
            Map.of(NotificationContextRole.RESPONSIBLE, new OrgUnitRef(unitId, unitKind)))
        .renderParams(params)
        .build();
  }

  /**
   * A need was fulfilled or withdrawn; its covered-order notices go (REQ-SUP-024).
   *
   * @param needId the need
   * @return the event
   */
  @NotNull
  public static NoticeEvent needClosed(@NotNull UUID needId) {
    return NoticeEvent.builder()
        .eventType(NotificationEventType.SUPPLY_NEED_CLOSED)
        .entityType(NEED_ENTITY_TYPE)
        .entityId(needId)
        .resolvesNotificationTypes(Set.of(NotificationType.SUPPLY_NEED_ORDER_COVERED))
        .build();
  }

  /**
   * A donor withdrew a pledge; only the pledge notices go.
   *
   * @param donationId the donation
   * @param actorSub the donor
   * @return the event
   */
  @NotNull
  public static NoticeEvent withdrawn(@NotNull UUID donationId, @Nullable UUID actorSub) {
    return NoticeEvent.builder()
        .eventType(NotificationEventType.SUPPLY_DONATION_WITHDRAWN)
        .actorSub(actorSub)
        .entityType(DONATION_ENTITY_TYPE)
        .entityId(donationId)
        .resolvesNotificationTypes(PLEDGE_NOTICES)
        .build();
  }
}
