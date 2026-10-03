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

package de.greluc.krt.profit.basetool.architecturefixture.domainmap.low;

import de.greluc.krt.profit.basetool.architecturefixture.domainmap.high.HighService;

/** Fixture of the low-ranked module that reaches up into the high-ranked one. */
public final class LowService {

  private final HighService high = new HighService();

  /**
   * Delegates to the high-ranked module.
   *
   * @return the high-ranked module's answer
   */
  public String answer() {
    return high.answer();
  }
}
