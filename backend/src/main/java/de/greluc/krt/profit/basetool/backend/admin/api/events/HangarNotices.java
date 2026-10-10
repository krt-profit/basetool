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

package de.greluc.krt.profit.basetool.backend.admin.api.events;

import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.ActorRef;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The notices that tell a member somebody else changed their hangar or blueprints in bulk or on
 * their behalf (REQ-HANGAR-007, -008), built from scalars so they can be delivered after the
 * commit.
 */
public final class HangarNotices {

  /** Loose entity-type tag of the notices about one mission. */
  public static final String MISSION_ENTITY_TYPE = "MISSION";

  /** Loose entity-type tag of the hangar notices; the entity id is the member. */
  public static final String HANGAR_ENTITY_TYPE = "HANGAR";

  /** Loose entity-type tag of the blueprint notices; the entity id is the member. */
  public static final String BLUEPRINT_ENTITY_TYPE = "PERSONAL_BLUEPRINTS";

  private HangarNotices() {}

  private static NoticeEvent.NoticeEventBuilder builder(
      NotificationEventType type, String entityType, UUID memberId, ActorRef actor) {
    return NoticeEvent.builder()
        .eventType(type)
        .actorSub(actor.id())
        .entityType(entityType)
        .entityId(memberId)
        .contextRecipientUserId(memberId);
  }

  /**
   * A ship assigned to a unit of a mission that is not finished was deleted.
   *
   * @param missionId the mission, the {@code MISSION_LEADERSHIP} selector input
   * @param responsibleId the unit's responsible member, or {@code null}
   * @param shipType the ship's type name (never the ship's own name)
   * @param missionName the mission's name
   * @param unitName the unit's name
   * @param actor who deleted the ship
   * @return the event
   */
  @NotNull
  public static NoticeEvent shipDeleted(
      @NotNull UUID missionId,
      @Nullable UUID responsibleId,
      @Nullable String shipType,
      @Nullable String missionName,
      @Nullable String unitName,
      @NotNull ActorRef actor) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("shipType", name(shipType));
    params.put("mission", name(missionName));
    params.put("unit", name(unitName));
    return NoticeEvent.builder()
        .eventType(NotificationEventType.HANGAR_SHIP_DELETED_FROM_MISSION)
        .actorSub(actor.id())
        .entityType(MISSION_ENTITY_TYPE)
        .entityId(missionId)
        .contextMissionId(missionId)
        .contextRecipientUserId(responsibleId)
        .renderParams(params)
        .build();
  }

  private static String name(@Nullable String value) {
    return value == null || value.isBlank() ? ActorRef.UNKNOWN_NAME : value.trim();
  }

  /**
   * The fitted marks of a member's ships were reset in bulk.
   *
   * @param memberId the ships' owner, the single recipient
   * @param count how many of their ships were reset
   * @param actor who reset them
   * @return the event
   */
  @NotNull
  public static NoticeEvent fittedReset(
      @NotNull UUID memberId, long count, @NotNull ActorRef actor) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("count", Long.toString(count));
    return builder(
            NotificationEventType.HANGAR_FITTED_RESET_FOR_OWNER,
            HANGAR_ENTITY_TYPE,
            memberId,
            actor)
        .renderParams(params)
        .build();
  }

  /**
   * An admin added, changed or deleted a ship in a member's hangar.
   *
   * @param memberId the hangar's owner, the single recipient
   * @param changeCode {@code ADDED}, {@code UPDATED} or {@code DELETED}
   * @param shipType the ship's type name
   * @param actor the admin
   * @return the event
   */
  @NotNull
  public static NoticeEvent hangarChanged(
      @NotNull UUID memberId,
      @NotNull String changeCode,
      @NotNull String shipType,
      @NotNull ActorRef actor) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("actor", actor.name());
    params.put("changeCode", changeCode);
    params.put("shipType", shipType);
    return builder(
            NotificationEventType.HANGAR_CHANGED_BY_ADMIN, HANGAR_ENTITY_TYPE, memberId, actor)
        .renderParams(params)
        .build();
  }

  /**
   * An admin added, changed, deleted or imported blueprints of a member.
   *
   * @param memberId the blueprints' owner, the single recipient
   * @param changeCode {@code ADDED}, {@code UPDATED}, {@code DELETED} or {@code IMPORTED}
   * @param subject the blueprint's name, or a count such as {@code 5×}
   * @param actor the admin
   * @return the event
   */
  @NotNull
  public static NoticeEvent blueprintsChanged(
      @NotNull UUID memberId,
      @NotNull String changeCode,
      @NotNull String subject,
      @NotNull ActorRef actor) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("actor", actor.name());
    params.put("changeCode", changeCode);
    params.put("subject", subject);
    return builder(
            NotificationEventType.BLUEPRINT_CHANGED_BY_ADMIN,
            BLUEPRINT_ENTITY_TYPE,
            memberId,
            actor)
        .renderParams(params)
        .build();
  }

  /**
   * An admin cleared the removable blueprints of every member; this member lost some.
   *
   * @param memberId the blueprints' owner, the single recipient
   * @param count how many blueprints they lost
   * @param actor the admin
   * @return the event
   */
  @NotNull
  public static NoticeEvent blueprintsPurged(
      @NotNull UUID memberId, long count, @NotNull ActorRef actor) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("actor", actor.name());
    params.put("count", Long.toString(count));
    return builder(
            NotificationEventType.BLUEPRINT_PURGED_BY_ADMIN, BLUEPRINT_ENTITY_TYPE, memberId, actor)
        .renderParams(params)
        .build();
  }
}
