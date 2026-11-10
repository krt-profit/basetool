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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import de.greluc.krt.profit.basetool.backend.inventory.api.LegacyJournalQuantityReference;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityComposition;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityEvidence;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityEvidence.KnownPhysical;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityFragment;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/** Maintains disjoint complete quantity evidence through stock transformations. */
public final class InventoryQuantityComposition {

  private static final Comparator<StockQuantityEvidence> PROOF_ORDER =
      Comparator.comparing(InventoryQuantityComposition::kind)
          .thenComparing(InventoryQuantityComposition::reference)
          .thenComparingLong(InventoryQuantityComposition::from);

  private InventoryQuantityComposition() {}

  /**
   * Validates disjoint proof without changing its observed order.
   *
   * @param quantity the complete ordered physical quantity
   * @return the same immutable composition
   */
  static @NotNull StockQuantityComposition validated(@NotNull StockQuantityComposition quantity) {
    StockQuantityEvidence previous = null;
    for (StockQuantityEvidence part : quantity.parts().stream().sorted(PROOF_ORDER).toList()) {
      if (previous != null && sameProof(previous, part) && from(part) < to(previous)) {
        throw new IllegalArgumentException("Physical quantity evidence must not overlap");
      }
      previous = part;
    }
    return quantity;
  }

  /**
   * Coalesces adjacent evidence, retaining mixed order and the established known-only order.
   *
   * @param parts complete intervals to combine
   * @return immutable disjoint composition
   */
  static @NotNull StockQuantityComposition canonical(
      @NotNull Collection<StockQuantityEvidence> parts) {
    List<StockQuantityEvidence> ordered =
        parts.stream().allMatch(KnownPhysical.class::isInstance)
            ? parts.stream().sorted(PROOF_ORDER).toList()
            : List.copyOf(parts);
    validated(new StockQuantityComposition(ordered));
    List<StockQuantityEvidence> result = new ArrayList<>();
    for (StockQuantityEvidence part : ordered) {
      if (!result.isEmpty()) {
        StockQuantityEvidence previous = result.getLast();
        if (sameProof(previous, part) && to(previous) == from(part)) {
          result.set(result.size() - 1, slice(previous, from(previous), to(part)));
          continue;
        }
      }
      result.add(part);
    }
    return new StockQuantityComposition(result);
  }

  /**
   * Divides complete quantity in its actual observed order without changing either proof kind.
   *
   * @param quantity the complete composition
   * @param amount leading integral quantity
   * @return disjoint leading and remaining compositions
   */
  static @NotNull Partition partition(@NotNull StockQuantityComposition quantity, long amount) {
    if (amount < 0L || amount > quantity.totalQuanta()) {
      throw new IllegalArgumentException("A quantity part must fit its physical stock");
    }
    validated(quantity);
    List<StockQuantityEvidence> taken = new ArrayList<>();
    List<StockQuantityEvidence> remaining = new ArrayList<>();
    long needed = amount;
    for (StockQuantityEvidence part : quantity.parts()) {
      long take = Math.min(needed, part.quanta());
      if (take > 0L) {
        taken.add(slice(part, from(part), Math.addExact(from(part), take)));
      }
      if (take < part.quanta()) {
        remaining.add(slice(part, Math.addExact(from(part), take), to(part)));
      }
      needed -= take;
    }
    return new Partition(
        new StockQuantityComposition(taken), new StockQuantityComposition(remaining));
  }

  /**
   * Retains complete evidence after a loss or adds only genuine new physical quantity.
   *
   * @param quantity existing complete composition
   * @param amount resulting integral physical amount
   * @return resulting complete composition
   */
  static @NotNull StockQuantityComposition resized(
      @NotNull StockQuantityComposition quantity, long amount) {
    if (amount < 0L) {
      throw new IllegalArgumentException("Physical stock cannot be negative");
    }
    long previous = quantity.totalQuanta();
    if (amount <= previous) {
      return partition(quantity, previous - amount).remaining();
    }
    List<StockQuantityEvidence> expanded = new ArrayList<>(quantity.parts());
    expanded.add(
        new KnownPhysical(new StockQuantityFragment(UUID.randomUUID(), 0L, amount - previous)));
    return canonical(expanded);
  }

