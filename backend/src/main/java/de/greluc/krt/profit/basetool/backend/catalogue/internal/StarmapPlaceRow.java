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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One row of a UEX location table as the starmap linking reads it (REQ-P4K-035).
 *
 * @param kind the table
 * @param id the row
 * @param name the UEX name
 * @param starSystem the star system's name
 * @param planet the planet's name, or {@code null}
 * @param orbit the orbit's name, or {@code null}
 * @param moon the moon's name, or {@code null}
 * @param city the city's name, or {@code null}
 * @param spaceStation the space station's name, or {@code null}
 * @param outpost the outpost's name, or {@code null}
 * @param starmapObjectId the linked starmap object, or {@code null}
 * @param manual whether an admin set or cleared the link
 */
public record StarmapPlaceRow(
    @NotNull StarmapPlaceKind kind,
    @NotNull UUID id,
    @Nullable String name,
    @Nullable String starSystem,
    @Nullable String planet,
    @Nullable String orbit,
    @Nullable String moon,
    @Nullable String city,
    @Nullable String spaceStation,
    @Nullable String outpost,
    @Nullable UUID starmapObjectId,
    boolean manual) {

  /**
   * Returns the name of one of the row's parents.
   *
   * @param parent the parent
   * @return its name, or {@code null}
   */
  @Nullable
  public String parentName(@NotNull StarmapPlaceKind.Parent parent) {
    return switch (parent) {
      case PLANET -> planet;
      case ORBIT -> orbit;
      case MOON -> moon;
      case CITY -> city;
      case SPACE_STATION -> spaceStation;
      case OUTPOST -> outpost;
    };
  }

  /**
   * Returns the link of the row's nearest linked parent: the parents the row names, nearest first,
   * then its star system.
   *
   * @param linked the links of the rows of each kind, keyed by star system and name
   * @param <V> the link value, a starmap object's GUID or id
   * @return the parent's link, or {@code null} when no named parent is linked or the row is a star
   *     system
   */
  @Nullable
  public <V> V nearestLinkedParent(@NotNull Map<StarmapPlaceKind, Map<String, V>> linked) {
    if (kind == StarmapPlaceKind.STAR_SYSTEM) {
      return null;
    }
    for (StarmapPlaceKind.Parent parent : kind.getParents()) {
      String parentName = parentName(parent);
      if (parentName != null) {
        V link =
            linked
                .getOrDefault(parent.kind(), Map.of())
                .get(StarmapNames.key(starSystem, parentName));
        if (link != null) {
          return link;
        }
      }
    }
    return starSystem == null
        ? null
        : linked
            .getOrDefault(StarmapPlaceKind.STAR_SYSTEM, Map.of())
            .get(StarmapNames.key(starSystem, starSystem));
  }

  /**
   * Builds a row from a native result tuple in the column order of {@link
   * StarmapPlaceKind#rowsQuery()}.
   *
   * @param kind the table
   * @param tuple the tuple
   * @return the row
   */
  @NotNull
  static StarmapPlaceRow of(@NotNull StarmapPlaceKind kind, @NotNull Object[] tuple) {
    return new StarmapPlaceRow(
        kind,
        (UUID) tuple[0],
        (String) tuple[1],
        (String) tuple[2],
        (String) tuple[3],
        (String) tuple[4],
        (String) tuple[5],
        (String) tuple[6],
        (String) tuple[7],
        (String) tuple[8],
        (UUID) tuple[9],
        Boolean.TRUE.equals(tuple[10]));
  }
}
