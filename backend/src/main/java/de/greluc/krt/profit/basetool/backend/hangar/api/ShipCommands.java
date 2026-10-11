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

package de.greluc.krt.profit.basetool.backend.hangar.api;

import de.greluc.krt.profit.basetool.backend.model.Ship;
import de.greluc.krt.profit.basetool.backend.model.dto.ShipRequestDto;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * The hangar's ship writes for other modules (plan §5.3): each joins the caller's transaction and
 * records its {@code HANGAR} audit event (REQ-HANGAR-004).
 */
public interface ShipCommands {

  /**
   * Adds a ship a connected application creates for the member (REQ-XCH-017), stamped with the
   * member's only org unit or none; audited as {@code HANGAR_SHIP_CREATED}.
   *
   * @param member the owning member
   * @param ship the ship payload; its org unit is ignored
   * @return the persisted ship
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the member does
   *     not exist
   * @throws de.greluc.krt.profit.basetool.backend.exception.BadRequestException when the ship type
   *     or location id is missing or invalid
   */
  @NotNull
  Ship addShipForClient(@NotNull UUID member, @NotNull ShipRequestDto ship);

  /**
   * Updates one of the member's ships, checking the version when the payload carries one; audited
   * as {@code HANGAR_SHIP_UPDATED} when a field changed.
   *
   * @param member the owning member
   * @param shipId the ship
   * @param ship the update payload
   * @return the persisted ship
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the ship does
   *     not exist
   * @throws org.springframework.security.access.AccessDeniedException when the member does not own
   *     the ship
   */
  @NotNull
  Ship updateShip(@NotNull UUID member, @NotNull UUID shipId, @NotNull ShipRequestDto ship);

  /**
   * Deletes one of the member's ships after detaching it from its mission units; audited as {@code
   * HANGAR_SHIP_DELETED}.
   *
   * @param member the owning member
   * @param shipId the ship
   * @return how many mission units the ship was detached from
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the ship does
   *     not exist
   * @throws org.springframework.security.access.AccessDeniedException when the member does not own
   *     the ship
   */
  int deleteShip(@NotNull UUID member, @NotNull UUID shipId);
}
