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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The rebooking of a member's own stock of one lot between personal and one org unit's pool, the
 * Lager's Umbuchung of REQ-INV-007 addressed by lot (REQ-INV-060). The command joins the caller's
 * transaction ({@code MANDATORY}); the caller has taken the member's lot lock with {@link
 * StockCommands#lockLots} first.
 *
 * <p>A refusal is answered instead of thrown, so the caller can report it per op and go on.
 */
public interface RebookCommands {

  /**
   * Rebooks part or all of the member's stock of a lot between personal and the named pool. The
   * member stays the holder; the stolen marker, the quality and the location carry over. Each moved
   * row is audited as {@code INVENTORY_ITEM_DEPERSONALIZED} or {@code INVENTORY_ITEM_PERSONALIZED}
   * under the current request's authentication.
   *
   * @param rebook the rebooking
   * @param dryRun whether to decide it only and write nothing
   * @return the refusal, or every row the rebooking created, changed or removed
   * @throws de.greluc.krt.profit.basetool.backend.kernel.BadRequestException when the amount is not
   *     a positive number
   * @throws java.util.NoSuchElementException when the named org unit does not exist
   */
  @NotNull
  RebookOutcome rebook(@NotNull Rebook rebook, boolean dryRun);

  /**
   * One rebooking of a member's lot.
   *
   * @param member the member whose own stock moves
   * @param lot the lot
   * @param poolUnitId the org unit whose pool receives or gives the stock
   * @param direction whether the stock moves into the pool or back to personal
   * @param amount the amount to move; positive
   */
  record Rebook(
      @NotNull UUID member,
      @NotNull StockLot lot,
      @NotNull UUID poolUnitId,
      @NotNull RebookDirection direction,
      double amount) {}

  /** The way a rebooking moves stock. */
  enum RebookDirection {
    /** From the member's personal stock into the pool. */
    TO_POOL,

    /** From the member's own shared stock in the pool back to personal. */
    TO_PERSONAL
  }

  /**
   * What the Lager refuses a rebooking for.
   *
   * <p>The constant names follow the exchange's per-op reason codes where one fits.
   */
  enum RebookRefusal {
    /** A move into the pool of an org unit the member is not a direct member of. */
    NOT_A_MEMBER,

    /** A fractional amount of a lot counted in whole units. */
    UNIT_MISMATCH,

    /** The member holds less stock at the source than the rebooking moves. */
    INSUFFICIENT_STOCK,

    /** The rebooking would move stock reserved for a job order, mission or donation. */
    STOCK_EARMARKED,

    /** The rebooking would leave a row below what it offers on the Materialbörse. */
    STOCK_OFFERED
  }

  /**
   * The outcome of a rebooking.
   *
   * @param refusal why it was refused, or {@code null} when it was applied or would be
   * @param changes every row it changed; empty for a refusal or a dry run
   */
  record RebookOutcome(
      @Nullable RebookRefusal refusal, @NotNull @Unmodifiable List<RebookedRow> changes) {

    /**
     * A refused rebooking.
     *
     * @param refusal the reason
     * @return the outcome
     */
    public static @NotNull RebookOutcome refused(@NotNull RebookRefusal refusal) {
      return new RebookOutcome(refusal, List.of());
    }

    /**
     * A rebooking that was decided only.
     *
     * @return the outcome
     */
    public static @NotNull RebookOutcome checked() {
      return new RebookOutcome(null, List.of());
    }

    /**
     * An applied rebooking.
     *
     * @param changes every row it changed
     * @return the outcome
     */
    public static @NotNull RebookOutcome applied(@NotNull List<RebookedRow> changes) {
      return new RebookOutcome(null, List.copyOf(changes));
    }
  }

  /**
   * One row a rebooking changed.
   *
   * @param before its state before, or {@code null} when the rebooking created it
   * @param after its state after, or {@code null} when the rebooking emptied or merged it away
   */
  record RebookedRow(@Nullable RebookRowState before, @Nullable RebookRowState after) {}

  /**
   * One of the member's rows of the lot as a caller journals it.
   *
   * @param id the row
   * @param personal whether it is personal
   * @param orgUnitId the org unit it is stamped to, or {@code null}
   * @param amount the stock it holds
   * @param version the optimistic-lock version it carries after the write
   * @param jobOrders its job-order earmarks by order
   * @param missions its mission earmarks by mission
   */
  record RebookRowState(
      @NotNull UUID id,
      boolean personal,
      @Nullable UUID orgUnitId,
      double amount,
      long version,
      @NotNull Map<UUID, Double> jobOrders,
      @NotNull Map<UUID, Double> missions) {}
}
