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
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Published when a member's Art. 17 erasure request is withdrawn or executed (REQ-SEC-061,
 * REQ-NOTIF-018).
 *
 * <p>Directed at nobody: its only effect is to clear the stale {@code ACCOUNT_DELETION_REQUESTED}
 * notifications shown to administrators. Carries only the member's id, so the after-commit listener
 * never touches a deleted entity.
 *
 * @param userId the member whose request was resolved; also the loose entity id of the superseded
 *     notifications
 */
public record AccountDeletionRequestResolvedEvent(UUID userId) implements NotificationEvent {

  /** The loose entity type both deletion-request notifications are tagged with. */
  public static final String ENTITY_TYPE = AccountDeletionRequestedEvent.ENTITY_TYPE;

  @NotNull
  @Override
  public NotificationEventType eventType() {
    return NotificationEventType.ACCOUNT_DELETION_REQUEST_RESOLVED;
  }

  /**
   * The member themselves, whether they withdrew the request or an admin carried it out.
   *
   * <p>On execution the account is already gone when the listener runs; the actor is a loose {@code
   * sub} with no foreign key.
   *
   * @return the member's id
   */
  @Override
  public UUID actorSub() {
    return userId;
  }

  @NotNull
  @Unmodifiable
  @Override
  public Map<NotificationContextRole, OrgUnitRef> contextOrgUnits() {
    return Map.of();
  }

  @Override
  public String entityType() {
    return ENTITY_TYPE;
  }

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
   * The request is settled, so the administrators' "member requests erasure" items are stale.
   *
   * @return the singleton {@link NotificationType#ACCOUNT_DELETION_REQUESTED}
   */
  @NotNull
  @Unmodifiable
  @Override
  public Set<NotificationType> resolvesNotificationTypes() {
    return Set.of(NotificationType.ACCOUNT_DELETION_REQUESTED);
  }
}
