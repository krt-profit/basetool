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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Locale;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The nine UEX location tables that link to the starmap tree (REQ-P4K-035, ADR-0345), in linking
 * order, parents first. Each names the starmap types it can link to, as type families (the type's
 * lower-case name up to its first {@code _}, so {@code Outpost_InvalidQT} is {@code outpost}), and
 * the denormalised parent-name columns the UEX feed gives it, nearest parent first.
 */
@RequiredArgsConstructor
@Getter
public enum StarmapPlaceKind {

  /** {@code star_system}; its own name is its system. */
  STAR_SYSTEM("star_system", ImmutableSet.of("solarsystem", "star", "system"), ImmutableList.of()),

  /** {@code orbit}: a body or point UEX treats as an orbit. */
  ORBIT(
      "orbit",
      ImmutableSet.of("planet", "lagrange", "lagrangepoint", "jumppoint"),
      ImmutableList.of()),

  /** {@code planet}. */
  PLANET("planet", ImmutableSet.of("planet"), ImmutableList.of()),

  /** {@code moon}. */
  MOON("moon", ImmutableSet.of("moon"), ImmutableList.of(Parent.PLANET, Parent.ORBIT)),

  /** {@code city}. */
  CITY(
      "city",
      ImmutableSet.of("landingzone", "city"),
      ImmutableList.of(Parent.MOON, Parent.PLANET, Parent.ORBIT)),

  /** {@code space_station}. */
  SPACE_STATION(
      "space_station",
      ImmutableSet.of("spacestation", "station", "manmade", "landingzone"),
      ImmutableList.of(Parent.MOON, Parent.PLANET, Parent.ORBIT)),

  /** {@code outpost}. */
  OUTPOST(
      "outpost",
      ImmutableSet.of("outpost", "manmade"),
      ImmutableList.of(Parent.MOON, Parent.PLANET, Parent.ORBIT)),

  /** {@code poi}. */
  POI(
      "poi",
      ImmutableSet.of(
          "pointofinterest", "poi", "manmade", "outpost", "asteroid", "anomaly", "cave"),
      ImmutableList.of(
          Parent.CITY,
          Parent.SPACE_STATION,
          Parent.OUTPOST,
          Parent.MOON,
          Parent.PLANET,
          Parent.ORBIT)),

  /** {@code terminal}. */
  TERMINAL(
      "terminal",
      ImmutableSet.of("manmade", "pointofinterest", "outpost", "landingzone"),
      ImmutableList.of(
          Parent.CITY,
          Parent.SPACE_STATION,
          Parent.OUTPOST,
          Parent.MOON,
          Parent.PLANET,
          Parent.ORBIT));

  /** The table. */
  @NotNull private final String table;

  /** The type families of the starmap objects a row can link to. */
  @NotNull @Unmodifiable private final ImmutableSet<String> typeFamilies;

  /**
   * The parents below the star system a row may name, nearest first; the star system closes every
   * chain.
   */
  @NotNull @Unmodifiable private final ImmutableList<Parent> parents;

  /**
   * Whether a starmap type belongs to one of this kind's families.
   *
   * @param type the object's type, or {@code null}
   * @return {@code true} when its family is one of this kind's
   */
  public boolean accepts(@Nullable String type) {
    if (type == null || type.isBlank()) {
      return false;
    }
    String family = type.strip().toLowerCase(Locale.ROOT);
    int cut = family.indexOf('_');
    return typeFamilies.contains(cut < 0 ? family : family.substring(0, cut));
  }

  /**
   * The native query that reads every row of the table as a {@link StarmapPlaceRow}: id, name, the
   * star system, planet, orbit, moon, city, space station and outpost names, the link and its
   * manual flag.
   *
   * @return the query text
   */
  @NotNull
  String rowsQuery() {
    String system = this == STAR_SYSTEM ? "name" : "star_system_name";
    return "SELECT id, name, "
        + system
        + ", "
        + column(Parent.PLANET)
        + ", "
        + column(Parent.ORBIT)
        + ", "
        + column(Parent.MOON)
        + ", "
        + column(Parent.CITY)
        + ", "
        + column(Parent.SPACE_STATION)
        + ", "
        + column(Parent.OUTPOST)
        + ", starmap_object_id, starmap_link_manual FROM "
        + table;
  }

  /**
   * The native statement an APPLY links a row with; it leaves a manual link alone.
   *
   * @return the statement with the parameters {@code objectId} and {@code id}
   */
  @NotNull
  String autoLinkStatement() {
    return "UPDATE "
        + table
        + " SET starmap_object_id = CAST(:objectId AS UUID) WHERE id = :id"
        + " AND starmap_link_manual = FALSE";
  }

  /**
   * The native statement an admin sets or clears a link with, compare-and-set on the link the admin
   * saw.
   *
   * @return the statement with the parameters {@code objectId}, {@code manual}, {@code id}, {@code
   *     expectedObjectId} and {@code expectedManual}
   */
  @NotNull
  String manualLinkStatement() {
    return "UPDATE "
        + table
        + " SET starmap_object_id = CAST(:objectId AS UUID), starmap_link_manual = :manual"
        + " WHERE id = :id AND starmap_link_manual = :expectedManual"
        + " AND starmap_object_id IS NOT DISTINCT FROM CAST(:expectedObjectId AS UUID)";
  }

  /**
   * The column of a parent name in this table, or a {@code NULL} literal when it has none.
   *
   * @param parent the parent
   * @return the column or the literal
   */
  @NotNull
  private String column(@NotNull Parent parent) {
    return parents.contains(parent) ? parent.getColumn() : "CAST(NULL AS TEXT)";
  }

  /** A parent a UEX row names by a denormalised column. */
  @RequiredArgsConstructor
  @Getter
  public enum Parent {

    /** The planet, {@code planet_name}. */
    PLANET("planet_name"),

    /** The orbit, {@code orbit_name}. */
    ORBIT("orbit_name"),

    /** The moon, {@code moon_name}. */
    MOON("moon_name"),

    /** The city, {@code city_name}. */
    CITY("city_name"),

    /** The space station, {@code space_station_name}. */
    SPACE_STATION("space_station_name"),

    /** The outpost, {@code outpost_name}. */
    OUTPOST("outpost_name");

    /** The column naming the parent. */
    @NotNull private final String column;

    /**
     * Returns the place kind of the parent.
     *
     * @return the kind
     */
    @NotNull
    public StarmapPlaceKind kind() {
      return switch (this) {
        case PLANET -> StarmapPlaceKind.PLANET;
        case ORBIT -> StarmapPlaceKind.ORBIT;
        case MOON -> StarmapPlaceKind.MOON;
        case CITY -> StarmapPlaceKind.CITY;
        case SPACE_STATION -> StarmapPlaceKind.SPACE_STATION;
        case OUTPOST -> StarmapPlaceKind.OUTPOST;
      };
    }
  }
}
