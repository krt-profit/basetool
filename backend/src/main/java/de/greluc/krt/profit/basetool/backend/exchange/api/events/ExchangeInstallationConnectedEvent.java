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
 * Domain event published when an exchange client is first seen with a new installation of a member
 * (REQ-XCH-032); the default rule notifies that member, so a connection they did not make is
 * noticed.
 *
 * <p>Only the registry's display name rides the event: the client-supplied label arrives later and
 * could pose as the Basetool, so it never enters a notification.
 *
 * @param userId the member whose account the installation connected to, the single recipient
 * @param installationId the new installation
 * @param clientName the registry display name of the client
 */
public record ExchangeInstallationConnectedEvent(
    @NotNull UUID userId, @NotNull UUID installationId, @NotNull String clientName)
    implements NotificationEvent {

  /** Loose entity-type tag stored on the produced notifications. */
  public static final String ENTITY_TYPE = "EXCHANGE_INSTALLATION";

  @NotNull
  @Override
  public NotificationEventType eventType() {
    return NotificationEventType.EXCHANGE_INSTALLATION_CONNECTED;
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
    return installationId;
  }

  @NotNull
  @Unmodifiable
  @Override
  public Map<String, String> renderParams() {
    return Map.of("client", clientName);
  }

  /**
   * The single recipient the {@code EVENT_RECIPIENT} selector resolves to — the connected member.
   *
   * @return the member's id
   */
  @Override
  public UUID contextRecipientUserId() {
    return userId;
  }
}
