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

import java.util.Collection;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Records in the job-order trail that a game patch wiped earmarked stock (REQ-PATCH-013,
 * REQ-PATCH-017). The loss is genuine: credited contribution stays, uncovered demand rises and no
 * ended promise revives (ADR-0360); the order pages refresh through the stock write itself.
 */
public interface JobOrderPatchWipe {

  /**
   * Records one summary event for the orders whose earmarked stock a patch wiped, inside the
   * caller's transaction.
   *
   * @param orderIds the job orders whose earmarks went with the wiped rows
   * @param patchChangeId the patch change that wiped the stock
   * @param patchLabel its label, the summary event's subject label
   * @throws org.springframework.transaction.IllegalTransactionStateException when no transaction is
   *     active
   */
  void recordStockLoss(
      @NotNull Collection<UUID> orderIds, @NotNull UUID patchChangeId, @NotNull String patchLabel);
}
