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

import de.greluc.krt.profit.basetool.backend.model.NotificationContextRole;
import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationEvent;
import de.greluc.krt.profit.basetool.backend.notification.api.events.OrgUnitRef;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Published when a pending registration leaves the admin queue — approved, rejected or deleted
 * (REQ-NOTIF-012, REQ-NOTIF-018).
 *
 * <p>Directed at nobody: its only effect is to clear every admin's {@code
 * DISCORD_REGISTRATION_PENDING} item for the registration, so the other admins are not left with a
 * decision that has already been made.
 *
 * @param userId the registration that was decided; the loose entity id of the cleared items
 * @param actorSub the admin who decided it, or {@code null} when no admin is known
 */
public record DiscordRegistrationDecidedEvent(@NotNull UUID userId, @Nullable UUID actorSub)
    implements NotificationEvent {

  @NotNull
  @Override
  public NotificationEventType eventType() {
    return NotificationEventType.DISCORD_REGISTRATION_DECIDED;
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
    return DiscordRegistrationPendingEvent.ENTITY_TYPE;
  }

  @NotNull
  @Override
  public UUID entityId() {
    return userId;
  }

  @NotNull
  @Unmodifiable
  @Override
  public Map<String, String> renderParams() {
    return Map.of();
  }

  /**
   * The registration is decided, so the admins' "registration awaits approval" items are stale.
   *
   * @return the singleton {@link NotificationType#DISCORD_REGISTRATION_PENDING}
   */
  @NotNull
  @Unmodifiable
  @Override
  public Set<NotificationType> resolvesNotificationTypes() {
    return Set.of(NotificationType.DISCORD_REGISTRATION_PENDING);
  }
}
