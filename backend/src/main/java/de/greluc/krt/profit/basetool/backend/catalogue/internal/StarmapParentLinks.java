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

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnmodifiableView;

/**
 * The links of UEX location rows by kind, star system and name, as {@link
 * StarmapPlaceRow#nearestLinkedParent} reads them. A child names its parent only by name, so a name
 * that rows of one kind in one star system link to different objects identifies no parent and is
 * left out for good.
 *
 * @param <V> the link value, a starmap object's GUID or id
 */
final class StarmapParentLinks<V> {

  private final Map<StarmapPlaceKind, Map<String, V>> links = new EnumMap<>(StarmapPlaceKind.class);
  private final Map<StarmapPlaceKind, Set<String>> clashing = new EnumMap<>(StarmapPlaceKind.class);

  /**
   * Records a row's link; a second, different link under the same name drops the name.
   *
   * @param row the row, ignored without a name
   * @param link the object the row links to
   */
  void put(@NotNull StarmapPlaceRow row, @NotNull V link) {
    if (row.name() == null) {
      return;
    }
    String key = StarmapNames.key(row.starSystem(), row.name());
    Set<String> clashingOfKind = clashing.computeIfAbsent(row.kind(), _ -> new HashSet<>());
    if (clashingOfKind.contains(key)) {
      return;
    }
    Map<String, V> ofKind = links.computeIfAbsent(row.kind(), _ -> new HashMap<>());
    V previous = ofKind.putIfAbsent(key, link);
    if (previous != null && !previous.equals(link)) {
      ofKind.remove(key);
      clashingOfKind.add(key);
    }
  }

  /**
   * Returns the links recorded so far.
   *
   * @return the unambiguous links by kind and by star system and name, backed by this instance
   */
  @NotNull
  @UnmodifiableView
  Map<StarmapPlaceKind, Map<String, V>> byKind() {
    return Collections.unmodifiableMap(links);
  }
}
