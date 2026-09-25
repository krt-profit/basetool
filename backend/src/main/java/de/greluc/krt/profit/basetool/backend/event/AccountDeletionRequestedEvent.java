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
 * Domain event published when a member raises an Art. 17 erasure request (REQ-SEC-061); the default
 * rule notifies every admin.
 *
 * <p>Carries no e-mail address, Discord id or the member's reasoning.
 *
 * @param userId the requesting member's id, also the notification's loose entity id
 * @param handle the requesting member's effective name, for rendering; may be {@code null}
 */
public record AccountDeletionRequestedEvent(UUID userId, @Nullable String handle)
    implements NotificationEvent {

  /** Loose entity-type tag stored on the produced notifications for deep-linking. */
  public static final String ENTITY_TYPE = "DELETION_REQUEST";

  @NotNull
  @Override
  public NotificationEventType eventType() {
    return NotificationEventType.ACCOUNT_DELETION_REQUESTED;
  }

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
    if (handle != null && !handle.isBlank()) {
      params.put("handle", handle);
    }
    return params;
  }
}
