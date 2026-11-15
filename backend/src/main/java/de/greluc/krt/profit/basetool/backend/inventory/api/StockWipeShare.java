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

import org.jetbrains.annotations.NotNull;

/**
 * One share of the stock a game patch wiped, as the Lager's summary event counts it
 * (REQ-PATCH-017): a wipe group, or the exceptions that wipe their entries. Implemented by the
 * patch-change module.
 */
public interface StockWipeShare {

  /**
   * Returns the key the summary event counts the share's rows under.
   *
   * @return the key, e.g. {@code CONSUMABLES}
   */
  @NotNull
  String countKey();
}
