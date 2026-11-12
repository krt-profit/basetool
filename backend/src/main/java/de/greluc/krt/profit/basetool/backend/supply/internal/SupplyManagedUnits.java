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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import de.greluc.krt.profit.basetool.backend.scope.api.ScopePredicate;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The units whose needs and donations the caller manages (REQ-ORG-032, REQ-SUP-003): inside the
 * active context as {@link SupplyAccessPolicy#managedUnits()} resolves them, or in any context as
 * {@link SupplyAccessPolicy#managedUnitsInAnyContext()} resolves them for the tab decisions. Every
 * manage flag and manager list of the supply module asks this value instead of deciding on its own
 * who manages a unit.
 *
 * @param everyUnit whether the caller manages every unit: inside the active context only an admin
 *     without a pin, in any context every admin
 * @param unitIds the units the caller manages while {@code everyUnit} is {@code false}; empty while
 *     it is {@code true}
 */
public record SupplyManagedUnits(boolean everyUnit, @NotNull @Unmodifiable Set<UUID> unitIds) {

  /**
   * Copies the listed units, and drops them while the caller manages every unit.
   *
   * @param everyUnit whether the caller manages every unit
   * @param unitIds the units the caller manages otherwise
   */
  public SupplyManagedUnits {
    unitIds = everyUnit ? Set.of() : Set.copyOf(unitIds);
  }

  /**
   * Reads the units a scope vector permits, as {@link ScopePredicate#permits(UUID)} does.
   *
   * @param scope the units the caller handles through the unit manager role
   * @return every unit for an all-units scope, otherwise the pinned and the listed units
   */
  static @NotNull SupplyManagedUnits of(@NotNull ScopePredicate scope) {
    Set<UUID> listed = new HashSet<>(scope.memberOrgUnitIds());
    UUID pinned = scope.activeOrgUnitId();
    if (pinned != null) {
      listed.add(pinned);
    }
    return new SupplyManagedUnits(scope.adminAllScope(), listed);
  }

  /**
   * Whether the caller manages the needs and donations of a unit.
   *
   * @param orgUnitId the unit
   * @return {@code true} for every unit while {@link #everyUnit()}, otherwise for a listed unit
   */
  public boolean contains(@NotNull UUID orgUnitId) {
    return everyUnit || unitIds.contains(orgUnitId);
  }

  /**
   * Narrows candidate units to the ones the caller manages.
   *
   * @param candidates the units to narrow
   * @return the candidates the caller manages
   */
  public @NotNull @Unmodifiable Set<UUID> among(@NotNull Collection<UUID> candidates) {
    return candidates.stream().filter(this::contains).collect(Collectors.toUnmodifiableSet());
  }

  /**
   * Whether the caller manages no unit at all.
   *
   * @return {@code true} when the caller neither manages every unit nor lists one
   */
  public boolean isEmpty() {
    return !everyUnit && unitIds.isEmpty();
  }
}
