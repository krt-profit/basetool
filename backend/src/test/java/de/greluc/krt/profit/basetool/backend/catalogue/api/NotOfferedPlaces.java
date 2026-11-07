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

import java.util.UUID;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Named;

/**
 * The storage places no picker offers, for the write-path tests of REQ-P4K-039; a parameterized
 * test names {@code de.greluc.krt.profit.basetool.backend.catalogue.api.NotOfferedPlaces#places} as
 * its {@code @MethodSource}.
 */
public final class NotOfferedPlaces {

  /** Non-instantiable fixture holder. */
  private NotOfferedPlaces() {}

  /**
   * One hidden and one reference-only place, each with a fresh id.
   *
   * @return the named places
   */
  public static @NotNull Stream<Named<Location>> places() {
    return Stream.of(
        Named.of("hidden", place(true, false)), Named.of("reference-only", place(false, true)));
  }

  /**
   * A place with a fresh id and the given markers.
   *
   * @param hidden whether an admin hid it
   * @param referenceOnly whether it exists only for existing personal-inventory rows
   * @return the place
   */
  public static @NotNull Location place(boolean hidden, boolean referenceOnly) {
    Location location = new Location();
    location.setId(UUID.randomUUID());
    location.setName(hidden ? "Hidden place" : referenceOnly ? "Reference place" : "Offered place");
    location.setHidden(hidden);
    location.setReferenceOnly(referenceOnly);
    return location;
  }
}
