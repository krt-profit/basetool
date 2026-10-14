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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import de.greluc.krt.profit.basetool.backend.inventory.api.OverAllocationException;
import org.jetbrains.annotations.NotNull;

/**
 * The guards that keep a row's pledged donation covered (REQ-INV-058): a write that would leave a
 * donation slice without stock is refused with the over-allocation conflict.
 */
public final class InventoryDonationGuards {

  /** Tolerance for the comparison, matching the other allocation dimensions. */
  private static final double EPSILON = 1e-6;

  private InventoryDonationGuards() {}

  /**
   * Refuses to delete or move away a whole entry that still carries a pledged donation; its owner
   * withdraws the pledge first.
   *
   * @param item the entry about to be depleted or moved in full; never {@code null}
   * @throws OverAllocationException when a donation slice is still on the entry
   */
  public static void requireNoDonation(@NotNull InventoryItem item) {
    if (InventoryAllocations.sumDonation(item) > EPSILON) {
      throw new OverAllocationException();
    }
  }

  /**
   * Refuses a lowered amount that no longer covers the entry's donation slices.
   *
   * @param item the entry with its post-decrement amount and donation slices loaded; never {@code
   *     null}
   * @throws OverAllocationException when the donation slices exceed the amount
   */
  public static void requireDonationFits(@NotNull InventoryItem item) {
    double amount = item.getAmount() != null ? item.getAmount() : 0.0;
    if (InventoryAllocations.sumDonation(item) > amount + EPSILON) {
      throw new OverAllocationException();
    }
  }
}
