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
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/** Plans and confirms a holder's complete physical lot action before its first stock mutation. */
public interface StockLotWritePlans {

  /** A physical lot operation with a unique step identity in one transaction. */
  sealed interface Intent permits SetLotAmount, RebookLot, RestoreLot {
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

  /**
   * A named historical row restoration before the personal lot operations.
   *
   * @param step the unique action step
   * @param journalReference the real oldest journal entry requiring this row restoration
   * @param request the owning restoration with immutable expected and target states
   */
  record EntryRestore(
      int step, @NotNull UUID journalReference, @NotNull PoolEntryCommands.PoolRestore request) {
    /** Freezes the named expected and target allocation values at the planning boundary. */
    public EntryRestore {
      request =
          new PoolEntryCommands.PoolRestore(
              request.entryId(), freeze(request.expected()), freeze(request.target()));
    }

    /** Returns an immutable copy of the historical placement, allocations and quantity. */
    private static @Nullable PoolEntryCommands.PoolEntryState freeze(
        @Nullable PoolEntryCommands.PoolEntryState state) {
      return state == null
          ? null
          : new PoolEntryCommands.PoolEntryState(
              state.id(),
              state.holder(),
              state.poolUnitId(),
              state.lotKey(),
              state.amount(),
              state.version(),
              Map.copyOf(state.jobOrders()),
              Map.copyOf(state.missions()),
              state.quantityProvenance(),
              state.completeQuantity());
    }
  }

  /**
   * A physically present row's available shared view.
   *
   * @param sharedState the shared state, or null for an existing personal or unassigned row
   */
  record EntryState(@Nullable PoolEntryCommands.PoolEntryState sharedState) {}

  /** Exact physical proof or an explicit old journal boundary for the target quantity. */
  sealed interface QuantityTarget permits ExactQuantity, LegacyQuantity {}

  /**
   * The complete original composition, including a proved empty quantity.
   *
   * @param quantity complete ordered physical and journal-reference evidence
   */
  record ExactQuantity(@NotNull StockQuantityComposition quantity) implements QuantityTarget {}

  /**
   * An old quantity target with only the actual first known writer boundary, when available.
   *
   * @param journalReference real oldest journal entry requiring the unproved quantity restoration
   * @param firstKnownBeforeBoundary first actual complete before-state after the old journal prefix
   */
  record LegacyQuantity(
      @NotNull UUID journalReference, @Nullable StockQuantityComposition firstKnownBeforeBoundary)
      implements QuantityTarget {}

  /**
   * An undo group's same-initial-state pool checks, canonical inverse moves and final quantity.
   *
   * @param step the unique action step
   * @param lot the physical lot
   * @param inversePoolMoves the ordered inverse pool moves, personal before pool destinations
   * @param targetTotal the rounded absolute lot total after those moves
   * @param targetQuantity exact original evidence or the explicit old journal boundary
   */
  record RestoreLot(
      int step,
      @NotNull StockLot lot,
      @NotNull List<RebookCommands.Rebook> inversePoolMoves,
      @NotNull BigDecimal targetTotal,
      @NotNull QuantityTarget targetQuantity)
      implements Intent {
    /** Freezes the group's ordered inverse requests. */
    public RestoreLot {
      inversePoolMoves = List.copyOf(inversePoolMoves);
    }
  }

  /**
   * Immutable declared operations that a locked calling-policy check may only narrow.
   *
   * @param entryRestores the ordered accepted historical prefix
   * @param intents the ordered accepted physical operations
   */
  record Selection(@NotNull List<EntryRestore> entryRestores, @NotNull List<Intent> intents) {
    /** Freezes the operation lists without changing their declared payloads or relative order. */
    public Selection {
      entryRestores = List.copyOf(entryRestores);
      intents = List.copyOf(intents);
    }
  }

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

    /**
     * Returns an explicit physical-presence snapshot for every declared historical row identity.
     *
     * @return immutable named snapshots; empty values mean physical absence only
     */
    @NotNull
    @Unmodifiable
    Map<UUID, Optional<EntryState>> lockedEntries();
  }

