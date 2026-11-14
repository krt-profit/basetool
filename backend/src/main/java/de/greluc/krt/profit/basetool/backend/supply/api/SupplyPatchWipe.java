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

package de.greluc.krt.profit.basetool.backend.supply.api;

import java.util.Collection;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Withdraws the donation pledges earmarked on stock a game patch wiped (REQ-PATCH-013): each pledge
 * is withdrawn with the patch change as its reason and the usual notice.
 */
public interface SupplyPatchWipe {

  /**
   * Counts the pledges earmarked on the given Lager rows, without writing.
   *
   * @param inventoryItemIds the Lager rows the patch wipes
   * @return the number of pledged donations
   */
  long countWithdrawable(@NotNull Collection<UUID> inventoryItemIds);

  /**
   * Withdraws every pledge earmarked on the given Lager rows inside the caller's transaction: each
   * keeps the patch change as its reason, its pledge notices go, and one summary event goes into
   * the supply trail; the open supply pages refresh after the commit. The earmarks go with the
   * rows.
   *
   * @param inventoryItemIds the Lager rows the patch wipes, locked by the caller
   * @param patchChangeId the patch change that wiped them
   * @param patchLabel its label, the summary event's subject label
   * @return the number of pledges withdrawn
   * @throws org.springframework.transaction.IllegalTransactionStateException when no transaction is
   *     active
   */
  int withdraw(
      @NotNull Collection<UUID> inventoryItemIds,
      @NotNull UUID patchChangeId,
      @NotNull String patchLabel);
}
