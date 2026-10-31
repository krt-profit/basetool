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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.inventory.internal.StockLevelAllocation.Fill;
import de.greluc.krt.profit.basetool.backend.inventory.internal.StockLevelAllocation.Level;
import de.greluc.krt.profit.basetool.backend.inventory.internal.StockLevelAllocation.Lot;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StockLevelAllocation} on plain rows: what counts as free stock and how it
 * is allocated top tier down (REQ-INV-067, ADR-0302).
 */
class StockLevelAllocationTest {

  private static final UUID TOP = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID MID = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID ANY = UUID.fromString("00000000-0000-0000-0000-000000000003");

  private static Lot shared(int quality, double amount) {
    return new Lot(quality, amount, 0.0, false, false);
  }

  @Test
  void singleUntieredLevel_countsEveryQualityAcrossLots() {
    Map<UUID, Fill> fills =
        StockLevelAllocation.allocate(
            List.of(new Level(ANY, null, 10.0, 20.0)), List.of(shared(100, 4.0), shared(900, 3.0)));

    assertThat(fills.get(ANY)).isEqualTo(new Fill(7.0, 13.0, 0.7, true));
  }

  @Test
  void personalStolenAndEarmarkedStock_neverCounts() {
    Map<UUID, Fill> fills =
        StockLevelAllocation.allocate(
            List.of(new Level(ANY, null, 5.0, 5.0)),
            List.of(
                new Lot(500, 10.0, 4.0, false, false),
                new Lot(500, 8.0, 0.0, true, false),
                new Lot(500, 6.0, 0.0, false, true)));

    assertThat(fills.get(ANY).allocated()).isEqualTo(6.0);
  }

  @Test
  void earmarksAboveTheAmount_leaveNoNegativeFreeStock() {
    Map<UUID, Fill> fills =
        StockLevelAllocation.allocate(
            List.of(new Level(ANY, null, 1.0, 1.0)),
            List.of(new Lot(500, 2.0, 3.0, false, false), shared(500, 1.5)));

    assertThat(fills.get(ANY).allocated()).isEqualTo(1.5);
  }

  @Test
  void higherFloorFillsToItsTargetFirst_andTheRestFlowsDown() {
    Map<UUID, Fill> fills =
        StockLevelAllocation.allocate(
            List.of(
                new Level(ANY, null, 10.0, 10.0),
                new Level(TOP, 800, 2.0, 5.0),
                new Level(MID, 500, 4.0, 6.0)),
            List.of(shared(900, 8.0), shared(600, 3.0), shared(100, 1.0)));

    assertThat(fills.get(TOP)).isEqualTo(new Fill(5.0, 0.0, 2.5, false));
    assertThat(fills.get(MID)).isEqualTo(new Fill(6.0, 0.0, 1.5, false));
    assertThat(fills.get(ANY)).isEqualTo(new Fill(1.0, 9.0, 0.1, true));
  }

  @Test
  void lowestLevel_takesTheWholeRemainderBeyondItsTarget() {
    Map<UUID, Fill> fills =
        StockLevelAllocation.allocate(
            List.of(new Level(TOP, 800, 1.0, 2.0), new Level(ANY, null, 1.0, 3.0)),
            List.of(shared(900, 10.0)));

    assertThat(fills.get(TOP).allocated()).isEqualTo(2.0);
    assertThat(fills.get(ANY)).isEqualTo(new Fill(8.0, 0.0, 8.0, false));
  }

  @Test
  void lowestTieredLevel_takesOnlyStockAtItsFloor() {
    Map<UUID, Fill> fills =
        StockLevelAllocation.allocate(
            List.of(new Level(MID, 500, 4.0, 4.0)), List.of(shared(499, 9.0), shared(500, 3.0)));

    assertThat(fills.get(MID)).isEqualTo(new Fill(3.0, 1.0, 0.75, true));
  }

  @Test
  void topTierStock_isNotCountedTwice() {
    Map<UUID, Fill> fills =
        StockLevelAllocation.allocate(
            List.of(new Level(TOP, 800, 5.0, 5.0), new Level(ANY, null, 5.0, 5.0)),
            List.of(shared(900, 5.0)));

    assertThat(fills.get(TOP).low()).isFalse();
    assertThat(fills.get(ANY)).isEqualTo(new Fill(0.0, 5.0, 0.0, true));
  }

  @Test
  void levelExactlyAtItsMinimum_isNotLow() {
    Map<UUID, Fill> fills =
        StockLevelAllocation.allocate(
            List.of(new Level(ANY, null, 3.0, 6.0)), List.of(shared(0, 3.0)));

    assertThat(fills.get(ANY)).isEqualTo(new Fill(3.0, 3.0, 1.0, false));
  }

  @Test
  void tierWithFloorZero_isServedBeforeTheUntieredLevel() {
    Map<UUID, Fill> fills =
        StockLevelAllocation.allocate(
            List.of(new Level(ANY, null, 1.0, 1.0), new Level(MID, 0, 2.0, 2.0)),
            List.of(shared(0, 2.5)));

    assertThat(fills.get(MID).allocated()).isEqualTo(2.0);
    assertThat(fills.get(ANY).allocated()).isEqualTo(0.5);
  }

  @Test
  void noStock_leavesEveryLevelEmptyAndLow() {
    Map<UUID, Fill> fills =
        StockLevelAllocation.allocate(List.of(new Level(ANY, null, 2.0, 4.0)), List.of());

    assertThat(fills.get(ANY)).isEqualTo(new Fill(0.0, 4.0, 0.0, true));
  }
}
