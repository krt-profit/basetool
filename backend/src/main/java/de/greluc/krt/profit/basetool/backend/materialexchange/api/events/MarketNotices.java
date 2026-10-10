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

package de.greluc.krt.profit.basetool.backend.materialexchange.api.events;

import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The notices of the Materialbörse that tell the members who showed interest that an offer or a
 * request is gone (REQ-MARKET-021, -022), built from scalars so they can be delivered after the
 * commit.
 */
public final class MarketNotices {

  private MarketNotices() {}

  /**
   * An offer left the board; the members who registered interest hear it and the owner's interest
   * notices for the offer go.
   *
   * @param offerId the offer
   * @param item the offered material's or item's name
   * @param reasonCode {@code WITHDRAWN} or {@code STOCK_GONE}
   * @param interested the members who registered interest
   * @param actorSub the member whose action ended the offer, or {@code null}
   * @return the event
   */
  @NotNull
  public static NoticeEvent offerUnavailable(
      @NotNull UUID offerId,
      @NotNull String item,
      @NotNull String reasonCode,
      @NotNull Set<UUID> interested,
      @Nullable UUID actorSub) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("item", item);
    params.put("reasonCode", reasonCode);
    return NoticeEvent.builder()
        .eventType(NotificationEventType.MATERIAL_EXCHANGE_OFFER_UNAVAILABLE)
        .actorSub(actorSub)
        .entityType(MaterialExchangeInterestRegisteredEvent.ENTITY_TYPE)
        .entityId(offerId)
        .contextRecipientUserIds(interested)
        .renderParams(params)
        .resolvesNotificationTypes(Set.of(NotificationType.MATERIAL_EXCHANGE_INTEREST_REGISTERED))
        .build();
  }

  /**
   * A request was withdrawn; the members who signalled they can supply it hear it and the owner's
   * fulfilment notices for the request go.
   *
   * @param requestId the request
   * @param item the requested material's or item's name
   * @param signallers the members who signalled they can supply it
   * @param actorSub the member who withdrew it, or {@code null}
   * @return the event
   */
  @NotNull
  public static NoticeEvent requestUnavailable(
      @NotNull UUID requestId,
      @NotNull String item,
      @NotNull Set<UUID> signallers,
      @Nullable UUID actorSub) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("item", item);
    return NoticeEvent.builder()
        .eventType(NotificationEventType.MATERIAL_REQUEST_UNAVAILABLE)
        .actorSub(actorSub)
        .entityType(MaterialRequestFulfillmentSignalledEvent.ENTITY_TYPE)
        .entityId(requestId)
        .contextRecipientUserIds(signallers)
        .renderParams(params)
        .resolvesNotificationTypes(Set.of(NotificationType.MATERIAL_REQUEST_FULFILLMENT_SIGNALLED))
        .build();
  }
}
