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

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** Scalar source epochs for the canonical earmarked game-item prefix. */
public interface InventoryItemConsumptionSource {
  /**
   * Returns the physical row identity.
   *
   * @return the existing stock row
   */
  UUID getRowId();

  /**
   * Returns the game item held by the source.
   *
   * @return the source's catalogue item
   */
  UUID getGameItemId();

  /**
   * Returns the current physical amount.
   *
   * @return the row's total quantity
   */
  Double getAmount();

  /**
   * Returns the selected order's earmarked amount.
   *
   * @return the available order allocation
   */
  Double getSliceAmount();

  /**
   * Returns the physical row version.
   *
   * @return the current persistence epoch, or null before its initial flush
   */
  @Nullable
  Long getVersion();

  /**
   * Returns the creation timestamp used by the owning oldest-first order.
   *
   * @return the source's age, or null when not populated
   */
  @Nullable
  java.time.Instant getCreatedAt();
}
