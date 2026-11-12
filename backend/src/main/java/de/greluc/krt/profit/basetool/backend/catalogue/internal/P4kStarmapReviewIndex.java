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
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Indexes the current starmap and UEX links once for one review request. */
final class P4kStarmapReviewIndex {

  private final P4kStarmapTree tree;
  private final Map<UUID, StarmapPlaceRow> subjects = new HashMap<>();
  private final Map<String, List<StarmapObject>> byName = new HashMap<>();
  private final Map<StarmapPlaceKind, Map<String, UUID>> links;

  /**
   * Reads each required UEX table once and indexes active objects by name and local row id.
   *
   * @param tree the stored starmap
   * @param placeRows the source of UEX locations
   * @param subjectKinds the kinds whose review subjects are needed
   */
  P4kStarmapReviewIndex(
      @NotNull P4kStarmapTree tree,
      @NotNull StarmapPlaceRows placeRows,
      @NotNull Set<StarmapPlaceKind> subjectKinds) {
    this.tree = tree;
    StarmapParentLinks<UUID> linked = new StarmapParentLinks<>();
    Map<UUID, UUID> liveGuids = new HashMap<>();
    for (StarmapObject object : tree.all()) {
      if (!object.isP4kRetired()) {
        liveGuids.put(object.getId(), object.getP4kUuid());
        String name = StarmapNames.normalise(object.getName());
        if (name != null) {
          byName.computeIfAbsent(name, _ -> new ArrayList<>()).add(object);
        }
      }
    }
    for (StarmapPlaceKind kind : StarmapPlaceKind.values()) {
      boolean subjectsNeeded = subjectKinds.contains(kind);
      if (kind == StarmapPlaceKind.TERMINAL && !subjectsNeeded) {
        continue;
      }
      for (StarmapPlaceRow row : placeRows.rows(kind)) {
        UUID guid = liveGuids.get(row.starmapObjectId());
        if (guid != null) {
          linked.put(row, guid);
        }
        if (subjectsNeeded) {
          subjects.put(P4kStarmapLinker.caseGuid(kind, row.id()), row);
        }
      }
    }
    links = linked.byKind();
  }

  /**
   * Looks up the UEX subject identified by the case's derived GUID.
   *
   * @param guid the case GUID
   * @return the current source row, if any
   */
  @Nullable
  StarmapPlaceRow subject(@NotNull UUID guid) {
    return subjects.get(guid);
  }

  /**
   * Finds the nearest ancestor linked to a currently active starmap object.
   *
   * @param row the UEX source
   * @return the ancestor's P4K GUID, or null for an unscoped row
   */
  @Nullable
  UUID ancestor(@NotNull StarmapPlaceRow row) {
    return row.nearestLinkedParent(links);
  }

  /**
   * Finds the active objects matching the source name, type and parent scope.
   *
   * @param row the UEX source
   * @param ancestor the scope, or null for a star system
   * @return exact eligible objects
   */
  @NotNull
  List<StarmapObject> exact(@NotNull StarmapPlaceRow row, @Nullable UUID ancestor) {
    String name = StarmapNames.normalise(row.name());
    return name == null
        ? List.of()
        : byName.getOrDefault(name, List.of()).stream()
            .filter(object -> compatible(row, ancestor, object))
            .toList();
  }

  /**
   * Checks whether an active object belongs to the source's type and scope.
   *
   * @param row the UEX source
   * @param ancestor the scope, or null for a star system
   * @param object the proposed object
   * @return whether the object can be selected
   */
  boolean compatible(
      @NotNull StarmapPlaceRow row, @Nullable UUID ancestor, @NotNull StarmapObject object) {
    return !object.isP4kRetired()
        && row.kind().accepts(object.getType())
        && (ancestor == null || tree.isBelow(object.getP4kUuid(), ancestor));
  }
}
