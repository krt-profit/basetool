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

package de.greluc.krt.profit.basetool.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import de.greluc.krt.profit.basetool.backend.service.QualityBucketAllocator.Allocation;
import de.greluc.krt.profit.basetool.backend.service.QualityBucketAllocator.Demand;
import de.greluc.krt.profit.basetool.backend.service.QualityBucketAllocator.Supply;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link QualityBucketAllocator} (REQ-ORDERS-037). */
class QualityBucketAllocatorTest {

  private static final double EPS = 1e-9;

  private static Supply row(int quality, double amount) {
    return new Supply(UUID.randomUUID(), quality, amount);
  }

  @Test
  void singleDemand_takesAllQualifyingStockIncludingSurplus() {
    Allocation<String> result =
        QualityBucketAllocator.allocate(
            List.of(new Demand<>("NONE", 0, 5.0)), List.of(row(400, 3.0), row(700, 4.0)));

    assertThat(result.attributedTo("NONE")).isCloseTo(7.0, within(EPS));
    assertThat(result.unattributed()).isCloseTo(0.0, within(EPS));
  }

  @Test
  void singleFloorDemand_ignoresStockBelowTheFloor() {
    Allocation<String> result =
        QualityBucketAllocator.allocate(
            List.of(new Demand<>("GOOD", 650, 5.0)), List.of(row(400, 3.0), row(700, 4.0)));

    assertThat(result.attributedTo("GOOD")).isCloseTo(4.0, within(EPS));
    assertThat(result.unattributed()).isCloseTo(3.0, within(EPS));
  }

  @Test
  void goodAndNone_sameHighGradeStock_countsEveryUnitOnce() {
    Allocation<String> result =
        QualityBucketAllocator.allocate(
            List.of(new Demand<>("GOOD", 650, 0.1), new Demand<>("NONE", 0, 2.64)),
            List.of(
                row(681, 1.0), row(681, 0.655), row(681, 0.369), row(681, 0.178), row(681, 0.438)));

    assertThat(result.attributedTo("GOOD")).isCloseTo(0.1, within(EPS));
    assertThat(result.attributedTo("NONE")).isCloseTo(2.54, within(EPS));
    assertThat(result.attributedTo("GOOD") + result.attributedTo("NONE"))
        .isCloseTo(2.64, within(EPS));
  }

  @Test
  void higherFloor_takesHighGradeFirst_lowGradeServesTheLowerDemand() {
    Allocation<String> result =
        QualityBucketAllocator.allocate(
            List.of(new Demand<>("NONE", 0, 3.0), new Demand<>("GOOD", 650, 2.0)),
            List.of(row(300, 3.0), row(800, 2.0)));

    assertThat(result.attributedTo("GOOD")).isCloseTo(2.0, within(EPS));
    assertThat(result.attributedTo("NONE")).isCloseTo(3.0, within(EPS));
  }

  @Test
  void higherFloor_prefersTheLowestQualifyingGrade() {
    Supply mid = row(660, 1.0);
    Supply top = row(990, 1.0);
    Allocation<String> result =
        QualityBucketAllocator.allocate(
            List.of(new Demand<>("GOOD", 650, 1.0), new Demand<>("EXCELLENT", 900, 0.0)),
            List.of(top, mid));

    assertThat(result.byRow().get(mid.rowId())).containsEntry("GOOD", 1.0);
    assertThat(result.byRow().get(top.rowId())).containsEntry("EXCELLENT", 1.0);
  }

  @Test
  void surplus_goesToTheHighestFloorItSatisfies() {
    Supply high = row(700, 5.0);
    Allocation<String> result =
        QualityBucketAllocator.allocate(
            List.of(new Demand<>("GOOD", 650, 1.0), new Demand<>("NONE", 0, 1.0)), List.of(high));

    assertThat(result.attributedTo("GOOD")).isCloseTo(4.0, within(EPS));
    assertThat(result.attributedTo("NONE")).isCloseTo(1.0, within(EPS));
  }

  @Test
  void lowGradeSurplus_goesToTheBaseDemand() {
    Allocation<String> result =
        QualityBucketAllocator.allocate(
            List.of(new Demand<>("GOOD", 650, 1.0), new Demand<>("NONE", 0, 1.0)),
            List.of(row(100, 4.0), row(700, 1.0)));

    assertThat(result.attributedTo("GOOD")).isCloseTo(1.0, within(EPS));
    assertThat(result.attributedTo("NONE")).isCloseTo(4.0, within(EPS));
  }

  @Test
  void shortfall_isLeftOnTheHigherDemand_notStolenFromTheLower() {
    Allocation<String> result =
        QualityBucketAllocator.allocate(
            List.of(new Demand<>("GOOD", 650, 5.0), new Demand<>("NONE", 0, 2.0)),
            List.of(row(400, 2.0), row(700, 1.0)));

    assertThat(result.attributedTo("GOOD")).isCloseTo(1.0, within(EPS));
    assertThat(result.attributedTo("NONE")).isCloseTo(2.0, within(EPS));
  }

