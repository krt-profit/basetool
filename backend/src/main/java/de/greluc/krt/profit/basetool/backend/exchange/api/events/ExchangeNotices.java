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

package de.greluc.krt.profit.basetool.backend.exchange.api.events;

import de.greluc.krt.profit.basetool.backend.notification.api.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The notices that tell the holders of a connected application's installations what an admin did to
 * it in the registry (REQ-XCH-040, -041), built from scalars so they can be delivered after the
 * commit, and the fixed entity id and coded render parameter of every switch notice. They name the
 * registry's display name, never the application's own label.
 */
public final class ExchangeNotices {

  /**
   * Loose entity-type tag of the notices about one registry client; the entity id is the client.
   */
  public static final String CLIENT_ENTITY_TYPE = "EXCHANGE_CLIENT";

  /** The fixed entity id of every switch notice; its entity type tells the switches apart. */
  public static final UUID SWITCH_ENTITY_ID = new UUID(0L, 1L);

  /**
   * The coded render parameter naming the job-order switch a notice is about (REQ-NOTIF-028), with
   * the value {@value #FAMILY_SWITCH} or {@value #WRITES_SWITCH}.
   */
  public static final String SWITCH_CODE = "switchCode";

  /** The {@link #SWITCH_CODE} of the job-order family switch. */
  public static final String FAMILY_SWITCH = "family";

  /** The {@link #SWITCH_CODE} of the job-order writes switch. */
  public static final String WRITES_SWITCH = "writes";

  private ExchangeNotices() {}

  private static NoticeEvent.NoticeEventBuilder client(
      NotificationEventType type, UUID clientId, @Nullable UUID actorSub) {
    return NoticeEvent.builder()
        .eventType(type)
        .actorSub(actorSub)
        .entityType(CLIENT_ENTITY_TYPE)
        .entityId(clientId)
        .contextExchangeClientId(clientId);
  }

  private static Map<String, String> app(String displayName) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("app", displayName);
    return params;
  }

  /**
   * A connected application was suspended.
   *
   * @param clientId the registry client
   * @param displayName its registry display name
   * @param actorSub the admin, or {@code null}
   * @return the event
   */
  @NotNull
  public static NoticeEvent clientSuspended(
      @NotNull UUID clientId, @NotNull String displayName, @Nullable UUID actorSub) {
    return client(NotificationEventType.EXCHANGE_CLIENT_SUSPENDED, clientId, actorSub)
        .renderParams(app(displayName))
        .resolvesNotificationTypes(
            Set.of(
                NotificationType.EXCHANGE_CLIENT_SUSPENDED,
                NotificationType.EXCHANGE_CLIENT_ACTIVATED))
        .build();
  }

  /**
   * A suspended connected application is active again; the suspension notices go.
   *
   * @param clientId the registry client
   * @param displayName its registry display name
   * @param actorSub the admin, or {@code null}
   * @return the event
   */
  @NotNull
  public static NoticeEvent clientActivated(
      @NotNull UUID clientId, @NotNull String displayName, @Nullable UUID actorSub) {
    return client(NotificationEventType.EXCHANGE_CLIENT_ACTIVATED, clientId, actorSub)
        .renderParams(app(displayName))
        .resolvesNotificationTypes(
            Set.of(
                NotificationType.EXCHANGE_CLIENT_SUSPENDED,
                NotificationType.EXCHANGE_CLIENT_ACTIVATED))
        .build();
  }

  /**
   * The minimum client version of a connected application was raised.
   *
   * @param clientId the registry client
   * @param displayName its registry display name
   * @param version the new minimum version
   * @param actorSub the admin, or {@code null}
   * @return the event
   */
  @NotNull
  public static NoticeEvent updateRequired(
      @NotNull UUID clientId,
      @NotNull String displayName,
      @NotNull String version,
      @Nullable UUID actorSub) {
    Map<String, String> params = app(displayName);
    params.put("version", version);
    return client(NotificationEventType.EXCHANGE_CLIENT_UPDATE_REQUIRED, clientId, actorSub)
        .renderParams(params)
        .build();
  }

  /**
   * Capabilities were removed from a connected application.
   *
   * @param clientId the registry client
   * @param displayName its registry display name
   * @param capabilities the removed scopes, comma-separated
   * @param actorSub the admin, or {@code null}
   * @return the event
   */
  @NotNull
  public static NoticeEvent capabilityRemoved(
      @NotNull UUID clientId,
      @NotNull String displayName,
      @NotNull String capabilities,
      @Nullable UUID actorSub) {
    Map<String, String> params = app(displayName);
    params.put("capabilities", capabilities);
    return client(NotificationEventType.EXCHANGE_CLIENT_CAPABILITY_REMOVED, clientId, actorSub)
        .renderParams(params)
        .build();
  }
}
