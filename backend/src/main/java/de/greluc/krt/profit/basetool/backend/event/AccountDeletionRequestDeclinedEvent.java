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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Domain event published when an admin refuses a member's erasure request (REQ-SEC-061); the
 * default rule notifies the requesting member.
 *
 * <p>The admin's reasoning does not ride the event; the member reads it on their profile page.
 *
 * @param userId the requesting member, who is the single recipient
 */
public record AccountDeletionRequestDeclinedEvent(UUID userId) implements NotificationEvent {

  /** Loose entity-type tag stored on the produced notifications for deep-linking. */
  public static final String ENTITY_TYPE = "DELETION_REQUEST";

  @NotNull
  @Override
  public NotificationEventType eventType() {
    return NotificationEventType.ACCOUNT_DELETION_REQUEST_DECLINED;
  }

  @Nullable
  @Override
  public UUID actorSub() {
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
  @Unmodifiable
  @Override
  public Map<String, String> renderParams() {
    return Map.of();
  }

  /**
   * The single recipient the {@code EVENT_RECIPIENT} selector resolves to — the requesting member.
   *
   * @return the requesting member's id
   */
  @Override
  public UUID contextRecipientUserId() {
    return userId;
  }

  /**
   * Clears the administrators' now-stale erasure-request notifications (REQ-NOTIF-018).
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
