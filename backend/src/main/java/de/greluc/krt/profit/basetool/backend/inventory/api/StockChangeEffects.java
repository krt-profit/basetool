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

/**
 * What a stock change did to the Materialbörse offers on the changed rows.
 *
 * @param reduced the number of offers lowered to the new stock
 * @param removed the number of offers removed with their rows
 */
public record StockChangeEffects(int reduced, int removed) {

  /** A stock change that touched no offer. */
  public static final StockChangeEffects NONE = new StockChangeEffects(0, 0);
}
