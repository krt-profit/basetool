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

import de.greluc.krt.profit.basetool.backend.annotation.ObserverSpi;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Reacts to Lager rows being lowered or deleted, inside the transaction that changes them (plan
 * §5.3, REQ-MARKET-013); implemented by the Materialbörse, whose offers stand on the rows.
 *
 * <p>Every path that lowers a row calls {@link #lower}; every path that deletes rows calls a {@code
 * before…} method before the delete, whose {@code ON DELETE CASCADE} then removes the offers. Every
 * implementation joins the caller's transaction ({@code MANDATORY}).
 */
@ObserverSpi
public interface StockChangeObserver {

  /**
   * A Lager row's stock was lowered.
   *
   * @param inventoryItemId the lowered row
   * @param stock the row's new stock: SCU for a material offer, floored to whole pieces for an item
   *     offer
   * @param reason the stock change
   * @return the number of offers lowered to the new stock
   */
  int lower(@NotNull UUID inventoryItemId, double stock, @NotNull StockChangeReason reason);

  /**
   * Lager rows are about to be deleted.
   *
   * @param inventoryItemIds the rows to be deleted
   * @param reason the stock change
   * @return the number of offers the delete will remove
   */
  int beforeDelete(@NotNull Collection<UUID> inventoryItemIds, @NotNull StockChangeReason reason);

  /**
   * The shared (non-personal) Lager rows within a scope are about to be wiped.
   *
   * @param adminAllScope whether the wipe covers every org unit
   * @param activeOrgUnitId the pinned org unit, or {@code null}
   * @param memberOrgUnitIds the caller's org units when neither of the above applies
   * @return the number of offers the wipe will remove
   */
  int beforeWipe(
      boolean adminAllScope, @Nullable UUID activeOrgUnitId, @NotNull Set<UUID> memberOrgUnitIds);

  /**
   * The Lager rows of an account are about to be purged with the account.
   *
   * @param userId the account being erased
   * @return the number of offers the purge will remove
   */
  int beforeUserPurge(@NotNull UUID userId);
}
