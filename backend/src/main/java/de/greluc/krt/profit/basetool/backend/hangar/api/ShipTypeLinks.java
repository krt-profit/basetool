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

import de.greluc.krt.profit.basetool.backend.catalogue.api.ClassNameKeyRef;
import de.greluc.krt.profit.basetool.backend.catalogue.api.UexKeyRef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The key links a {@link ShipTypeMatcher} index uses beside the ship types themselves: the P4K
 * variant groups (REQ-P4K-032) and the curated UEX alias of ADR-0307 (REQ-DATA-026).
 *
 * @param variantBaseByClassName the ship type id of a variant's group base, by the variant's
 *     lower-cased class name
 * @param aliasByUexId the ship type ids carrying the class name an alias ties a UEX vehicle id to
 */
public record ShipTypeLinks(
    @NotNull @Unmodifiable Map<String, UUID> variantBaseByClassName,
    @NotNull @Unmodifiable Map<Integer, List<UUID>> aliasByUexId) {

  /** No links: the index matches by the ship types' own keys only. */
  public static final ShipTypeLinks NONE = new ShipTypeLinks(Map.of(), Map.of());

  /**
   * Copies both maps.
   *
   * @param variantBaseByClassName the variant bases by lower-cased class name
   * @param aliasByUexId the aliased ship type ids by UEX vehicle id
   */
  public ShipTypeLinks {
    variantBaseByClassName = Map.copyOf(variantBaseByClassName);
    aliasByUexId =
        aliasByUexId.entrySet().stream()
            .collect(
                Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
  }

  /**
   * Builds the links from the repository's projection rows.
   *
   * @param variantBases one row per variant class name and its base ship type
   * @param aliases one row per UEX vehicle id and aliased ship type
   * @return the links
   */
  @NotNull
  public static ShipTypeLinks of(
      @NotNull List<ClassNameKeyRef> variantBases, @NotNull List<UexKeyRef> aliases) {
    Map<String, UUID> bases = new HashMap<>();
    for (ClassNameKeyRef ref : variantBases) {
      bases.putIfAbsent(ref.getClassName().toLowerCase(Locale.ROOT), ref.getId());
    }
    Map<Integer, List<UUID>> aliased = new HashMap<>();
    for (UexKeyRef ref : aliases) {
      aliased.computeIfAbsent(ref.getUexId(), _ -> new ArrayList<>()).add(ref.getId());
    }
    return new ShipTypeLinks(bases, aliased);
  }
}
