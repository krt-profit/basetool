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

import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import java.util.Optional;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The guard every squadron write runs on the storage place it sets (REQ-P4K-039): only a place the
 * pickers offer, neither hidden nor reference-only, is accepted; a write that keeps an entry's
 * current place passes whatever that place has become.
 *
 * <p>The personal inventory keeps its own rule and does not use this guard.
 */
public final class OfferedLocations {

  /** Non-instantiable static-helper holder. */
  private OfferedLocations() {}

  /**
   * Whether the pickers offer the place.
   *
   * @param location the place
   * @return {@code true} when it is neither hidden nor reference-only
   */
  public static boolean isOffered(@NotNull Location location) {
    return !Boolean.TRUE.equals(location.getHidden())
        && !Boolean.TRUE.equals(location.getReferenceOnly());
  }

  /**
   * Returns the place when the pickers offer it.
   *
   * @param location the place a write sets
   * @return {@code location}
   * @throws LocationNotOfferedException when it is hidden or reference-only
   */
  @Contract("_ -> param1")
  public static @NotNull Location requireOffered(@NotNull Location location) {
    if (!isOffered(location)) {
      throw new LocationNotOfferedException();
    }
    return location;
  }

  /**
   * Unwraps a lookup by id and returns the place when the pickers offer it.
   *
   * @param found the result of the lookup
   * @param notFoundMessage the RFC&nbsp;7807 {@code detail} raised when the place does not exist
   * @return the offered place
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the lookup is empty
   * @throws LocationNotOfferedException when the place is hidden or reference-only
   */
  public static @NotNull Location require(
      @NotNull Optional<Location> found, @NotNull String notFoundMessage) {
    return requireOffered(Entities.require(found, notFoundMessage));
  }

  /**
   * Returns the place an update sets when it is the entry's current place or one the pickers offer,
   * so an entry on a place that has since been hidden stays editable as long as it stays there.
   *
   * @param requested the place the update sets
   * @param current the entry's place before the update, or {@code null} when it has none
   * @return {@code requested}
   * @throws LocationNotOfferedException when the place changes to a hidden or reference-only one
   */
  @Contract("_, _ -> param1")
  public static @NotNull Location requireOfferedOrCurrent(
      @NotNull Location requested, @Nullable Location current) {
    if (current != null && requested.getId().equals(current.getId())) {
      return requested;
    }
    return requireOffered(requested);
  }
}
