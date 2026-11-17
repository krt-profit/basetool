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

import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kStarmapObjectDto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The starmap places a shop's terminal is looked for at, nearest first (REQ-P4K-043). The shop's
 * starmap object is refined to the deepest object at or below it whose placing entity is one of the
 * containers the shop is placed in; that place and its ancestors up to, not including, the first
 * celestial body follow. The entity names come from the run's catalogue, so a catalogue without
 * starmap objects refines nothing.
 */
final class ShopPlaces {

  /** The type families of the celestial bodies a place's ancestors end at. */
  private static final Set<String> CELESTIAL =
      Set.of("star", "solarsystem", "planet", "moon", "asteroid", "jumppoint", "anomaly");

  /** The stored objects by id. */
  private final Map<UUID, StarmapObject> byId = new HashMap<>();

  /** The ids of the objects by the lower-case entity name that places them. */
  private final Map<String, List<UUID>> idsByEntityName = new HashMap<>();

  /**
   * Indexes the stored objects and the entity names the run's catalogue gives them.
   *
   * @param objects the stored starmap objects
   * @param records the run's starmap records
   */
  ShopPlaces(@NotNull List<StarmapObject> objects, @NotNull List<P4kStarmapObjectDto> records) {
    Map<UUID, UUID> idByGuid = new HashMap<>();
    for (StarmapObject object : objects) {
      UUID id = object.getId();
      if (id != null) {
        byId.put(id, object);
        idByGuid.put(object.getP4kUuid(), id);
      }
    }
    for (P4kStarmapObjectDto record : records) {
      UUID guid = record.parsedGuid();
      UUID id = guid == null ? null : idByGuid.get(guid);
      String entityName = record.entityName();
      if (id != null && entityName != null && !entityName.isBlank()) {
        idsByEntityName
            .computeIfAbsent(entityName.toLowerCase(Locale.ROOT), _ -> new ArrayList<>())
            .add(id);
      }
    }
  }

  /**
   * Lists the places a shop's terminal is looked for at.
   *
   * @param place the shop's starmap object
   * @param containers the containers the shop is placed in, from the system root down
   * @return the refined place, then its ancestors up to the first celestial body
   */
  @NotNull
  @Unmodifiable
  List<UUID> scopes(@NotNull UUID place, @NotNull List<String> containers) {
    UUID refined = refine(place, containers);
    List<UUID> scopes = new ArrayList<>(List.of(refined));
    Set<UUID> visited = new HashSet<>(scopes);
    UUID current = parentOf(refined);
    while (current != null && visited.add(current) && !isCelestial(current)) {
      scopes.add(current);
      current = parentOf(current);
    }
    return List.copyOf(scopes);
  }

  /**
   * Refines a place to the deepest object at or below it that a container of the shop places.
   *
   * @param place the shop's starmap object
   * @param containers the containers, from the system root down
   * @return the object placed by the deepest such container, else the place
   */
  @NotNull
  private UUID refine(@NotNull UUID place, @NotNull List<String> containers) {
    UUID refined = place;
    for (String container : containers) {
      for (UUID id : idsByEntityName.getOrDefault(container.toLowerCase(Locale.ROOT), List.of())) {
        if (id.equals(place) || isBelow(id, place)) {
          refined = id;
        }
      }
    }
    return refined;
  }

  /**
   * Whether an object lies below another one, at any depth.
   *
   * @param id the object
   * @param ancestor the presumed ancestor
   * @return {@code true} when the ancestor is on the object's parent chain
   */
  private boolean isBelow(@NotNull UUID id, @NotNull UUID ancestor) {
    Set<UUID> visited = new HashSet<>();
    UUID current = parentOf(id);
    while (current != null && visited.add(current)) {
      if (current.equals(ancestor)) {
        return true;
      }
      current = parentOf(current);
    }
    return false;
  }

  /**
   * Returns an object's parent.
   *
   * @param id the object
   * @return the parent's id, or {@code null} for a root or an object the tree lacks
   */
  @Nullable
  private UUID parentOf(@NotNull UUID id) {
    StarmapObject object = byId.get(id);
    return object == null ? null : object.getParentId();
  }

  /**
   * Whether an object is a celestial body.
   *
   * @param id the object
   * @return {@code true} when its type family is a celestial one
   */
  private boolean isCelestial(@NotNull UUID id) {
    StarmapObject object = byId.get(id);
    String family = object == null ? null : StarmapPlaceKind.typeFamily(object.getType());
    return family != null && CELESTIAL.contains(family);
  }
}
