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

import de.greluc.krt.profit.basetool.backend.catalogue.api.PairKeyRef;
import de.greluc.krt.profit.basetool.backend.catalogue.api.UexKeyRef;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The id maps of a UEX price-matrix run in which a merged second listing resolves to the row it was
 * merged into, and that listing's price yields to the row's own listing at a terminal both list
 * (REQ-DATA-044, REQ-DATA-046).
 *
 * @param lookups the run's id maps; a merged listing resolves to the row it was merged into
 * @param merged the UEX ids of the merged listings no row holds itself
 */
record UexMergedListings(
    @NotNull UexMatrixLookups lookups, @NotNull @Unmodifiable Set<Integer> merged) {

  /**
   * Builds the run's id maps; a merged listing whose UEX id a row holds itself is the row's own.
   *
   * @param heldRefs the (UEX id, local id) rows of the catalogue parents
   * @param mergedRefs the (UEX id, surviving local id) rows of the merged listings
   * @param terminalRefs the terminals' (UEX id, local id) rows
   * @param rowRefs the matrix rows' (parent, terminal, id) rows
   * @return the id maps and the merged listings they resolve
   */
  @NotNull
  static UexMergedListings of(
      @NotNull List<UexKeyRef> heldRefs,
      @NotNull List<UexKeyRef> mergedRefs,
      @NotNull List<UexKeyRef> terminalRefs,
      @NotNull List<PairKeyRef> rowRefs) {
    List<UexKeyRef> parents = new ArrayList<>(heldRefs);
    Set<Integer> known = new HashSet<>();
    heldRefs.forEach(ref -> known.add(ref.getUexId()));
    Set<Integer> merged = new HashSet<>();
    for (UexKeyRef ref : mergedRefs) {
      if (known.add(ref.getUexId())) {
        merged.add(ref.getUexId());
        parents.add(ref);
      }
    }
    return new UexMergedListings(
        new UexMatrixLookups(parents, terminalRefs, rowRefs), Set.copyOf(merged));
  }

  /**
   * Drops the prices of a merged listing at a terminal where the listing the row holds has a price
   * of its own in the same fetch, so the row's own price wins.
   *
   * @param dtos the fetched prices
   * @param parentOf reads a price's UEX parent id
   * @param terminalOf reads a price's UEX terminal id
   * @param <T> the price type
   * @return the prices to write
   */
  @NotNull
  <T> List<T> withoutListedTwinPrices(
      @NotNull List<T> dtos,
      @NotNull Function<T, Integer> parentOf,
      @NotNull Function<T, Integer> terminalOf) {
    if (merged.isEmpty()) {
      return dtos;
    }
    Set<UexMatrixLookups.Pair> listed = new HashSet<>();
    for (T dto : dtos) {
      UexMatrixLookups.Pair pair = pair(parentOf.apply(dto), terminalOf.apply(dto));
      if (pair != null && !merged.contains(parentOf.apply(dto))) {
        listed.add(pair);
      }
    }
    return dtos.stream()
        .filter(dto ->
            !merged.contains(parentOf.apply(dto))
                || !listed.contains(pair(parentOf.apply(dto), terminalOf.apply(dto))))
        .toList();
  }

  /**
   * Resolves a fetched price to its (parent, terminal) pair.
   *
   * @param parentUexId the price's UEX parent id
   * @param terminalUexId the price's UEX terminal id
   * @return the pair, or {@code null} when the parent or the terminal is unknown
   */
  @Nullable
  private UexMatrixLookups.Pair pair(
      @Nullable Integer parentUexId, @Nullable Integer terminalUexId) {
    UUID parentId = lookups.parentId(parentUexId);
    UUID terminalId = lookups.terminalId(terminalUexId);
    return parentId == null || terminalId == null
        ? null
        : new UexMatrixLookups.Pair(parentId, terminalId);
  }
}
