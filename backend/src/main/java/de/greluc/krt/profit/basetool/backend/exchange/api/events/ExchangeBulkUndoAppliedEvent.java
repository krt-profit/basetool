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

import de.greluc.krt.profit.basetool.backend.model.NotificationContextRole;
import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationEvent;
import de.greluc.krt.profit.basetool.backend.notification.api.events.OrgUnitRef;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Domain event published once per member and run when an admin's bulk undo restored entries an
 * exchange client had written for that member (REQ-XCH-034); the default rule notifies the member.
 *
 * @param userId the member whose entries were restored, the single recipient
 * @param runId the bulk undo run
 * @param clientName the registry display name of the client
 * @param restored how many entries were restored
 */
public record ExchangeBulkUndoAppliedEvent(
    @NotNull UUID userId, @NotNull UUID runId, @NotNull String clientName, int restored)
    implements NotificationEvent {

  /** Loose entity-type tag stored on the produced notifications. */
  public static final String ENTITY_TYPE = "EXCHANGE_BULK_UNDO_RUN";

  @NotNull
  @Override
  public NotificationEventType eventType() {
    return NotificationEventType.EXCHANGE_BULK_UNDO_APPLIED;
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
    return runId;
  }

  @NotNull
  @Unmodifiable
  @Override
  public Map<String, String> renderParams() {
    return Map.of("client", clientName, "count", Integer.toString(restored));
  }

  /**
   * The single recipient the {@code EVENT_RECIPIENT} selector resolves to — the member.
   *
   * @return the member's id
   */
  @Override
  public UUID contextRecipientUserId() {
    return userId;
  }
}
