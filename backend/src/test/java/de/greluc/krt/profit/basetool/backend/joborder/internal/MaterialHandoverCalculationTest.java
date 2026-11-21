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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import de.greluc.krt.profit.basetool.backend.joborder.internal.MaterialHandoverCalculation.Booked;
import de.greluc.krt.profit.basetool.backend.joborder.internal.MaterialHandoverCalculation.Booking;
import de.greluc.krt.profit.basetool.backend.joborder.internal.MaterialHandoverCalculation.Input;
import de.greluc.krt.profit.basetool.backend.joborder.internal.MaterialHandoverCalculation.LineCredit;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Pins the per-line credits and the surplus each handover entry is booked with (REQ-ORDERS-077).
 */
class MaterialHandoverCalculationTest {

  private static final UUID MATERIAL = UUID.randomUUID();
  private static final UUID ROW = UUID.randomUUID();

  private static @NotNull MaterialHandoverRequirement line(
      @NotNull String tierCode, int floor, double remaining) {
    return new MaterialHandoverRequirement(
        UUID.randomUUID(), 0L, MATERIAL, UUID.randomUUID(), tierCode, floor, remaining);
  }

  private static @NotNull Input entry(int quality, double amount, @Nullable String tierCode) {
    return new Input(ROW, MATERIAL, quality, amount, tierCode);
  }

  private static @NotNull Booked booked(
      @NotNull List<MaterialHandoverRequirement> lines, @NotNull List<Input> inputs) {
    return (Booked) MaterialHandoverCalculation.calculate(lines, inputs);
  }

  /** A partial delivery credits its line fully and leaves no surplus. */
  @Test
  void aPartialDeliveryIsCreditedInFullToItsLine() {
    MaterialHandoverRequirement good = line("GOOD", 650, 10.0);

    Booked result = booked(List.of(good), List.of(entry(700, 4.0, null)));

    assertThat(result.bookings())
        .containsExactly(new Booking(ROW, "GOOD", List.of(new LineCredit(good.id(), 4.0)), 0.0));
    assertThat(result.allFulfilled()).isFalse();
  }

  /** What no eligible line can take is the entry's surplus, and the line counts as fulfilled. */
  @Test
  void anOverDeliveryCreditsTheNeedAndRecordsTheRestAsSurplus() {
    MaterialHandoverRequirement good = line("GOOD", 650, 2.5);

    Booked result = booked(List.of(good), List.of(entry(700, 4.0, null)));

    assertThat(result.bookings())
        .containsExactly(new Booking(ROW, "GOOD", List.of(new LineCredit(good.id(), 2.5)), 1.5));
    assertThat(result.fulfilledMaterials()).containsExactly(MATERIAL);
    assertThat(result.allFulfilled()).isTrue();
  }

  /** High-grade stock fills the highest floor first and spills into the lower line. */
  @Test
  void aSpillOverCreditsEachLineItTouches() {
    MaterialHandoverRequirement good = line("GOOD", 650, 1.0);
    MaterialHandoverRequirement none = line("NONE", 0, 5.0);

    Booked result = booked(List.of(none, good), List.of(entry(800, 3.0, null)));

    assertThat(result.bookings().getFirst().credits())
        .containsExactly(new LineCredit(good.id(), 1.0), new LineCredit(none.id(), 2.0));
    assertThat(result.bookings().getFirst().surplus()).isZero();
  }

  /** A second entry sees the need the first one already credited. */
  @Test
  void laterEntriesOnlyGetTheNeedEarlierEntriesLeft() {
    MaterialHandoverRequirement none = line("NONE", 0, 3.0);

    Booked result = booked(List.of(none), List.of(entry(100, 2.0, null), entry(100, 2.0, "NONE")));

    assertThat(result.bookings())
        .extracting(Booking::credits, Booking::surplus)
        .containsExactly(
            tuple(List.of(new LineCredit(none.id(), 2.0)), 0.0),
            tuple(List.of(new LineCredit(none.id(), 1.0)), 1.0));
  }

  /** Credits are kept at SCU scale even when the remaining need carries floating-point noise. */
  @Test
  void creditsAndSurplusAreRoundedToScuScale() {
    MaterialHandoverRequirement none = line("NONE", 0, 2.3 - 1.1);

    Booked result = booked(List.of(none), List.of(entry(100, 1.5, null)));

    assertThat(result.bookings().getFirst())
        .isEqualTo(new Booking(ROW, "NONE", List.of(new LineCredit(none.id(), 1.2)), 0.3));
  }
}
