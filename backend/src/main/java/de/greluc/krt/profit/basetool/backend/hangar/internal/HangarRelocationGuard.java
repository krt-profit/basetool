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
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRelocationLock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Component;

/**
 * Holds the hangar's ship writes against the relocation lock (REQ-PATCH-023): an edit, a unit or
 * origin change or a delete of a ship from before the open home-location phase's live time is
 * refused for anyone the lock refuses, a fitted reset leaves such ships out, and the pages that
 * list other members' ships mark them.
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

  /**
   * Returns the ships among the given owners' that the caller may not write: those from before the
   * open phase's live time of an owner the lock refuses the caller for.
   *
   * @param owners the owners whose ships a bulk write would change
   * @return the locked owners with the cutoff, {@link LockedShips#NONE} when nothing is locked
   */
  @NotNull
  LockedShips lockedFor(@NotNull Collection<UUID> owners) {
    if (owners.isEmpty()) {
      return LockedShips.NONE;
    }
    Optional<Instant> liveAt = lock.openPhaseLiveAt();
    if (liveAt.isEmpty()) {
      return LockedShips.NONE;
    }
    Set<UUID> refused = lock.refusedAmong(owners);
    return refused.isEmpty() ? LockedShips.NONE : new LockedShips(refused, liveAt.get());
  }

  /**
   * Returns the ships a pending relocation locks, for the „Umzug ausstehend" marker: those from
   * before the open phase's live time of an owner who has not chosen yet.
   *
   * @param ships the listed ships, their owners readable
   * @return the ids of the locked ships, empty while no phase is open
   */
  @NotNull
  @Unmodifiable
  Set<UUID> pendingShips(@NotNull Collection<Ship> ships) {
    if (ships.isEmpty()) {
      return Set.of();
    }
    Optional<Instant> liveAt = lock.openPhaseLiveAt();
    if (liveAt.isEmpty()) {
      return Set.of();
    }
    Set<UUID> pending =
        lock.pendingAmong(
            ships.stream()
                .map(HangarRelocationGuard::ownerId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));
    if (pending.isEmpty()) {
      return Set.of();
    }
    return ships.stream()
        .filter(ship -> isPending(ship, pending) && fromBefore(ship, liveAt.get()))
        .map(Ship::getId)
        .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * Tells whether a ship's owner is among the pending owners.
   *
   * @param ship the ship
   * @param pending the pending owners
   * @return {@code true} when the ship has an owner and that owner is pending
   */
  private static boolean isPending(@NotNull Ship ship, @NotNull Set<UUID> pending) {
    UUID owner = ownerId(ship);
    return owner != null && pending.contains(owner);
  }

  /**
   * Tells whether a ship is from before a cutoff; a ship without a creation time counts as such.
   *
   * @param ship the ship
   * @param cutoff the cutoff
   * @return {@code true} when the ship was created before the cutoff or has no creation time
   */
  static boolean fromBefore(@NotNull Ship ship, @NotNull Instant cutoff) {
    return ship.getCreatedAt() == null || ship.getCreatedAt().isBefore(cutoff);
  }

  /**
   * Reads a ship's owner id without initialising a lazy owner.
   *
   * @param ship the ship
   * @return the owner's id, or {@code null} for a ship without one
   */
  private static @Nullable UUID ownerId(@NotNull Ship ship) {
    return ship.getOwner() == null ? null : ship.getOwner().getId();
  }

  /**
   * The ships a bulk write leaves out for its caller: those of the owners created before the
   * cutoff, or without a creation time.
   *
   * @param owners the owners the caller is refused for
   * @param before the open phase's live time, or {@code null} when nothing is locked
   */
  record LockedShips(@NotNull @Unmodifiable Set<UUID> owners, @Nullable Instant before) {

    /** Nothing is locked for the caller. */
    static final LockedShips NONE = new LockedShips(Set.of(), null);
  }
}
