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

import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The hangar's ship writes for other modules (plan §5.3): each joins the caller's transaction and
 * records its {@code HANGAR} audit event (REQ-HANGAR-004).
 */
public interface ShipCommands {

  /**
   * Adds a ship a connected application creates for the member without naming a unit (REQ-XCH-017),
   * stamped with the member's only org unit or none; audited as {@code HANGAR_SHIP_CREATED}.
   *
   * @param member the owning member
   * @param ship the ship payload; its org unit is ignored
   * @return the persisted ship
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the member does not
   *     exist
   * @throws de.greluc.krt.profit.basetool.backend.kernel.BadRequestException when the ship type or
   *     location id is missing or invalid
   */
  @NotNull
  Ship addShipForClient(@NotNull UUID member, @NotNull ShipRequestDto ship);

  /**
   * Adds a ship a connected application creates for the member in the org unit it names
   * (REQ-XCH-017); audited as {@code HANGAR_SHIP_CREATED}.
   *
   * @param member the owning member
   * @param ship the ship payload; its org unit is ignored
   * @param orgUnitId one of the member's direct memberships, or {@code null} for none
   * @return the persisted ship
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the member does not
   *     exist
   * @throws de.greluc.krt.profit.basetool.backend.kernel.BadRequestException when the unit is not a
   *     direct membership of the member, or the ship type or location id is missing or invalid
   */
  @NotNull
  Ship addShipForClientInOrgUnit(
      @NotNull UUID member, @NotNull ShipRequestDto ship, @Nullable UUID orgUnitId);

  /**
   * Moves one of the member's ships to another of the member's direct memberships or to none,
   * behind the ship's unit counter and without touching its version (REQ-HANGAR-009, ADR-0258);
   * audited as {@code HANGAR_SHIP_ORG_UNIT_CHANGED} when the unit changed.
   *
   * @param member the owning member
   * @param shipId the ship
   * @param orgUnitId the new org unit, or {@code null} for none
   * @param orgUnitVersion the unit counter the caller last saw
   * @return the ship as it is after the change
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the ship does not
   *     exist
   * @throws org.springframework.security.access.AccessDeniedException when the member does not own
   *     the ship
   * @throws de.greluc.krt.profit.basetool.backend.kernel.BadRequestException when the unit is not a
   *     direct membership of the member
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the counter is
   *     stale
   */
  @NotNull
  Ship changeShipOrgUnit(
      @NotNull UUID member, @NotNull UUID shipId, @Nullable UUID orgUnitId, long orgUnitVersion);

  /**
   * Updates one of the member's ships, checking the version when the payload carries one; audited
   * as {@code HANGAR_SHIP_UPDATED} when a field changed.
   *
   * @param member the owning member
   * @param shipId the ship
   * @param ship the update payload
   * @return the persisted ship
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the ship does not
   *     exist
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
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the ship does not
   *     exist
   * @throws org.springframework.security.access.AccessDeniedException when the member does not own
   *     the ship
   */
  int deleteShip(@NotNull UUID member, @NotNull UUID shipId);
}
