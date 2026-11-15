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
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipDeletionObserver;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The ordinary deletion path of ships: whatever references a ship lets go of it first through
 * {@link ShipDeletionObserver}, so a mission unit is released with its notice, then the ships go
 * and the unit hangar overviews refresh after the commit. The caller records its own audit event.
 */
@Component
@RequiredArgsConstructor
class HangarShipRemoval {

  private final ShipDeletionObserver shipDeletionObserver;
  private final EntityManager entityManager;
  private final ShipRepository shipRepository;
  private final HangarUnitLiveSync unitLiveSync;

  /**
   * Deletes the given ships inside the caller's transaction.
   *
   * @param ships the managed ships to delete
   * @return how many mission units the ships were detached from
   */
  int remove(@NotNull List<Ship> ships) {
    int detached = 0;
    for (Ship ship : ships) {
      detached += shipDeletionObserver.beforeShipDelete(ship.getId());
    }
    entityManager.flush();
    shipRepository.deleteAll(ships);
    unitLiveSync.shipsChanged(ships.stream().map(Ship::getOwningOrgUnit).toList());
    return detached;
  }
}
