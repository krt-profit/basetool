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

import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/** Resolves curated UEX aliases when an engine class has no direct catalogue row. */
@Component
@RequiredArgsConstructor
class P4kShipClassAliases {

  private static final String ALIASES = "SELECT a FROM ShipTypeUexAlias a";

  private final EntityManager entityManager;

  /**
   * Groups the stored ship types by the class name the {@code ship_type_uex_alias} table ties their
   * UEX vehicle id to (REQ-DATA-026).
   *
   * @param stored every stored ship type
   * @return the aliased ship types by folded alias class name
   */
  @NotNull
  Map<String, List<ShipType>> byClassName(@NotNull List<ShipType> stored) {
    Map<Integer, List<ShipType>> byVehicleId = new HashMap<>();
    stored.stream()
        .filter(row -> row.getUexVehicleId() != null)
        .forEach(row ->
            byVehicleId.computeIfAbsent(row.getUexVehicleId(), _ -> new ArrayList<>()).add(row));
    Map<String, List<ShipType>> aliased = new HashMap<>();
    for (ShipTypeUexAlias alias :
        entityManager.createQuery(ALIASES, ShipTypeUexAlias.class).getResultList()) {
      List<ShipType> holders = byVehicleId.get(alias.getUexVehicleId());
      if (holders != null) {
        aliased
            .computeIfAbsent(P4kMatchKeys.fold(alias.getClassName()), _ -> new ArrayList<>())
            .addAll(holders);
      }
    }
    return aliased;
  }

  /**
   * Finds the ship types of a class name ignoring case, or, when none carries it, the ship types
   * holding the UEX vehicle id the alias ties the class name to (REQ-DATA-026).
   *
   * @param rows the ship-type rows of the run
   * @param aliased the aliased ship types by folded alias class name
   * @param className the class name
   * @return the direct matches, else the aliased rows, else an empty list
   */
  @NotNull
  static List<ShipType> classMatches(
      @NotNull P4kRowIndex<ShipType> rows,
      @NotNull Map<String, List<ShipType>> aliased,
      @NotNull String className) {
    List<ShipType> direct = rows.byClassName(className);
    if (!direct.isEmpty()) {
      return direct;
    }
    return List.copyOf(aliased.getOrDefault(P4kMatchKeys.fold(className), List.of()));
  }
}
