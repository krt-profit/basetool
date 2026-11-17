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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * One row of a UEX location table as the starmap linking reads it (REQ-P4K-035).
 *
 * @param kind the table
 * @param id the row
 * @param uexId the row's UEX id, or {@code null}
 * @param name the UEX name
 * @param nickname the UEX nickname, {@code null} when UEX gives none or the table keeps none
 * @param starSystem the star system's name
 * @param planet the planet's name, or {@code null}
 * @param orbit the orbit's name, or {@code null}
 * @param moon the moon's name, or {@code null}
 * @param city the city's name, or {@code null}
 * @param spaceStation the space station's name, or {@code null}
 * @param outpost the outpost's name, or {@code null}
 * @param live UEX's {@code is_available_live}, or {@code null} when UEX stated none
 * @param terminalType the UEX type of a terminal, e.g. {@code item}, {@code null} for other tables
 * @param starmapObjectId the linked starmap object, or {@code null}
 * @param manual whether an admin set or cleared the link
 */
public record StarmapPlaceRow(
    @NotNull StarmapPlaceKind kind,
    @NotNull UUID id,
    @Nullable Integer uexId,
    @Nullable String name,
    @Nullable String nickname,
    @Nullable String starSystem,
    @Nullable String planet,
    @Nullable String orbit,
    @Nullable String moon,
    @Nullable String city,
    @Nullable String spaceStation,
    @Nullable String outpost,
    @Nullable Boolean live,
    @Nullable String terminalType,
    @Nullable UUID starmapObjectId,
    boolean manual) {

  /**
   * Names the location in the UEX hierarchy with the parent names supplied by UEX.
   *
   * @return its path from the star system to this row
   */
  @NotNull
  String reviewPath() {
    return Stream.of(starSystem, orbit, planet, moon, city, spaceStation, outpost, name)
        .filter(value -> value != null && !value.isBlank())
        .distinct()
        .collect(Collectors.joining(" / "));
  }

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
    List<V> parents = linkedParents(linked);
    return parents.isEmpty() ? null : parents.getFirst();
  }

  /**
   * Returns the links of the row's linked parents: the parents the row names, nearest first, then
   * its star system, each link once.
   *
   * @param linked the links of the rows of each kind, keyed by star system and name
   * @param <V> the link value, a starmap object's GUID or id
   * @return the links, empty for a star system or a row without a linked parent
   */
  @NotNull
  @Unmodifiable
  public <V> List<V> linkedParents(@NotNull Map<StarmapPlaceKind, Map<String, V>> linked) {
    if (kind == StarmapPlaceKind.STAR_SYSTEM) {
      return List.of();
    }
    List<V> links = new ArrayList<>();
    for (StarmapPlaceKind.Parent parent : kind.getParents()) {
      String parentName = parentName(parent);
      V link =
          parentName == null
              ? null
              : linked
                  .getOrDefault(parent.kind(), Map.of())
                  .get(StarmapNames.key(starSystem, parentName));
      if (link != null && !links.contains(link)) {
        links.add(link);
      }
    }
    V system = starSystemLink(linked);
    if (system != null && !links.contains(system)) {
      links.add(system);
    }
    return List.copyOf(links);
  }

  /**
   * Returns the link of the row's star system.
   *
   * @param linked the links of the rows of each kind, keyed by star system and name
   * @param <V> the link value, a starmap object's GUID or id
   * @return the link of the star system row of the row's system, or {@code null}
   */
  @Nullable
  public <V> V starSystemLink(@NotNull Map<StarmapPlaceKind, Map<String, V>> linked) {
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
        (Integer) tuple[1],
        (String) tuple[2],
        (String) tuple[3],
        (String) tuple[4],
        (String) tuple[5],
        (String) tuple[6],
        (String) tuple[7],
        (String) tuple[8],
        (String) tuple[9],
        (String) tuple[10],
        (Boolean) tuple[11],
        (String) tuple[12],
        (UUID) tuple[13],
        Boolean.TRUE.equals(tuple[14]));
  }
}
