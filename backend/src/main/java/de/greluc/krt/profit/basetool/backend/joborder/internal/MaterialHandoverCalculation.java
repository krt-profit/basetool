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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Calculates material-line bookings from immutable demand and handover facts. */
public final class MaterialHandoverCalculation {
  /** Tolerance for residual floating-point noise in three-decimal physical quantities. */
  static final double EPSILON = 1e-4;

  private MaterialHandoverCalculation() {}

  /**
   * One deliberate physical handover in request order.
   *
   * @param rowId the named physical source
   * @param materialId the source material
   * @param quality the source's observed quality
   * @param amount the deliberate handover quantity
   * @param tierCode an explicit requirement tier, or null for the default highest suitable floor
   */
  record Input(
      @NotNull UUID rowId,
      @NotNull UUID materialId,
      int quality,
      double amount,
      @Nullable String tierCode) {}

  /**
   * The part of one request entry credited to one requirement line.
   *
   * @param lineId the credited requirement line
   * @param amount the credited quantity, at SCU scale and positive
   */
  record LineCredit(@NotNull UUID lineId, double amount) {}

  /**
   * How one request entry is booked: its credits in take order and the part no line could take.
   *
   * @param rowId the named physical source
   * @param tierCode the code of the first selected line, or null when no line matches the material
   * @param credits the per-line credits in take order
   * @param surplus the quantity beyond every eligible line's remaining need, at SCU scale
   */
  record Booking(
      @NotNull UUID rowId,
      @Nullable String tierCode,
      @NotNull List<LineCredit> credits,
      double surplus) {
    Booking {
      credits = List.copyOf(credits);
    }
  }

  /** A complete booking result or a precise requirement-validation refusal. */
  sealed interface Result permits Booked, Refused {}

  /**
   * The bookings of every request entry and the exact fulfillment effects of the handover.
   *
   * @param bookings the booking of each request entry, in request order
   * @param fulfilledMaterials touched materials whose every requirement is fulfilled
   * @param allFulfilled whether every material requirement is fulfilled
   */
  record Booked(
      @NotNull List<Booking> bookings, @NotNull Set<UUID> fulfilledMaterials, boolean allFulfilled)
      implements Result {
    Booked {
      bookings = List.copyOf(bookings);
      fulfilledMaterials = Set.copyOf(fulfilledMaterials);
    }
  }

  /** The requirement condition that prevents the entire handover. */
  enum Refusal {
    /** The explicit tier does not occur on the material order. */
    UNKNOWN_TIER,
    /** The physical stock cannot satisfy the selected or any required tier. */
    QUALITY_BELOW_FLOOR
  }

  /** One requirement refusal without any mutation. */
  record Refused(@NotNull Refusal reason) implements Result {}

  /**
   * Books every entry against the lines of its material: the explicit tier first, then the other
   * lines its quality meets by descending floor, each up to its remaining need (REQ-ORDERS-038).
   *
   * @param lines the order's requirement lines with their remaining need
   * @param inputs the deliberate handover entries in request order
   * @return the bookings, or the refusal that prevents the whole handover
   */
  @NotNull
  static Result calculate(
      @NotNull List<MaterialHandoverRequirement> lines, @NotNull List<Input> inputs) {
    Map<UUID, Double> remaining = new LinkedHashMap<>();
    lines.forEach(line -> remaining.put(line.id(), line.remaining()));
    List<Booking> bookings = new ArrayList<>();
    Set<UUID> touched = new LinkedHashSet<>();
    for (Input input : inputs) {
      List<MaterialHandoverRequirement> matching =
          lines.stream().filter(line -> line.materialId().equals(input.materialId())).toList();
      List<MaterialHandoverRequirement> eligible =
          new ArrayList<>(
              matching.stream()
                  .filter(line -> input.quality() >= line.floor())
                  .sorted(Comparator.comparingInt(MaterialHandoverRequirement::floor).reversed())
                  .toList());
      if (input.tierCode() != null && !input.tierCode().isBlank()) {
        String code = input.tierCode().trim().toUpperCase(Locale.ROOT);
        if (matching.stream().noneMatch(line -> line.tierCode().equals(code))) {
          return new Refused(Refusal.UNKNOWN_TIER);
        }
        MaterialHandoverRequirement chosen =
            eligible.stream().filter(line -> line.tierCode().equals(code)).findFirst().orElse(null);
        if (chosen == null) {
          return new Refused(Refusal.QUALITY_BELOW_FLOOR);
        }
        eligible.remove(chosen);
        eligible.addFirst(chosen);
      }
      if (eligible.isEmpty() && !matching.isEmpty()) {
        return new Refused(Refusal.QUALITY_BELOW_FLOOR);
      }
      double left = input.amount();
      List<LineCredit> credits = new ArrayList<>();
      for (MaterialHandoverRequirement line : eligible) {
        if (left <= EPSILON) {
          break;
        }
        double current = Objects.requireNonNull(remaining.get(line.id()));
        double take = ScuAmounts.roundToScuScale(Math.min(left, current));
        if (take > EPSILON) {
          remaining.put(line.id(), Math.max(0.0, ScuAmounts.roundToScuScale(current - take)));
          credits.add(new LineCredit(line.id(), take));
          left -= take;
        }
      }
      double surplus = left > EPSILON ? ScuAmounts.roundToScuScale(left) : 0.0;
      bookings.add(
          new Booking(
              input.rowId(),
              eligible.isEmpty() ? null : eligible.getFirst().tierCode(),
              credits,
              surplus));
      touched.add(input.materialId());
    }
    Set<UUID> fulfilled = new LinkedHashSet<>();
    for (UUID material : touched) {
      if (lines.stream()
          .filter(line -> line.materialId().equals(material))
          .allMatch(line -> Objects.requireNonNull(remaining.get(line.id())) <= EPSILON)) {
        fulfilled.add(material);
      }
    }
    return new Booked(
        bookings, fulfilled, remaining.values().stream().allMatch(amount -> amount <= EPSILON));
  }
}
