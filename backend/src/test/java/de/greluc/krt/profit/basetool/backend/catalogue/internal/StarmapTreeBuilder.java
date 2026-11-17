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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Builds a stored starmap tree in memory for the link rule tests (REQ-P4K-035, REQ-P4K-043): each
 * object gets a GUID and an id, and its parent both as GUID and as id.
 */
final class StarmapTreeBuilder {

  private final Map<UUID, StarmapObject> objects = new HashMap<>();
  private final Map<UUID, UUID> parents = new HashMap<>();

  /**
   * Adds a placed object.
   *
   * @param name the English name
   * @param type the type
   * @param parent the parent's GUID, or {@code null} for a root
   * @return the object's GUID
   */
  @NotNull
  UUID add(@NotNull String name, @NotNull String type, @Nullable UUID parent) {
    StarmapObject object = unplaced(name, type, parent);
    object.setPositionX(1.0);
    object.setPositionY(2.0);
    object.setPositionZ(3.0);
    return object.getP4kUuid();
  }

  /**
   * Adds an object the catalogue does not place, so it has no position.
   *
   * @param name the English name
   * @param type the type
   * @param parent the parent's GUID, or {@code null} for a root
   * @return the object
   */
  @NotNull
  StarmapObject unplaced(@NotNull String name, @NotNull String type, @Nullable UUID parent) {
    StarmapObject object = new StarmapObject();
    object.setId(UUID.randomUUID());
    object.setP4kUuid(UUID.randomUUID());
    object.setName(name);
    object.setType(type);
    if (parent != null) {
      object.setParentId(objects.get(parent).getId());
      parents.put(object.getP4kUuid(), parent);
    }
    objects.put(object.getP4kUuid(), object);
    return object;
  }

  /**
   * Returns an added object.
   *
   * @param guid its GUID
   * @return the object
   */
  @NotNull
  StarmapObject object(@NotNull UUID guid) {
    return objects.get(guid);
  }

  /**
   * Returns every added object, as the repository lists them.
   *
   * @return the objects
   */
  @NotNull
  List<StarmapObject> objects() {
    return new ArrayList<>(objects.values());
  }

  /**
   * Builds the tree.
   *
   * @return the tree of the added objects
   */
  @NotNull
  P4kStarmapTree build() {
    return new P4kStarmapTree(objects, parents);
  }
}
