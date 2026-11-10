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

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/** Plans and confirms a holder's complete physical lot action before its first stock mutation. */
public interface StockLotWritePlans {

  /** A physical lot operation with a unique step identity in one transaction. */
  sealed interface Intent permits SetLotAmount, RebookLot {
    /**
     * Returns the operation's identity within the containing action.
     *
     * @return the unique step index
     */
    int step();
  }

  /**
   * A lot quantity change whose expected total has been checked by the calling policy.
   *
   * @param step the unique action step
   * @param lot the physical lot
   * @param observedTotal the rounded observed or expected total before the step
   * @param target the rounded total requested after the step
   */
  record SetLotAmount(
      int step,
      @NotNull StockLot lot,
      @NotNull BigDecimal observedTotal,
      @NotNull BigDecimal target)
      implements Intent {}

  /**
   * A personal/pool rebooking within a holder's physical lot.
   *
   * @param step the unique action step
   * @param request the owning Inventory rebooking request
   */
  record RebookLot(int step, @NotNull RebookCommands.Rebook request) implements Intent {}

  /** The locked immutable lot observations needed by the calling policy before confirmation. */
  interface Prepared {
    /**
     * Returns the holder's immutable locked physical snapshots by canonical lot key.
     *
     * @return every declared read lot, including empty lots
     */
    @NotNull
    @Unmodifiable
    Map<String, List<StockRow>> lockedRows();
  }

  /** An originating-transaction plan with one confirmed operation order and dry-run mode. */
  interface Confirmed {}

  /**
   * The owning effects of one confirmed physical step.
   *
   * @param refusal the ordinary rebooking refusal, or null for an applied/checked step
   * @param offers the Materialbörse offer effects of the step
   */
  record Outcome(
      @Nullable RebookCommands.RebookRefusal refusal, @NotNull StockChangeEffects offers) {}

  /**
   * Locks the complete preliminary mutation resources and returns fresh locked observations.
   *
   * @param holder the holder of every declared lot and operation
   * @param readLots every lot the calling sequential policy reads
   * @param preliminaryIntents the preliminary ordered physical operations
   * @return a handle usable only by the originating transaction
   */
  @NotNull
  Prepared prepareLotBatch(
      @NotNull UUID holder,
      @NotNull Collection<StockLot> readLots,
      @NotNull List<Intent> preliminaryIntents);

  /**
   * Resimulates the final calling-policy decisions within the already closed resource plan.
   *
   * @param prepared the originating transaction's prepared observations
   * @param finalOrderedIntents the exact final operation order after policy re-evaluation
   * @param dryRun whether execution must leave physical stock unchanged
   * @return the confirmed plan for subsequent ordered execution
   */
  @NotNull
  Confirmed confirmLotBatch(
      @NotNull Prepared prepared, @NotNull List<Intent> finalOrderedIntents, boolean dryRun);

  /**
   * Executes one confirmed step through the ordinary owning Inventory commands.
   *
   * @param confirmed the originating transaction's confirmed plan
   * @param step the next confirmed step, executed at most once
   * @param dryRun the mode fixed by confirmation
   * @return the ordinary owning refusal and offer effects
   */
  @NotNull
  Outcome executePreparedLot(@NotNull Confirmed confirmed, int step, boolean dryRun);
}
