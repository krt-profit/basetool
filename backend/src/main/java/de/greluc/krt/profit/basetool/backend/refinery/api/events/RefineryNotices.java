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

package de.greluc.krt.profit.basetool.backend.refinery.api.events;

import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.ActorRef;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The notification events of the refinery module (REQ-REFINERY-023, -024), built from scalars so
 * they can be delivered after the commit.
 */
public final class RefineryNotices {

  /** Loose entity-type tag stored on every refinery notification. */
  public static final String ENTITY_TYPE = "REFINERY_ORDER";

  private static final String UNKNOWN = ActorRef.UNKNOWN_NAME;

  private RefineryNotices() {}

  /**
   * The short form of an order id the notices name it by.
   *
   * @param orderId the order
   * @return the first eight characters of its id
   */
  @NotNull
  public static String shortId(@NotNull UUID orderId) {
    return orderId.toString().substring(0, 8);
  }

  private static String text(@Nullable String value) {
    return value == null || value.isBlank() ? UNKNOWN : value.trim();
  }

  /**
   * An order's run has ended.
   *
   * @param orderId the order
   * @param ownerId the owner, the single recipient
   * @param location the refinery's location name
   * @param outputs the pre-worded, language-neutral list of output materials and SCU
   * @return the event
   */
  @NotNull
  public static NoticeEvent ready(
      @NotNull UUID orderId,
      @NotNull UUID ownerId,
      @Nullable String location,
      @Nullable String outputs) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("order", shortId(orderId));
    params.put("location", text(location));
    params.put("outputs", text(outputs));
    return NoticeEvent.builder()
        .eventType(NotificationEventType.REFINERY_ORDER_READY)
        .entityType(ENTITY_TYPE)
        .entityId(orderId)
        .contextRecipientUserId(ownerId)
        .renderParams(params)
        .resolvesNotificationTypes(Set.of(NotificationType.REFINERY_ORDER_READY))
        .build();
  }

  /**
   * An order was stored, cancelled or got a new run time; its collect-me notice goes.
   *
   * @param orderId the order
   * @return the event
   */
  @NotNull
  public static NoticeEvent readyCleared(@NotNull UUID orderId) {
    return NoticeEvent.builder()
        .eventType(NotificationEventType.REFINERY_ORDER_READY_CLEARED)
        .entityType(ENTITY_TYPE)
        .entityId(orderId)
        .resolvesNotificationTypes(Set.of(NotificationType.REFINERY_ORDER_READY))
        .build();
  }

  /**
   * Somebody else changed, cancelled or stored an order, or booked its yield onto a member.
   *
   * @param orderId the order
   * @param recipientId the owner or the member the yield was booked onto
   * @param location the refinery's location name
   * @param changeCode {@code UPDATED}, {@code CANCELED}, {@code STORED} or {@code STORED_TO_YOU}
   * @param actor who did it
   * @return the event
   */
  @NotNull
  public static NoticeEvent changedByOther(
      @NotNull UUID orderId,
      @NotNull UUID recipientId,
      @Nullable String location,
      @NotNull String changeCode,
      @NotNull ActorRef actor) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("order", shortId(orderId));
    params.put("location", text(location));
    params.put("changeCode", changeCode);
    params.put("actor", text(actor.name()));
    return NoticeEvent.builder()
        .eventType(NotificationEventType.REFINERY_ORDER_CHANGED_BY_OTHER)
        .actorSub(actor.id())
        .entityType(ENTITY_TYPE)
        .entityId(orderId)
        .contextRecipientUserId(recipientId)
        .renderParams(params)
        .build();
  }
}
