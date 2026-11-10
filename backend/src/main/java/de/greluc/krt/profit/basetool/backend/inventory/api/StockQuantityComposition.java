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

package de.greluc.krt.profit.basetool.backend.inventory.api;

import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * Preserves the complete ordered physical quantity evidence without regrouping its kinds.
 *
 * @param parts immutable physical intervals and real journal references in their observed order
 */
public record StockQuantityComposition(@NotNull List<StockQuantityEvidence> parts) {
  /** The known empty physical quantity, distinct from missing historical evidence. */
  public static final StockQuantityComposition EMPTY = new StockQuantityComposition(List.of());

  /** Freezes the complete observed order without inferring missing quantity evidence. */
  public StockQuantityComposition {
    parts = List.copyOf(parts);
  }

  /**
   * Wraps known physical intervals without changing their supplied order or identities.
   *
   * @param quantity proved physical intervals
   * @return the ordered known composition
   */
  public static @NotNull StockQuantityComposition known(
      @NotNull List<StockQuantityFragment> quantity) {
    return new StockQuantityComposition(
        quantity.stream()
            .<StockQuantityEvidence>map(StockQuantityEvidence.KnownPhysical::new)
            .toList());
  }

  /**
   * Sums every physical amount, including unverifiable restored quantity.
   *
   * @return total thousandths of a unit without arithmetic wraparound
   * @throws ArithmeticException when the complete amount does not fit a long
   */
  public long totalQuanta() {
    return parts.stream().mapToLong(StockQuantityEvidence::quanta).reduce(0L, Math::addExact);
  }

  /**
   * Selects only proved physical intervals while retaining their observed relative order.
   *
   * @return the quantity eligible for an actual new provision budget
   */
  public @NotNull List<StockQuantityFragment> knownPhysical() {
    List<StockQuantityFragment> quantity = new ArrayList<>();
    for (StockQuantityEvidence part : parts) {
      if (part instanceof StockQuantityEvidence.KnownPhysical known) {
        quantity.add(known.quantity());
      }
    }
    return List.copyOf(quantity);
  }

  /**
   * Selects journal references while retaining their observed relative order.
   *
   * @return unverifiable quantity references, never physical origins
   */
  public @NotNull List<LegacyJournalQuantityReference> legacyReferences() {
    List<LegacyJournalQuantityReference> references = new ArrayList<>();
    for (StockQuantityEvidence part : parts) {
      if (part instanceof LegacyJournalQuantityReference reference) {
        references.add(reference);
      }
    }
    return List.copyOf(references);
  }
}
