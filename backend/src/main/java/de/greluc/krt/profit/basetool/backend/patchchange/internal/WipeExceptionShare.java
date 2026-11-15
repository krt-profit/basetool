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

package de.greluc.krt.profit.basetool.backend.patchchange.internal;

import de.greluc.krt.profit.basetool.backend.inventory.api.StockWipeShare;
import org.jetbrains.annotations.NotNull;

/**
 * The share of the wiped stock that the wiping exceptions select, counted apart from the wipe
 * groups in the Lager's summary event (REQ-PATCH-011, REQ-PATCH-017).
 */
enum WipeExceptionShare implements StockWipeShare {
  /** Every row a wiping exception selects, whatever its group. */
  WIPE_EXCEPTIONS;

  /**
   * Returns the share's name, the key the summary event counts its rows under.
   *
   * @return the constant's name
   */
  @Override
  public @NotNull String countKey() {
    return name();
  }
}