  /** An originating-transaction plan with one confirmed operation order and dry-run mode. */
  interface Confirmed {}

  /**
   * The logical before and after composition proved by actual owning stock execution.
   *
   * @param before the complete ordered quantity before this operation
   * @param after the complete ordered quantity after this operation
   */
  record QuantityChange(
      @NotNull StockQuantityComposition before, @NotNull StockQuantityComposition after) {}

  /**
   * The owning effects of one confirmed physical step.
   *
   * @param refusal the ordinary rebooking refusal, or null for an applied/checked step
   * @param offers the Materialbörse offer effects of the step
   * @param quantity actual executed quantity proof, or null for a refused or dry operation
   */
  record Outcome(
      @Nullable RebookCommands.RebookRefusal refusal,
      @NotNull StockChangeEffects offers,
      @Nullable QuantityChange quantity) {}

  /**
   * Locks the complete preliminary mutation resources and returns fresh locked observations. Each
   * historical row appears once, steps are unique across history and personal lists, and accepted
   * restorations are an ordered subset with identical declared payloads.
   *
   * @param holder the personal lot holder; named restorations retain their own actual holders
   * @param readLots every lot the calling sequential policy reads
   * @param declaredHistoricalEntries every genuine named historical observation in declared order
   * @param preliminaryEntryRestores the accepted ordered subset that can actually mutate stock
   * @param preliminaryIntents the preliminary ordered physical operations
   * @return a handle usable only by the originating transaction
   */
  @NotNull
  Prepared prepareLotBatch(
      @NotNull UUID holder,
      @NotNull Collection<StockLot> readLots,
      @NotNull List<EntryRestore> declaredHistoricalEntries,
      @NotNull List<EntryRestore> preliminaryEntryRestores,
      @NotNull List<Intent> preliminaryIntents);

  /**
   * Plans complete resources and rechecks caller policy after evidence and lot waits. The
   * synchronous selector runs up to twice, after evidence guards and after lot locks, without
   * writes or new locks; each result may only drop unchanged payloads from the preceding selection
   * in their original order, and confirmation may narrow it further.
   *
   * @param holder the personal lot holder; named restorations retain their own actual holders
   * @param readLots every lot the calling sequential policy reads
   * @param declaredHistoricalEntries every genuine named historical observation in declared order
   * @param preliminaryEntryRestores the accepted ordered subset that can actually mutate stock
   * @param preliminaryIntents the preliminary ordered physical operations
   * @param afterResourceWaits the read-only policy after evidence guards and after lot locks
   * @return a handle usable only by the originating transaction
   */
  @NotNull
  Prepared prepareLotBatch(
      @NotNull UUID holder,
      @NotNull Collection<StockLot> readLots,
      @NotNull List<EntryRestore> declaredHistoricalEntries,
      @NotNull List<EntryRestore> preliminaryEntryRestores,
      @NotNull List<Intent> preliminaryIntents,
      @NotNull UnaryOperator<Selection> afterResourceWaits);

  /**
   * Resimulates the final calling-policy decisions within the already closed resource plan.
   *
   * @param prepared the originating transaction's prepared observations
   * @param finalEntryRestores the exact final ordered historical prefix after policy re-evaluation
   * @param finalOrderedIntents the exact final operation order after policy re-evaluation
   * @param dryRun whether execution must leave physical stock unchanged
   * @return the confirmed plan for subsequent ordered execution
   */
  @NotNull
  Confirmed confirmLotBatch(
      @NotNull Prepared prepared,
      @NotNull List<EntryRestore> finalEntryRestores,
      @NotNull List<Intent> finalOrderedIntents,
      boolean dryRun);

  /**
   * Executes the confirmed historical prefix exactly once before any personal operation.
   *
   * @param confirmed the originating transaction's confirmed plan and fixed mode
   * @return the owning result of each requested restore in the confirmed prefix order
   */
  @NotNull
  @Unmodifiable
  List<PoolEntryCommands.PoolRestoreResult> executePreparedEntryRestores(
      @NotNull Confirmed confirmed);

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
