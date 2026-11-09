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

package de.greluc.krt.profit.basetool.backend.identity.api.events;

import de.greluc.krt.profit.basetool.backend.notification.api.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The notice a member gets when one of their roles changes (REQ-SEC-088), built from scalars so it
 * can be delivered after the commit.
 */
public final class RoleNotices {

  /** Loose entity-type tag of the notices about one member's roles; the entity id is the member. */
  public static final String ROLES_ENTITY_TYPE = "USER_ROLES";

  /** The {@code changeCode} of an assignment. */
  public static final String CHANGE_ASSIGNED = "ASSIGNED";

  /** The {@code changeCode} of a withdrawal. */
  public static final String CHANGE_WITHDRAWN = "WITHDRAWN";

  private RoleNotices() {}

  /**
   * One of a member's roles was assigned or withdrawn.
   *
   * @param userId the member, the recipient
   * @param roleCode the role's stable code
   * @param assigned whether the role was assigned rather than withdrawn
   * @param actorSub the member who changed the role, or {@code null} for the bootstrap grant
   * @return the event
   */
  @NotNull
  public static NoticeEvent roleChanged(
      @NotNull UUID userId, @NotNull String roleCode, boolean assigned, @Nullable UUID actorSub) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("roleCode", roleCode);
    params.put("changeCode", assigned ? CHANGE_ASSIGNED : CHANGE_WITHDRAWN);
    return NoticeEvent.builder()
        .eventType(NotificationEventType.USER_ROLE_CHANGED)
        .actorSub(actorSub)
        .entityType(ROLES_ENTITY_TYPE)
        .entityId(userId)
        .contextRecipientUserId(userId)
        .renderParams(params)
        .build();
  }
}
