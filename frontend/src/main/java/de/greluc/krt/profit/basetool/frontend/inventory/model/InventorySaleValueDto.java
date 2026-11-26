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

package de.greluc.krt.profit.basetool.frontend.inventory.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.math.BigDecimal;

/**
 * Frontend mirror of the estimated sale value of one "Mein Lager" material group (REQ-INV-072).
 *
 * @param basis the price basis the member chose in the profile
 * @param pricePerScu the price of that basis in aUEC per SCU
 * @param terminalName the terminal paying {@code pricePerScu}, or {@code null} for the average
 * @param terminalCount the number of terminals that buy the material
 * @param value the amount without stolen stock times {@code pricePerScu}, in whole aUEC
 * @param unvaluedStolenAmount the SCU marked „gestohlen" that {@code value} leaves out
 */
@DtoMirror
public record InventorySaleValueDto(
    SalePriceBasis basis,
    BigDecimal pricePerScu,
    String terminalName,
    int terminalCount,
    BigDecimal value,
    double unvaluedStolenAmount) {

  /**
   * Tells whether the value leaves stolen stock out.
   *
   * @return {@code true} when the group holds stock marked „gestohlen"
   */
  public boolean excludesStolen() {
    return unvaluedStolenAmount > 0;
  }
}