  /**
   * Preserves surviving current proof and references only the unproved positive restoration delta.
   *
   * @param current the actual surviving or journal-proved boundary composition
   * @param amount the old absolute integral quantity target
   * @param journalReference the real oldest journal entry requiring the restoration
   * @return complete quantity evidence without a new physical origin
   */
  static @NotNull StockQuantityComposition restoredLegacy(
      @NotNull StockQuantityComposition current, long amount, @NotNull UUID journalReference) {
    if (amount < 0L) {
      throw new IllegalArgumentException("Restored stock cannot be negative");
    }
    long previous = current.totalQuanta();
    if (amount <= previous) {
      return partition(current, previous - amount).remaining();
    }
    long offset =
        current.legacyReferences().stream()
            .filter(reference -> reference.journalReference().equals(journalReference))
            .mapToLong(LegacyJournalQuantityReference::toQuanta)
            .max()
            .orElse(0L);
    List<StockQuantityEvidence> restored = new ArrayList<>(current.parts());
    restored.add(
        new LegacyJournalQuantityReference(
            journalReference, offset, Math.addExact(offset, amount - previous)));
    return canonical(restored);
  }

  /**
   * Subtracts exact proved parts while retaining unrelated physical and journal-reference evidence.
   *
   * @param quantity complete current composition
   * @param removed complete exact intervals to remove
   * @return surviving quantity in the original observed order
   */
  static @NotNull StockQuantityComposition subtract(
      @NotNull StockQuantityComposition quantity, @NotNull StockQuantityComposition removed) {
    validated(quantity);
    List<StockQuantityEvidence> blocked =
        validated(removed).parts().stream().sorted(PROOF_ORDER).toList();
    List<StockQuantityEvidence> result = new ArrayList<>();
    for (StockQuantityEvidence candidate : quantity.parts()) {
      long cursor = from(candidate);
      for (StockQuantityEvidence used : blocked) {
        if (!sameProof(candidate, used) || to(used) <= cursor) {
          continue;
        }
        if (from(used) >= to(candidate)) {
          break;
        }
        if (from(used) > cursor) {
          result.add(slice(candidate, cursor, from(used)));
        }
        cursor = Math.max(cursor, to(used));
        if (cursor >= to(candidate)) {
          break;
        }
      }
      if (cursor < to(candidate)) {
        result.add(slice(candidate, cursor, to(candidate)));
      }
    }
    return new StockQuantityComposition(result);
  }

  private static boolean sameProof(StockQuantityEvidence left, StockQuantityEvidence right) {
    return kind(left) == kind(right) && reference(left).equals(reference(right));
  }

  private static Kind kind(StockQuantityEvidence part) {
    return switch (part) {
      case KnownPhysical _ -> Kind.PHYSICAL;
      case LegacyJournalQuantityReference _ -> Kind.JOURNAL;
    };
  }

  private static UUID reference(StockQuantityEvidence part) {
    return switch (part) {
      case KnownPhysical known -> known.quantity().origin();
      case LegacyJournalQuantityReference legacy -> legacy.journalReference();
    };
  }

  private static long from(StockQuantityEvidence part) {
    return switch (part) {
      case KnownPhysical known -> known.quantity().fromQuanta();
      case LegacyJournalQuantityReference legacy -> legacy.fromQuanta();
    };
  }

  private static long to(StockQuantityEvidence part) {
    return switch (part) {
      case KnownPhysical known -> known.quantity().toQuanta();
      case LegacyJournalQuantityReference legacy -> legacy.toQuanta();
    };
  }

  private static StockQuantityEvidence slice(StockQuantityEvidence part, long from, long to) {
    return switch (part) {
      case KnownPhysical known ->
          new KnownPhysical(new StockQuantityFragment(known.quantity().origin(), from, to));
      case LegacyJournalQuantityReference legacy ->
          new LegacyJournalQuantityReference(legacy.journalReference(), from, to);
    };
  }

  /** Disjoint physical origin and journal-reference identity domains. */
  private enum Kind {
    /** A proved durable physical origin. */
    PHYSICAL,
    /** A real journal action with unverifiable original quantity. */
    JOURNAL
  }

  /**
   * One exact division retaining the evidence of every physical part.
   *
   * @param taken leading quantity
   * @param remaining the rest
   */
  record Partition(
      @NotNull StockQuantityComposition taken, @NotNull StockQuantityComposition remaining) {}
}
