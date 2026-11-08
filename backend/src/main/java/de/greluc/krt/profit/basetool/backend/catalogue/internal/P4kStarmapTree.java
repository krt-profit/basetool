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

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The starmap tree after a run's reconciliation, keyed by P4K GUID: every stored object plus the
 * ones the run adds, which a preview holds unsaved and without an id. Parents are kept by GUID so a
 * preview can walk the tree it would write.
 *
 * @param objects every object by its GUID
 * @param parents each object's parent GUID, absent for a root
 */
record P4kStarmapTree(
    @NotNull @Unmodifiable Map<UUID, StarmapObject> objects,
    @NotNull @Unmodifiable Map<UUID, UUID> parents) {

  /**
   * Copies both maps.
   *
   * @param objects every object by its GUID
   * @param parents each object's parent GUID
   */
  P4kStarmapTree {
    objects = Map.copyOf(objects);
    parents = Map.copyOf(parents);
  }

  /**
   * Returns every object.
   *
   * @return the objects
   */
  @NotNull
  @Unmodifiable
  Collection<StarmapObject> all() {
    return objects.values();
  }

  /**
   * Returns an object.
   *
   * @param guid the GUID, or {@code null}
   * @return the object, or {@code null} when the tree holds none
   */
  @Nullable
  StarmapObject object(@Nullable UUID guid) {
    return guid == null ? null : objects.get(guid);
  }

  /**
   * Whether an object lies below another one, at any depth.
   *
   * @param guid the object
   * @param ancestor the presumed ancestor
   * @return {@code true} when the ancestor is on the object's parent chain
   */
  boolean isBelow(@NotNull UUID guid, @NotNull UUID ancestor) {
    Set<UUID> visited = new HashSet<>();
    UUID current = parents.get(guid);
    while (current != null && visited.add(current)) {
      if (current.equals(ancestor)) {
        return true;
      }
      current = parents.get(current);
    }
    return false;
  }
}
