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

import de.greluc.krt.profit.basetool.backend.hangar.api.Ship;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRepository;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.security.access.AccessDeniedException;

/**
 * The selection of a bulk change of one member's ships (REQ-HANGAR-010, REQ-HANGAR-018): the ships
 * are locked in ascending id order and checked before the first write, so an unknown or foreign
 * ship refuses the whole selection.
 *
 * @param changing the selected ships the change applies to, locked, in ascending id order
 * @param skipped how many selected ships the change leaves as they are
 */
record BulkShipSelection(@NotNull @Unmodifiable List<Ship> changing, int skipped) {

  /**
   * Locks a member's selected ships and keeps those the change applies to.
   *
   * @param ships the ship repository, inside the caller's transaction
   * @param ownerId the member the ships must belong to
   * @param shipIds the selected ships, duplicates allowed
   * @param changes whether the change applies to a locked ship
   * @return the selection
   * @throws NotFoundException when a selected ship does not exist
   * @throws AccessDeniedException when a selected ship belongs to another member
   */
  static @NotNull BulkShipSelection lock(
      @NotNull ShipRepository ships,
      @NotNull UUID ownerId,
      @NotNull List<UUID> shipIds,
      @NotNull Predicate<Ship> changes) {
    List<UUID> distinctIds = shipIds.stream().distinct().toList();
    List<Ship> locked = ships.lockAllByIdOrderById(distinctIds);
    if (locked.size() != distinctIds.size()) {
      throw new NotFoundException("Ship not found");
    }
    locked.forEach(ship -> ShipOwnership.requireOwner(ship, ownerId));
    List<Ship> changing = locked.stream().filter(changes).toList();
    return new BulkShipSelection(changing, locked.size() - changing.size());
  }
}