  @Test
  void threeTiers_nestedFloorsAreServedHighestFirst() {
    Allocation<String> result =
        QualityBucketAllocator.allocate(
            List.of(
                new Demand<>("NONE", 0, 2.0),
                new Demand<>("GOOD", 650, 2.0),
                new Demand<>("EXCELLENT", 900, 2.0)),
            List.of(row(950, 3.0), row(700, 2.0), row(100, 1.0)));

    assertThat(result.attributedTo("EXCELLENT")).isCloseTo(2.0, within(EPS));
    assertThat(result.attributedTo("GOOD")).isCloseTo(2.0, within(EPS));
    assertThat(result.attributedTo("NONE")).isCloseTo(2.0, within(EPS));
  }

  @Test
  void equalFloors_areServedInListOrder() {
    Allocation<Integer> result =
        QualityBucketAllocator.allocate(
            List.of(new Demand<>(0, 650, 1.0), new Demand<>(1, 650, 1.0)), List.of(row(700, 1.5)));

    assertThat(result.attributedTo(0)).isCloseTo(1.0, within(EPS));
    assertThat(result.attributedTo(1)).isCloseTo(0.5, within(EPS));
  }

  @Test
  void nullQualityCountsAsZero_andNegativeAmountsCountAsZero() {
    Allocation<String> result =
        QualityBucketAllocator.allocate(
            List.of(new Demand<>("GOOD", 650, 1.0), new Demand<>("NONE", 0, -3.0)),
            List.of(new Supply(UUID.randomUUID(), null, 2.0), row(700, -1.0)));

    assertThat(result.attributedTo("GOOD")).isCloseTo(0.0, within(EPS));
    assertThat(result.attributedTo("NONE")).isCloseTo(2.0, within(EPS));
  }

  @Test
  void noStock_attributesNothing_andEveryDemandKeyIsPresent() {
    Allocation<String> result =
        QualityBucketAllocator.allocate(
            List.of(new Demand<>("GOOD", 650, 1.0), new Demand<>("NONE", 0, 1.0)), List.of());

    assertThat(result.byDemand()).containsOnlyKeys("GOOD", "NONE");
    assertThat(result.attributedTo("GOOD")).isZero();
    assertThat(result.byRow()).isEmpty();
  }

  @Test
  void duplicateKeys_areRejected() {
    assertThatThrownBy(
            () ->
                QualityBucketAllocator.allocate(
                    List.of(new Demand<>("GOOD", 650, 1.0), new Demand<>("GOOD", 650, 1.0)),
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rowAttributionsSumToTheDemandTotals() {
    Supply a = row(700, 2.0);
    Supply b = row(300, 2.0);
    Allocation<String> result =
        QualityBucketAllocator.allocate(
            List.of(new Demand<>("GOOD", 650, 1.0), new Demand<>("NONE", 0, 2.5)), List.of(a, b));

    double good =
        result.byRow().values().stream().mapToDouble(m -> m.getOrDefault("GOOD", 0.0)).sum();
    double none =
        result.byRow().values().stream().mapToDouble(m -> m.getOrDefault("NONE", 0.0)).sum();
    assertThat(good).isCloseTo(result.attributedTo("GOOD"), within(EPS));
    assertThat(none).isCloseTo(result.attributedTo("NONE"), within(EPS));
  }

  @Test
  void randomCases_neverCountAUnitTwice_andCoverAsMuchAsAnyAssignment() {
    Random random = new Random(42);
    int[] floors = {0, 650, 800, 900};
    for (int run = 0; run < 500; run++) {
      List<Demand<Integer>> demands = new ArrayList<>();
      for (int i = 0; i < floors.length; i++) {
        if (random.nextBoolean()) {
          demands.add(new Demand<>(i, floors[i], random.nextInt(6)));
        }
      }
      List<Supply> supplies = new ArrayList<>();
      int rows = random.nextInt(5);
      double totalStock = 0.0;
      for (int r = 0; r < rows; r++) {
        double amount = random.nextInt(5);
        supplies.add(row(random.nextInt(1001), amount));
        totalStock += amount;
      }
      Allocation<Integer> result = QualityBucketAllocator.allocate(demands, supplies);

      double attributed =
          result.byDemand().values().stream().mapToDouble(Double::doubleValue).sum();
      assertThat(attributed + result.unattributed()).isCloseTo(totalStock, within(1e-6));

      double covered = 0.0;
      for (Demand<Integer> demand : demands) {
        covered += Math.min(demand.need(), result.attributedTo(demand.key()));
      }
      assertThat(covered).isCloseTo(maxCoverage(demands, supplies), within(1e-6));
    }
  }

  private static double maxCoverage(List<Demand<Integer>> demands, List<Supply> supplies) {
    List<Demand<Integer>> sorted = new ArrayList<>(demands);
    sorted.sort((x, y) -> Integer.compare(y.floor(), x.floor()));
    double coveredAbove = 0.0;
    for (Demand<Integer> demand : sorted) {
      double eligible =
          supplies.stream()
              .filter(s -> (s.quality() == null ? 0 : s.quality()) >= demand.floor())
              .mapToDouble(Supply::amount)
              .sum();
      coveredAbove = Math.min(coveredAbove + demand.need(), eligible);
    }
    return coveredAbove;
  }
}
