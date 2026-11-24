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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import com.google.errorprone.annotations.Immutable;
import de.greluc.krt.profit.basetool.backend.exchange.api.events.ExchangeNotices;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The notice one {@link ExchangeSwitch} publishes when an admin flips it (REQ-XCH-041,
 * REQ-BXCH-006, REQ-OXCH-009), with the fixed {@link ExchangeNotices#SWITCH_ENTITY_ID} as its
 * entity id.
 *
 * @param switchedOn the event of switching on
 * @param switchedOff the event of switching off
 * @param entityType the loose entity-type tag that tells the switches apart
 * @param supersededOn the notice type of switching on that every flip supersedes, or {@code null}
 *     when switching on leaves no notice
 * @param supersededOff the notice type of switching off that every flip supersedes
 * @param switchCode the {@link ExchangeNotices#SWITCH_CODE} the notice and the audit details name
 *     the switch by, or {@code null} for a switch that carries none
 * @param offReachesEveryInstallation whether switching off concerns the holders of every
 *     installation
 */
@Immutable
record ExchangeSwitchNotice(
    @NotNull NotificationEventType switchedOn,
    @NotNull NotificationEventType switchedOff,
    @NotNull String entityType,
    @Nullable NotificationType supersededOn,
    @NotNull NotificationType supersededOff,
    @Nullable String switchCode,
    boolean offReachesEveryInstallation) {

  /**
   * Builds the notice of one flip.
   *
   * @param on the switch's new state
   * @param actorSub the acting admin, or {@code null}
   * @return the event to publish
   */
  @NotNull
  NoticeEvent flipped(boolean on, @Nullable UUID actorSub) {
    return NoticeEvent.builder()
        .eventType(on ? switchedOn : switchedOff)
        .actorSub(actorSub)
        .entityType(entityType)
        .entityId(ExchangeNotices.SWITCH_ENTITY_ID)
        .contextAllExchangeClients(!on && offReachesEveryInstallation)
        .renderParams(
            switchCode == null ? Map.of() : Map.of(ExchangeNotices.SWITCH_CODE, switchCode))
        .resolvesNotificationTypes(
            supersededOn == null ? Set.of(supersededOff) : Set.of(supersededOn, supersededOff))
        .build();
  }
}
