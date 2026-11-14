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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.hangar.api.Ship;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRelocation;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRelocationCount;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRepository;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The hangar's part of a patch change's relocation (REQ-PATCH-025, ADR-0383): the owner's ships
 * from before the cutoff take the new location on the managed rows, so each one's version advances.
 */
@Service
@RequiredArgsConstructor
class HangarShipRelocation implements ShipRelocation {

  private final ShipRepository shipRepository;
  private final HangarRelocationRepository relocations;
  private final LocationRepository locationRepository;
  private final HangarUnitLiveSync unitLiveSync;
  private final LiveSyncPublisher liveSync;

  @Override
  @Transactional(readOnly = true)
  public @NotNull @Unmodifiable List<ShipRelocationCount> countByLocation(
      @NotNull UUID owner, @NotNull Instant createdBefore) {
    return relocations.countByLocation(owner, createdBefore).stream()
        .map(group ->
            new ShipRelocationCount(
                group.getLocationId(), group.getLocationName(), group.getShipCount()))
        .toList();
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public int relocate(
      @NotNull UUID owner, @NotNull Instant createdBefore, @NotNull UUID targetLocationId) {
    Location target = locationRepository.getReferenceById(targetLocationId);
    int moved = 0;
    for (Ship ship : shipRepository.lockAllOwnedByOrderById(owner)) {
      if (HangarRelocationGuard.fromBefore(ship, createdBefore) && !isAt(ship, targetLocationId)) {
        ship.setLocation(target);
        moved++;
      }
    }
    if (moved > 0) {
      shipRepository.flush();
    }
    announceLockChange(List.of(owner));
    return moved;
  }

  @Override
  public void announceLockChange(@NotNull Collection<UUID> owners) {
    if (owners.isEmpty()) {
      return;
    }
    for (UUID owner : owners) {
      liveSync.publishAfterCommit(
          LiveSyncTopic.of(LiveSyncTopicClass.HANGAR_OWN, owner),
          List.of(HangarUnitLiveSync.SHIPS));
    }
    unitLiveSync.unitsChanged(relocations.findOrgUnitIdsOfOwners(owners));
  }

  /**
   * Tells whether a ship already lies at a location.
   *
   * @param ship the ship
   * @param locationId the location
   * @return {@code true} when the ship's location is that location
   */
  private static boolean isAt(@NotNull Ship ship, @NotNull UUID locationId) {
    return ship.getLocation() != null && locationId.equals(ship.getLocation().getId());
  }
}
