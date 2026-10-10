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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.model.NotificationContextRole;
import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationEvent;
import de.greluc.krt.profit.basetool.backend.notification.api.events.OrgUnitRef;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;

/** A configurable {@link NotificationEvent} for tests of the rule engine and the creation path. */
@Builder
@Getter(onMethod_ = @__(@Override))
@Accessors(fluent = true)
final class StubNotificationEvent implements NotificationEvent {

  @Builder.Default
  private final NotificationEventType eventType = NotificationEventType.JOB_ORDER_CREATED;

  private final UUID actorSub;
  @Builder.Default private final String entityType = "STUB";

  @Builder.Default
  private final UUID entityId = UUID.fromString("00000000-0000-0000-0000-00000000d001");

  @Builder.Default
  private final Map<NotificationContextRole, OrgUnitRef> contextOrgUnits = Map.of();

  @Builder.Default private final Map<String, String> renderParams = Map.of();
  @Builder.Default private final Set<UUID> contextRecipientUserIds = Set.of();
  private final UUID contextMissionId;
  private final boolean contextMissionOnlyNotCheckedIn;
  private final UUID contextExchangeClientId;
  private final boolean contextAllExchangeClients;
  @Builder.Default private final Set<NotificationType> resolvesNotificationTypes = Set.of();

  @Builder.Default
  private final Set<NotificationType> resolvesNotificationTypesForRecipients = Set.of();

  @Builder.Default private final Set<UUID> supersedeRecipients = Set.of();
}
