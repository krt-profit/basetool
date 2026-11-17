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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Resolves the id of a ship type merged into another to the ship type that stays (REQ-DATA-042), so
 * an id a client still holds keeps working.
 */
public interface ShipTypeIdentityReferences {

  /**
   * Loads the ship type of a current or merged id.
   *
   * @param id the ship type id the caller holds
   * @return the ship type, or the one it was merged into; empty when neither exists
   */
  @NotNull
  Optional<ShipType> findById(@NotNull UUID id);

  /**
   * Tells whether a current or merged id names a ship type.
   *
   * @param id the ship type id the caller holds
   * @return whether the ship type, or the one it was merged into, exists
   */
  boolean existsById(@NotNull UUID id);

  /**
   * Loads the distinct ship types of current or merged ids in one query.
   *
   * @param ids the ship type ids the caller holds
   * @return the distinct ship types they name
   */
  @NotNull
  List<ShipType> findAllById(@NotNull Iterable<UUID> ids);

  /**
   * Returns a reference to the ship type of a current or merged id.
   *
   * @param id the ship type id the caller holds
   * @return a reference to the ship type the id names now
   */
  @NotNull
  ShipType getReferenceById(@NotNull UUID id);

  /**
   * Maps the merged ids among the given ones to the ship types they were merged into.
   *
   * @param ids the ship type ids the caller holds
   * @return the merged ids only; a current or unknown id is absent
   */
  @NotNull
  Map<UUID, UUID> findLocalIdRedirects(@NotNull Collection<UUID> ids);
}
