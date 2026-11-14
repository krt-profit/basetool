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

package de.greluc.krt.profit.basetool.backend.materialexchange.api;

import java.util.Collection;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Ends the Materialbörse offers that stand on stock a game patch wiped (REQ-PATCH-013): each active
 * offer ends as when its stock is gone, with the usual notice to the members who registered
 * interest.
 */
public interface MaterialExchangePatchWipe {

  /**
   * Counts the active offers standing on the given Lager rows, without writing.
   *
   * @param inventoryItemIds the Lager rows the patch wipes
   * @return the number of active offers
   */
  long countEndable(@NotNull Collection<UUID> inventoryItemIds);

  /**
   * Ends every active offer on the given Lager rows inside the caller's transaction, which holds
   * the rows' locks: tells the interested members the stock is gone, removes the offers and records
   * one summary event in the Materialbörse's trail; the open board refreshes after the commit.
   *
   * @param inventoryItemIds the Lager rows the patch wipes, locked by the caller
   * @param patchChangeId the patch change that wiped them
   * @param patchLabel its label, the summary event's subject label
   * @return the number of offers ended
   * @throws org.springframework.transaction.IllegalTransactionStateException when no transaction is
   *     active
   */
  int end(
      @NotNull Collection<UUID> inventoryItemIds,
      @NotNull UUID patchChangeId,
      @NotNull String patchLabel);
}
