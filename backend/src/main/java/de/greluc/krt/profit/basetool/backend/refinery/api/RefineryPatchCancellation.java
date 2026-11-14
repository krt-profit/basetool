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

package de.greluc.krt.profit.basetool.backend.refinery.api;

import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Ends the refinery orders a game patch ended (REQ-PATCH-006): every {@code OPEN} or {@code
 * IN_PROGRESS} order of every member that started before the patch's live time, an order without a
 * start counting from its creation and one with neither as started before.
 */
public interface RefineryPatchCancellation {

  /**
   * Counts the orders {@link #cancel} would end now, without writing.
   *
   * @param liveAt the patch's live time
   * @return the number of running orders started before it
   */
  long countCancellable(@NotNull Instant liveAt);

  /**
   * Cancels every running order started before the live time inside the caller's transaction: each
   * keeps the patch change as its cancel reason, advances its game-data counter and loses its ready
   * notice; its finances stay. Records one summary event in the refinery's audit trail and
   * refreshes the open refinery pages after the commit.
   *
   * @param liveAt the patch's live time
   * @param patchChangeId the patch change that ended the orders
   * @param patchLabel its label, the summary event's subject label
   * @return the number of orders cancelled
   * @throws org.springframework.transaction.IllegalTransactionStateException when no transaction is
   *     active
   */
  int cancel(@NotNull Instant liveAt, @NotNull UUID patchChangeId, @NotNull String patchLabel);
}
