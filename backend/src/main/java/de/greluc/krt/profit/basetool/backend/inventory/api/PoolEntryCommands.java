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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The writes on the shared entries of one org unit's pool that a connected application makes for a
 * Logistician of that pool, through the Lager's own create and book-out (REQ-XCH-045, ADR-0257).
 * Every command joins the caller's transaction ({@code MANDATORY}); the caller decides who may
 * write the pool, the commands decide what the Lager allows.
 *
 * <p>A command answers a refusal instead of throwing for what the Lager refuses by its rules, so
 * the caller can report it per op and go on; it throws only for what should not happen.
 */
public interface PoolEntryCommands {

  /**
   * Takes the advisory lot locks of every holder lot a batch touches, in ascending order of the
   * lock keys, before any row is read (ADR-0229): the given holder lots, and the lot of each named
   * entry as it is now.
   *
   * @param holderLots the stock lot keys ({@link StockLot#key()}) a batch books into, by holder
   * @param entryIds the entries a batch books out of; an unknown id is left out
   */
  void lockLots(@NotNull Map<UUID, Set<String>> holderLots, @NotNull Collection<UUID> entryIds);

  /**
   * Books stock in as a new shared entry of the pool held by {@code holder}, as the Lager's
   * Einbuchen does: with its earmarks, its note and the merge of REQ-INV-026, auditing it as {@code
   * INVENTORY_ITEM_CREATED}.
   *
   * @param bookIn the book-in
   * @param dryRun whether to check it only
   * @return the refusal, or every entry the book-in changed: the one that holds the stock
   *     afterwards and each entry a merge folded into it
   */
  @NotNull
  PoolOutcome bookIn(@NotNull PoolBookIn bookIn, boolean dryRun);

  /**
   * Books an amount out of a shared entry of the pool through the Lager's own book-out, which
   * lowers the entry's Materialbörse offers, audits it and tells its holder.
   *
   * @param bookOut the book-out
   * @param dryRun whether to check it only
   * @return the refusal, or the entry's change and the offers it lowered or removed
   */
  @NotNull
  PoolOutcome bookOut(@NotNull PoolBookOut bookOut, boolean dryRun);

  /**
   * A book-in into a pool, recorded under the current request's acting member.
   *
   * @param holder the member who holds the new entry
   * @param poolUnitId the org unit whose pool receives it
   * @param lot the material or item, location, quality and stolen marker
   * @param amount the amount, rounded to the lot's unit
   * @param note the note, or {@code null} for none
   * @param mergeStock the merge opt-in of an SCU material (REQ-INV-026)
   * @param jobOrders the job-order earmarks
   * @param missions the mission earmarks
   */
  record PoolBookIn(
      @NotNull UUID holder,
      @NotNull UUID poolUnitId,
      @NotNull StockLot lot,
      double amount,
      @Nullable String note,
      boolean mergeStock,
      @NotNull @Unmodifiable List<PoolEarmark> jobOrders,
      @NotNull @Unmodifiable List<PoolEarmark> missions) {}

  /**
   * A book-out of one entry of a pool.
   *
   * @param actor the writer, recorded as the actor
   * @param entryId the entry
   * @param poolUnitId the org unit whose pool the writer names
   * @param version the entry's version the writer last read
   * @param amount the amount to take, rounded to the entry's unit
   * @param unit the amount's unit, {@code SCU} or {@code PIECE}, which must be the entry's
   * @param fromJobOrders how much comes out of each job-order earmark; the rest from the free part
   * @param fromMissions how much comes out of each mission earmark; the rest from the free part
   */
  record PoolBookOut(
      @NotNull UUID actor,
      @NotNull UUID entryId,
      @NotNull UUID poolUnitId,
      long version,
      double amount,
      @NotNull String unit,
      @NotNull @Unmodifiable List<PoolEarmark> fromJobOrders,
      @NotNull @Unmodifiable List<PoolEarmark> fromMissions) {}

  /**
   * An amount of an entry for one job order or mission.
   *
   * @param targetId the job order or mission
   * @param amount the amount, rounded to the entry's unit
   */
  record PoolEarmark(@NotNull UUID targetId, double amount) {}

  /**
   * What the Lager refuses a pool write for.
   *
   * <p>The constant names are the exchange's per-op reason codes.
   */
  enum PoolRefusal {
    /** The entry is unknown, personal, or not in the named pool. */
    NOT_PERMITTED,

    /** The holder is not a direct member of the pool's unit. */
    HOLDER_UNKNOWN,

    /** The entry's version is not the one the writer read. */
    VERSION_CONFLICT,

    /** The amount's unit is not the entry's. */
    UNIT_MISMATCH,

    /** The entry holds less than the book-out takes. */
    INSUFFICIENT_STOCK,

    /** The book-out takes stock earmarked to a job order, mission or donation it does not name. */
    STOCK_EARMARKED,

    /** The earmarks of a dimension add up to more than the entry holds. */
    OVER_ALLOCATION,

    /**
     * An earmark names a target that does not exist, does not need the material or item, appears
     * twice, or is not on the entry, or takes more from a target than it holds.
     */
    EARMARK_TARGET_INVALID,

    /** Stolen stock while the stolen marking is switched off. */
    STOLEN_MARKING_DISABLED
  }

  /**
   * The outcome of a pool write.
   *
   * @param refusal why the write was refused, or {@code null} when it was applied or would be
   * @param changes every entry the write changed; empty for a refusal or a dry run
   * @param offers the Materialbörse offers the write lowered and removed
   */
  record PoolOutcome(
      @Nullable PoolRefusal refusal,
      @NotNull @Unmodifiable List<PoolEntryChange> changes,
      @NotNull StockChangeEffects offers) {

    /**
     * A refused write.
     *
     * @param refusal the reason
     * @return the outcome
     */
    public static @NotNull PoolOutcome refused(@NotNull PoolRefusal refusal) {
      return new PoolOutcome(refusal, List.of(), StockChangeEffects.NONE);
    }

    /**
     * A write that was checked only.
     *
     * @return the outcome
     */
    public static @NotNull PoolOutcome checked() {
      return new PoolOutcome(null, List.of(), StockChangeEffects.NONE);
    }
  }

  /**
   * One entry a pool write changed.
   *
   * @param before its state before, or {@code null} when the write created it
   * @param after its state after, or {@code null} when the write emptied or merged it away
   */
  record PoolEntryChange(@Nullable PoolEntryState before, @Nullable PoolEntryState after) {}

  /**
   * One shared entry as the journal records it, without its note.
   *
   * @param id the entry
   * @param holder the member who holds it
   * @param poolUnitId the org unit whose pool holds it
   * @param lotKey its stock lot key ({@link StockLot#key()})
   * @param amount its amount
   * @param version its version
   * @param jobOrders its job-order earmarks by order
   * @param missions its mission earmarks by mission
   */
  record PoolEntryState(
      @NotNull UUID id,
      @NotNull UUID holder,
      @NotNull UUID poolUnitId,
      @NotNull String lotKey,
      double amount,
      long version,
      @NotNull Map<UUID, Double> jobOrders,
      @NotNull Map<UUID, Double> missions) {}
}
