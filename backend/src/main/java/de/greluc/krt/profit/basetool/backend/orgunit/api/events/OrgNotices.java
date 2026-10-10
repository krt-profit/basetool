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

package de.greluc.krt.profit.basetool.backend.orgunit.api.events;

import de.greluc.krt.profit.basetool.backend.model.NotificationContextRole;
import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import de.greluc.krt.profit.basetool.backend.notification.api.events.OrgUnitRef;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The organisation notices (REQ-ORG-029, -030), built from scalars so they can be delivered after
 * the commit.
 */
public final class OrgNotices {

  /** Loose entity-type tag of the notices about one member; the entity id is the member. */
  public static final String MEMBER_ENTITY_TYPE = "ORG_UNIT_MEMBER";

  private OrgNotices() {}

  /**
   * A leadership seat was filled or vacated and the member's OFFICER role does not fit; replaces
   * the member's earlier mismatch notice.
   *
   * @param userId the member
   * @param member the member's name
   * @param unit the unit's shorthand or name
   * @param seatCode {@code APPOINTED} or {@code REMOVED}
   * @param rankCode the seat's {@code MembershipRole} name
   * @param mismatchCode {@code MISSING_OFFICER} or {@code SURPLUS_OFFICER}
   * @param actorSub the member who changed the seat, or {@code null}
   * @return the event
   */
  @NotNull
  public static NoticeEvent leadershipRoleMismatch(
      @NotNull UUID userId,
      @NotNull String member,
      @NotNull String unit,
      @NotNull String seatCode,
      @NotNull String rankCode,
      @NotNull String mismatchCode,
      @Nullable UUID actorSub) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("member", member);
    params.put("unit", unit);
    params.put("seatCode", seatCode);
    params.put("rankCode", rankCode);
    params.put("mismatchCode", mismatchCode);
    return NoticeEvent.builder()
        .eventType(NotificationEventType.ORG_LEADERSHIP_ROLE_MISMATCH)
        .actorSub(actorSub)
        .entityType(MEMBER_ENTITY_TYPE)
        .entityId(userId)
        .renderParams(params)
        .resolvesNotificationTypes(Set.of(NotificationType.ORG_LEADERSHIP_ROLE_MISMATCH))
        .build();
  }

  /**
   * The member's roles fit their seats; the mismatch notice goes.
   *
   * @param userId the member
   * @return the event
   */
  @NotNull
  public static NoticeEvent leadershipRolesAgree(@NotNull UUID userId) {
    return NoticeEvent.builder()
        .eventType(NotificationEventType.ORG_LEADERSHIP_ROLE_MISMATCH_CLEARED)
        .entityType(MEMBER_ENTITY_TYPE)
        .entityId(userId)
        .resolvesNotificationTypes(Set.of(NotificationType.ORG_LEADERSHIP_ROLE_MISMATCH))
        .build();
  }

  /**
   * A member left the organisation; the leadership of one of their units hears it.
   *
   * @param userId the departed member, kept out of the recipients
   * @param member the member's name
   * @param unit the notified unit
   * @param unitName the unit's shorthand or name
   * @param reasonCode how the departure showed
   * @param seatVacant whether a leadership seat of the member became vacant
   * @return the event
   */
  @NotNull
  public static NoticeEvent memberDeparted(
      @NotNull UUID userId,
      @NotNull String member,
      @NotNull OrgUnitRef unit,
      @NotNull String unitName,
      @NotNull String reasonCode,
      boolean seatVacant) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("member", member);
    params.put("unit", unitName);
    params.put("reasonCode", reasonCode);
    params.put("vacancyCode", seatVacant ? "YES" : "NO");
    return NoticeEvent.builder()
        .eventType(NotificationEventType.ORG_MEMBER_DEPARTED)
        .actorSub(userId)
        .entityType(MEMBER_ENTITY_TYPE)
        .entityId(userId)
        .contextOrgUnits(Map.of(NotificationContextRole.RESPONSIBLE, unit))
        .renderParams(params)
        .build();
  }
}
