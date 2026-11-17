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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Decides whether a UEX location row the matching left unlinked opens a review case (REQ-P4K-060).
 * None opens for a row UEX does not report live and the game has no hit for, and for a POI or an
 * outpost whose link would feed nothing: no UEX terminal names it as its place (the terminal table
 * names no POI) and no storage place sits on or below one of its candidates. Orbits and terminals
 * are never matched and open none either.
 */
final class StarmapCaseRules {

  private final P4kStarmapTree tree;

  /** The outposts UEX terminals name as their place, by star system and name. */
  private final Set<String> outpostsWithTerminals = new HashSet<>();

  /** The GUIDs of the starmap objects a storage place sits on. */
  private final Set<UUID> storagePlaces;

  /**
   * Collects what an unlinked row's link could feed.
   *
   * @param tree the starmap tree after the run's reconciliation
   * @param terminals the UEX terminal rows
   * @param storagePlaces the GUIDs of the starmap objects a storage place sits on
   */
  StarmapCaseRules(
      @NotNull P4kStarmapTree tree,
      @NotNull List<StarmapPlaceRow> terminals,
      @NotNull Set<UUID> storagePlaces) {
    this.tree = tree;
    this.storagePlaces = Set.copyOf(storagePlaces);
    for (StarmapPlaceRow terminal : terminals) {
      String outpost = terminal.outpost();
      if (outpost != null) {
        outpostsWithTerminals.add(StarmapNames.key(terminal.starSystem(), outpost));
      }
    }
  }

  /**
   * Whether an unlinked row opens a review case.
   *
   * @param row the row
   * @param candidates the GUIDs of its candidates, none or several
   * @return {@code false} when a case could settle nothing the row's link feeds
   */
  boolean opensCase(@NotNull StarmapPlaceRow row, @NotNull List<UUID> candidates) {
    if (candidates.isEmpty() && Boolean.FALSE.equals(row.live())) {
      return false;
    }
    return switch (row.kind()) {
      case STAR_SYSTEM, PLANET, MOON, CITY, SPACE_STATION -> true;
      case ORBIT, TERMINAL -> false;
      case OUTPOST -> hasTerminals(row) || holdsStoragePlace(candidates);
      case POI -> holdsStoragePlace(candidates);
    };
  }

  /**
   * Whether a UEX terminal names an outpost row as its place.
   *
   * @param row the outpost row
   * @return {@code true} when a terminal of its star system names it
   */
  private boolean hasTerminals(@NotNull StarmapPlaceRow row) {
    String name = row.name();
    return name != null && outpostsWithTerminals.contains(StarmapNames.key(row.starSystem(), name));
  }

  /**
   * Whether a storage place sits on one of the candidates or below one.
   *
   * @param candidates the GUIDs of the candidates
   * @return {@code true} when a storage place's object is or lies below a candidate
   */
  private boolean holdsStoragePlace(@NotNull List<UUID> candidates) {
    return storagePlaces.stream()
        .anyMatch(place ->
            candidates.stream()
                .anyMatch(candidate -> candidate.equals(place) || tree.isBelow(place, candidate)));
  }
}
