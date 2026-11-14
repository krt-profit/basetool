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

import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRelocationLock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Holds the hangar's ship writes against the relocation lock (REQ-PATCH-023): an edit, a unit or
 * origin change or a delete of a ship from before the open home-location phase's live time is
 * refused for anyone the lock refuses.
 */
@Component
@RequiredArgsConstructor
class HangarRelocationGuard {

  private final ShipRelocationLock lock;
  private final HangarRelocationRepository relocations;

  /**
   * Refuses the write when one of the ships is locked for the caller.
   *
   * @param shipIds the ships the write changes
   * @throws RuntimeException the lock's {@code 409 RELOCATION_PENDING} problem
   */
  void requireWritable(@NotNull Collection<UUID> shipIds) {
    if (shipIds.isEmpty()) {
      return;
    }
    Optional<Instant> liveAt = lock.openPhaseLiveAt();
    if (liveAt.isEmpty()) {
      return;
    }
    List<UUID> owners = relocations.findOwnersOfShipsBefore(shipIds, liveAt.get());
    if (!owners.isEmpty()) {
      lock.requireWritable(owners);
    }
  }
}
