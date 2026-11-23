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
 * commit. They name the registry's display name, never the application's own label.
 */
public final class ExchangeNotices {

  /**
   * Loose entity-type tag of the notices about one registry client; the entity id is the client.
   */
  public static final String CLIENT_ENTITY_TYPE = "EXCHANGE_CLIENT";

  /** Loose entity-type tag of the notices about the global switch. */
  public static final String SWITCH_ENTITY_TYPE = "EXCHANGE_SWITCH";

  /** Loose entity-type tag of the notices about the bank switch. */
  public static final String BANK_SWITCH_ENTITY_TYPE = "EXCHANGE_BANK_SWITCH";

  /** Loose entity-type tag of the notices about the job-order family switch. */
  public static final String JOB_ORDER_SWITCH_ENTITY_TYPE = "EXCHANGE_JOB_ORDER_SWITCH";

  /** Loose entity-type tag of the notices about the job-order writes switch. */
  public static final String JOB_ORDER_WRITES_SWITCH_ENTITY_TYPE =
      "EXCHANGE_JOB_ORDER_WRITES_SWITCH";

  /** The fixed entity id of every switch; the entity type tells the switches apart. */
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

  /** The notice types every job-order switch notice supersedes for its own switch. */
  private static final Set<NotificationType> JOB_ORDER_SWITCH_NOTICES =
      Set.of(
          NotificationType.JOB_ORDER_EXCHANGE_SWITCHED_ON,
          NotificationType.JOB_ORDER_EXCHANGE_SWITCHED_OFF);

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

  /**
   * The whole exchange was switched off; the holders of every installation hear it.
   *
   * @param actorSub the admin, or {@code null}
   * @return the event
   */
  @NotNull
  public static NoticeEvent switchedOff(@Nullable UUID actorSub) {
    return NoticeEvent.builder()
        .eventType(NotificationEventType.EXCHANGE_SWITCHED_OFF)
        .actorSub(actorSub)
        .entityType(SWITCH_ENTITY_TYPE)
        .entityId(SWITCH_ENTITY_ID)
        .contextAllExchangeClients(true)
        .resolvesNotificationTypes(Set.of(NotificationType.EXCHANGE_SWITCHED_OFF))
        .build();
  }

  /**
   * The exchange was switched on again; the switched-off notices go.
   *
   * @param actorSub the admin, or {@code null}
   * @return the event
   */
  @NotNull
  public static NoticeEvent switchedOn(@Nullable UUID actorSub) {
    return NoticeEvent.builder()
        .eventType(NotificationEventType.EXCHANGE_SWITCHED_ON)
        .actorSub(actorSub)
        .entityType(SWITCH_ENTITY_TYPE)
        .entityId(SWITCH_ENTITY_ID)
        .resolvesNotificationTypes(Set.of(NotificationType.EXCHANGE_SWITCHED_OFF))
        .build();
  }

  /**
   * The bank was switched on for connected applications (REQ-BXCH-006); the earlier bank switch
   * notices go.
   *
   * @param actorSub the admin, or {@code null}
   * @return the event
   */
  @NotNull
  public static NoticeEvent bankSwitchedOn(@Nullable UUID actorSub) {
    return bankSwitch(NotificationEventType.BANK_EXCHANGE_SWITCHED_ON, actorSub);
  }

  /**
   * The bank was switched off for connected applications (REQ-BXCH-006); the earlier bank switch
   * notices go.
   *
   * @param actorSub the admin, or {@code null}
   * @return the event
   */
  @NotNull
  public static NoticeEvent bankSwitchedOff(@Nullable UUID actorSub) {
    return bankSwitch(NotificationEventType.BANK_EXCHANGE_SWITCHED_OFF, actorSub);
  }

  private static NoticeEvent bankSwitch(NotificationEventType type, @Nullable UUID actorSub) {
    return NoticeEvent.builder()
        .eventType(type)
        .actorSub(actorSub)
        .entityType(BANK_SWITCH_ENTITY_TYPE)
        .entityId(SWITCH_ENTITY_ID)
        .resolvesNotificationTypes(
            Set.of(
                NotificationType.BANK_EXCHANGE_SWITCHED_ON,
                NotificationType.BANK_EXCHANGE_SWITCHED_OFF))
        .build();
  }

  /**
   * Job orders were switched on for connected applications (REQ-OXCH-009); the earlier notices
   * about the family switch go.
   *
   * @param actorSub the admin, or {@code null}
   * @return the event
   */
  @NotNull
  public static NoticeEvent jobOrdersSwitchedOn(@Nullable UUID actorSub) {
    return jobOrderSwitch(
        NotificationEventType.JOB_ORDER_EXCHANGE_SWITCHED_ON,
        JOB_ORDER_SWITCH_ENTITY_TYPE,
        FAMILY_SWITCH,
        actorSub);
  }

  /**
   * Job orders were switched off for connected applications (REQ-OXCH-009); the earlier notices
   * about the family switch go.
   *
   * @param actorSub the admin, or {@code null}
   * @return the event
   */
  @NotNull
  public static NoticeEvent jobOrdersSwitchedOff(@Nullable UUID actorSub) {
    return jobOrderSwitch(
        NotificationEventType.JOB_ORDER_EXCHANGE_SWITCHED_OFF,
        JOB_ORDER_SWITCH_ENTITY_TYPE,
        FAMILY_SWITCH,
        actorSub);
  }

  /**
   * Job-order writes were switched on for connected applications (REQ-OXCH-009); the earlier
   * notices about the writes switch go.
   *
   * @param actorSub the admin, or {@code null}
   * @return the event
   */
  @NotNull
  public static NoticeEvent jobOrderWritesSwitchedOn(@Nullable UUID actorSub) {
    return jobOrderSwitch(
        NotificationEventType.JOB_ORDER_EXCHANGE_SWITCHED_ON,
        JOB_ORDER_WRITES_SWITCH_ENTITY_TYPE,
        WRITES_SWITCH,
        actorSub);
  }

  /**
   * Job-order writes were switched off for connected applications (REQ-OXCH-009); the earlier
   * notices about the writes switch go.
   *
   * @param actorSub the admin, or {@code null}
   * @return the event
   */
  @NotNull
  public static NoticeEvent jobOrderWritesSwitchedOff(@Nullable UUID actorSub) {
    return jobOrderSwitch(
        NotificationEventType.JOB_ORDER_EXCHANGE_SWITCHED_OFF,
        JOB_ORDER_WRITES_SWITCH_ENTITY_TYPE,
        WRITES_SWITCH,
        actorSub);
  }

  private static NoticeEvent jobOrderSwitch(
      NotificationEventType type, String entityType, String switchCode, @Nullable UUID actorSub) {
    return NoticeEvent.builder()
        .eventType(type)
        .actorSub(actorSub)
        .entityType(entityType)
        .entityId(SWITCH_ENTITY_ID)
        .renderParams(Map.of(SWITCH_CODE, switchCode))
        .resolvesNotificationTypes(JOB_ORDER_SWITCH_NOTICES)
        .build();
  }
}
