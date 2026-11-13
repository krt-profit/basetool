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

package de.greluc.krt.profit.basetool.backend.joborder.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Follows a currency wipe of the game in the aUEC orders (REQ-PATCH-043…045), for every order
 * whatever its status and responsibility: the Bank attempts waiting since before the live time are
 * cancelled, each holding that existed at the live time and is still held becomes a real shortage,
 * and the unit commitments opened before the live time end. Target, net handover and status stay.
 */
public interface JobOrderCurrencyWipe {

  /**
   * Counts what {@link #apply} would do now, without writing.
   *
   * @param liveAt the live time of the patch
   * @return the effect it would have
   */
  @NotNull
  Effect preview(@NotNull Instant liveAt);

  /**
   * Runs the wipe inside the caller's transaction: each shortage is its own money operation that
   * names the patch change, each cancelled attempt's Bank request is cancelled with it, and the
   * order trail records one summary event whose subject is the patch change.
   *
   * @param liveAt the live time of the patch
   * @param patchChangeId the patch change, kept as the reason of every change
   * @param patchLabel its label, the summary event's subject label
   * @return the effect it had
   * @throws org.springframework.transaction.IllegalTransactionStateException when no transaction is
   *     active
   */
  @NotNull
  Effect apply(@NotNull Instant liveAt, @NotNull UUID patchChangeId, @NotNull String patchLabel);

  /**
   * What the currency wipe does in the aUEC orders.
   *
   * @param shortages the holdings booked as a real shortage
   * @param shortageTotal the sum of those shortages, in whole aUEC
   * @param attemptsCancelled the waiting Bank attempts cancelled
   * @param commitmentsEnded the unit commitments ended
   */
  record Effect(
      long shortages,
      @NotNull BigDecimal shortageTotal,
      long attemptsCancelled,
      long commitmentsEnded) {}
}
