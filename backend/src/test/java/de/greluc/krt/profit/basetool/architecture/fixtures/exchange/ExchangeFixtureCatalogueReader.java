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

package de.greluc.krt.profit.basetool.architecture.fixtures.exchange;

import de.greluc.krt.profit.basetool.architecture.fixtures.exchangeleak.FixtureCatalogueEntry;

/** Planted violation: exchange code that reads a game text through its getter. */
public class ExchangeFixtureCatalogueReader {

  /**
   * Reads the entry's name, which is allowed, and its description, which is not.
   *
   * @param entry the entry
   * @return both texts joined
   */
  public String describe(FixtureCatalogueEntry entry) {
    return entry.getName() + entry.getDescription();
  }
}
