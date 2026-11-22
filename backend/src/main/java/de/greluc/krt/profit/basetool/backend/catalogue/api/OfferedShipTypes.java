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

import java.util.Objects;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The rule which ship types the pickers offer and the guard every write runs on the ship type it
 * sets (REQ-P4K-080): only a ship type the game files list, neither hidden nor retired, is
 * accepted; a write that keeps an entry's current ship type passes whatever that type has become.
 *
 * <p>{@link OfferedShipTypeJpql#OFFERED_SHIP} is the query form of {@link #isOffered(ShipType)}.
 */
public final class OfferedShipTypes {

  /** Non-instantiable static-helper holder. */
  private OfferedShipTypes() {}

  /**
   * Whether the pickers offer the ship type.
   *
   * @param shipType the ship type
   * @return {@code true} when the P4K matched it and it is neither hidden nor retired
   */
  public static boolean isOffered(@NotNull ShipType shipType) {
    return !shipType.isHidden() && shipType.getP4kUuid() != null && !shipType.isP4kRetired();
  }

  /**
   * Whether the ship type is one the game files do not list: the P4K never matched it.
   *
   * @param shipType the ship type
   * @return {@code true} when it holds no P4K GUID
   */
  public static boolean isNotInGame(@NotNull ShipType shipType) {
    return shipType.getP4kUuid() == null;
  }

  /**
   * Returns the ship type when the pickers offer it.
   *
   * @param shipType the ship type a write sets
   * @return {@code shipType}
   * @throws ShipTypeNotOfferedException when it is hidden, retired or unknown to the game files
   */
  @Contract("_ -> param1")
  public static @NotNull ShipType requireOffered(@NotNull ShipType shipType) {
    if (!isOffered(shipType)) {
      throw new ShipTypeNotOfferedException();
    }
    return shipType;
  }

  /**
   * Returns the ship type an update sets when it is the entry's current ship type or one the
   * pickers offer, so an entry of a type that has left the pickers stays editable as long as it
   * keeps that type.
   *
   * @param requested the ship type the update sets
   * @param current the entry's ship type before the update, or {@code null} when it has none
   * @return {@code requested}
   * @throws ShipTypeNotOfferedException when the type changes to one the pickers do not offer
   */
  @Contract("_, _ -> param1")
  public static @NotNull ShipType requireOfferedOrCurrent(
      @NotNull ShipType requested, @Nullable ShipType current) {
    if (current != null && Objects.equals(requested.getId(), current.getId())) {
      return requested;
    }
    return requireOffered(requested);
  }
}
