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

package de.greluc.krt.profit.basetool.backend.hangar.api;

import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * What the hangar import found for one upload name: the ships the name may mean, each with the
 * catalogue rows that hold it (REQ-HANGAR-019). One ship resolves the name; several leave the
 * choice to the member.
 *
 * @param ships the ships, ordered by their canonical row's name; empty when the name found none
 */
public record ImportShipMatch(@NotNull @Unmodifiable List<Candidate> ships) {

  /** The name found no ship. */
  public static final ImportShipMatch NONE = new ImportShipMatch(List.of());

  /**
   * Copies the ships.
   *
   * @param ships the ships found
   */
  public ImportShipMatch {
    ships = List.copyOf(ships);
  }

  /**
   * Whether the name found no ship.
   *
   * @return {@code true} without a candidate
   */
  public boolean isEmpty() {
    return ships.isEmpty();
  }

  /**
   * Returns the one ship the name resolved to.
   *
   * @return the ship, or {@code null} when the name found none or several
   */
  @Nullable
  public Candidate single() {
    return ships.size() == 1 ? ships.getFirst() : null;
  }

  /**
   * One ship and the catalogue rows that hold it: the rows whose names agree once the leading
   * manufacturer word is dropped, such as {@code "Constellation Mk IV Andromeda"} and {@code "RSI
   * Constellation Mk IV Andromeda"}.
   *
   * @param rows the rows, never empty; the canonical row first, which is the one whose name carries
   *     the manufacturer word, then the others by name
   */
  public record Candidate(@NotNull @Unmodifiable List<ShipType> rows) {

    /**
     * Copies the rows and refuses an empty list.
     *
     * @param rows the rows, canonical first
     * @throws IllegalArgumentException when {@code rows} is empty
     */
    public Candidate {
      if (rows.isEmpty()) {
        throw new IllegalArgumentException("A candidate holds at least one ship type.");
      }
      rows = List.copyOf(rows);
    }

    /**
     * Returns the row a new ship of this candidate goes to when the hangar holds none of its rows.
     *
     * @return the canonical row
     */
    @NotNull
    public ShipType canonical() {
      return rows.getFirst();
    }

    /**
     * Returns the row of this candidate with the given id.
     *
     * @param shipTypeId the ship type id
     * @return the row, or {@code null} when this candidate holds no row with that id
     */
    @Nullable
    public ShipType row(@NotNull UUID shipTypeId) {
      return rows.stream().filter(row -> shipTypeId.equals(row.getId())).findFirst().orElse(null);
    }
  }
}
