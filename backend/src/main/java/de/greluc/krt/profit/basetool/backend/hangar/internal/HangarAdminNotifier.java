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

package de.greluc.krt.profit.basetool.backend.hangar.internal;

import de.greluc.krt.profit.basetool.backend.admin.api.events.HangarNotices;
import de.greluc.krt.profit.basetool.backend.hangar.api.Ship;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.notification.api.events.ActorRef;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Tells a member that an admin changed their hangar (REQ-HANGAR-008), unless the admin is that
 * member; the notices go out after the commit.
 */
@Component
@RequiredArgsConstructor
public class HangarAdminNotifier {

  private final UserService userService;
  private final ApplicationEventPublisher eventPublisher;

  /**
   * Announces an admin's change of one ship.
   *
   * @param memberId the ship's owner
   * @param changeCode {@code ADDED}, {@code UPDATED}, {@code DELETED} or {@code ORG_UNIT_CHANGED}
   * @param ship the ship, named by its type only
   */
  public void shipChanged(@NotNull UUID memberId, @NotNull String changeCode, @NotNull Ship ship) {
    tell(
        memberId,
        actor ->
            HangarNotices.hangarChanged(
                memberId,
                changeCode,
                ship.getShipType() == null ? ActorRef.UNKNOWN_NAME : ship.getShipType().getName(),
                actor));
  }

  /**
   * Publishes a notice to a member about an admin's change, unless the admin is that member.
   *
   * @param memberId the member the change concerns
   * @param notice builds the notice from the acting admin
   */
  public void tell(@NotNull UUID memberId, @NotNull Function<ActorRef, NoticeEvent> notice) {
    ActorRef actor = userService.currentActor();
    if (!memberId.equals(actor.id())) {
      eventPublisher.publishEvent(notice.apply(actor));
    }
  }
}
