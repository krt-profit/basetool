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
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * What one {@link ShipTypeMatcher} step found: no ship type, exactly one, or several, which is
 * ambiguous and never resolves to the first (REQ-P4K-032).
 *
 * @param candidates the distinct ship types the step found, ordered by name
 */
public record ShipTypeMatch(@NotNull @Unmodifiable List<ShipType> candidates) {

  /** The step found nothing. */
  public static final ShipTypeMatch NONE = new ShipTypeMatch(List.of());

  /**
   * Copies the candidates.
   *
   * @param candidates the ship types found
   */
  public ShipTypeMatch {
    candidates = List.copyOf(candidates);
  }

  /**
   * Builds a match from the ship types a step found, dropping duplicates and ordering by name.
   *
   * @param found the ship types, or {@code null} for none
   * @return the match
   */
  @NotNull
  public static ShipTypeMatch of(@Nullable Collection<ShipType> found) {
    if (found == null || found.isEmpty()) {
      return NONE;
    }
    return new ShipTypeMatch(
        new LinkedHashSet<>(found)
            .stream()
                .sorted(Comparator.comparing(ShipType::getName, String.CASE_INSENSITIVE_ORDER))
                .toList());
  }

  /**
   * Whether the step found nothing.
   *
   * @return {@code true} without a candidate
   */
  public boolean isEmpty() {
    return candidates.isEmpty();
  }

  /**
   * Whether the step found several ship types.
   *
   * @return {@code true} with more than one candidate
   */
  public boolean isAmbiguous() {
    return candidates.size() > 1;
  }

  /**
   * Returns the one ship type the step resolved to.
   *
   * @return the ship type, or {@code null} when the step found none or several
   */
  @Nullable
  public ShipType single() {
    return candidates.size() == 1 ? candidates.getFirst() : null;
  }
}
