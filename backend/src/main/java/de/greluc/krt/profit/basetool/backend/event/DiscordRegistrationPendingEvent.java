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

package de.greluc.krt.profit.basetool.backend.event;

import de.greluc.krt.profit.basetool.backend.model.NotificationContextRole;
import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Domain event published right after a brand-new Discord registration is persisted in the {@code
 * PENDING} state (epic #720, Track 1, REQ-NOTIF-012). The seeded default rule notifies every admin
 * so they can review and approve.
 *
 * <p>Carries only the new user's id (the notification's deep-link target) and their display
 * username for rendering — deliberately <strong>no Discord id</strong> or other PII rides the
 * event.
 *
 * @param userId the new user's id (also the notification's loose entity id, for deep-linking)
 * @param username the new user's username, for rendering the admin notification; may be {@code
 *     null}
 */
public record DiscordRegistrationPendingEvent(UUID userId, @Nullable String username)
    implements NotificationEvent {

  /** Loose entity-type tag stored on the produced notifications for deep-linking. */
  public static final String ENTITY_TYPE = "DISCORD_REGISTRATION";

  @NotNull
  @Override
  public NotificationEventType eventType() {
    return NotificationEventType.DISCORD_REGISTRATION_PENDING;
  }

  @Nullable
  @Override
  public UUID actorSub() {
    // The new (unapproved) user is not an "actor" to exclude; recipients are admins only.
    return null;
  }

  @NotNull
  @Unmodifiable
  @Override
  public Map<NotificationContextRole, OrgUnitRef> contextOrgUnits() {
    return Map.of();
  }

  @NotNull
  @Override
  public String entityType() {
    return ENTITY_TYPE;
  }

  @Override
  public UUID entityId() {
    return userId;
  }

  @NotNull
  @Override
  public Map<String, String> renderParams() {
    Map<String, String> params = new LinkedHashMap<>();
    if (username != null && !username.isBlank()) {
      params.put("username", username);
    }
    return params;
  }
}
