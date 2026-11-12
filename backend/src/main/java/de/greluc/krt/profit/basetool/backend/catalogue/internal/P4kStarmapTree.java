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

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
   * Builds a GUID-keyed tree from objects and their persisted parent ids.
   *
   * @param stored the stored objects
   * @return the stored tree
   */
  static P4kStarmapTree stored(@NotNull List<StarmapObject> stored) {
    Map<UUID, StarmapObject> byGuid = new HashMap<>();
    Map<UUID, UUID> guidsById = new HashMap<>();
    Map<UUID, UUID> parentGuids = new HashMap<>();
    stored.forEach(object -> {
      byGuid.put(object.getP4kUuid(), object);
      guidsById.put(object.getId(), object.getP4kUuid());
    });
    for (StarmapObject object : stored) {
      UUID parentGuid = guidsById.get(object.getParentId());
      if (parentGuid != null) {
        parentGuids.put(object.getP4kUuid(), parentGuid);
      }
    }
    return new P4kStarmapTree(byGuid, parentGuids);
  }

  /**
   * Lists an object's named ancestors from the root to the object.
   *
   * @param guid the stored object's P4K GUID
   * @return the display path
   */
  @NotNull
  String path(@NotNull UUID guid) {
    Deque<String> segments = new ArrayDeque<>();
    Set<UUID> visited = new HashSet<>();
    UUID current = guid;
    while (current != null && visited.add(current)) {
      StarmapObject object = objects.get(current);
      if (object != null) {
        segments.addFirst(objectLabel(object));
      }
      current = parents.get(current);
    }
    return String.join(" / ", segments);
  }

  /**
   * Names an object with its English name, class or GUID.
   *
   * @param object the object
   * @return its display label
   */
  @NotNull
  static String objectLabel(@NotNull StarmapObject object) {
    if (object.getName() != null && !object.getName().isBlank()) {
      return object.getName();
    }
    if (object.getClassName() != null && !object.getClassName().isBlank()) {
      return object.getClassName();
    }
    return object.getP4kUuid().toString();
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
