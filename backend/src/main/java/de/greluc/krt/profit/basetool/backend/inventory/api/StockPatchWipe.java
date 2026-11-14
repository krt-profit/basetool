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

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Removes the stock a game patch wiped (REQ-PATCH-012): every member's own, pool and personal rows
 * of every org unit, stolen ones included, created before the patch's live time. A row without a
 * creation time counts as created before it.
 *
 * <p>The wipe is a game fact, not an order write: it takes every lock an ordinary stock write
 * takes, the shared hierarchy lock first, but an earmarked job order with invalid responsibility
 * does not refuse it (REQ-PATCH-014, ADR-0382).
 */
public interface StockPatchWipe {

  /**
   * Lists every row created before the cutoff, without writing.
   *
   * @param cutoff the patch's live time
   * @return the rows with their catalogue facts, ascending by id
   */
  @NotNull
  @Unmodifiable
  List<StockWipeRow> rowsCreatedBefore(@NotNull Instant cutoff);

  /**
   * Counts the job orders earmarked on the given rows, without writing.
   *
   * @param rowIds the rows
   * @return the number of distinct job orders
   */
  long countEarmarkedOrders(@NotNull Collection<UUID> rowIds);

  /**
   * Locks the given rows for a wipe inside the caller's transaction, after the hierarchy, material
   * and lot locks every stock write takes; the patch wipe is the transaction's one stock plan.
   *
   * @param rowIds the rows the caller selected from {@link #rowsCreatedBefore}
   * @return the rows that still exist, locked, with their current catalogue facts
   * @throws org.springframework.transaction.IllegalTransactionStateException when no transaction is
   *     active
   */
  @NotNull
  @Unmodifiable
  List<StockWipeRow> lockForWipe(@NotNull Collection<UUID> rowIds);

  /**
   * Deletes rows {@link #lockForWipe} locked in this transaction; their earmarks go with them.
   * Records one summary event in the Lager's audit trail and refreshes the open Lager pages after
   * the commit.
   *
   * @param rowsByShare the rows to delete, by the share the summary event counts them under, in the
   *     order the event lists the shares
   * @param patchChangeId the patch change that wiped them
   * @param patchLabel its label, the summary event's subject label
   * @return what the wipe removed
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when a row was not
   *     locked through {@link #lockForWipe} in this transaction
   */
  @NotNull
  StockWipeResult wipe(
      @NotNull Map<? extends StockWipeShare, ? extends Collection<UUID>> rowsByShare,
      @NotNull UUID patchChangeId,
      @NotNull String patchLabel);
}
