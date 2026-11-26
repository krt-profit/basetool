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

package de.greluc.krt.profit.basetool.frontend.inventory.web;

import de.greluc.krt.profit.basetool.frontend.inventory.model.GroupedInventoryDto;
import de.greluc.krt.profit.basetool.frontend.inventory.model.InventorySaleValueDto;
import de.greluc.krt.profit.basetool.frontend.inventory.model.InventorySelectionSaleValueDto;
import de.greluc.krt.profit.basetool.frontend.inventory.model.SalePriceBasis;
import java.math.BigDecimal;
import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The sum row of the "Mein Lager" material tree: the estimated sale value of exactly the groups
 * shown, so it follows the active filter, or of the entries marked for an action (REQ-INV-072).
 *
 * @param total the summed value, in whole aUEC
 * @param basis the price basis the member chose in the profile, which every value shares
 * @param unpricedMaterials the counted materials without a sale price: a {@code PIECE} material or
 *     one no terminal buys
 * @param stolenExcluded whether a counted material leaves stock marked „gestohlen" out of its value
 * @param scope what the row totals, which its label names
 */
public record LagerSaleValueSummary(
    @NotNull BigDecimal total,
    @NotNull SalePriceBasis basis,
    int unpricedMaterials,
    boolean stolenExcluded,
    @NotNull Scope scope) {

  /** What a sum row totals; its label says which. */
  @Getter
  @RequiredArgsConstructor
  public enum Scope {

    /** Every group of the unfiltered tree. */
    SHOWN("inventory.saleValue.total"),

    /** The groups a filter leaves in the tree. */
    FILTERED("inventory.saleValue.totalFiltered"),

    /** The entries marked for an action, while any are. */
    SELECTION("inventory.saleValue.totalSelection");

    /** The message key of the row's label. */
    @NotNull private final String labelKey;
  }

  /**
   * Sums the sale values of the shown groups.
   *
   * @param groups the material groups the tree shows
   * @param filtered whether a filter narrows them
   * @return the sum row, or {@code null} when no shown group carries a positive value
   */
  @Nullable
  public static LagerSaleValueSummary of(
      @NotNull List<GroupedInventoryDto> groups, boolean filtered) {
    BigDecimal total = BigDecimal.ZERO;
    SalePriceBasis basis = null;
    int unpriced = 0;
    boolean valued = false;
    boolean stolenExcluded = false;
    for (GroupedInventoryDto group : groups) {
      if (group.material() == null) {
        continue;
      }
      InventorySaleValueDto saleValue = group.saleValue();
      if (saleValue == null) {
        unpriced++;
        continue;
      }
      basis = saleValue.basis();
      total = total.add(saleValue.value());
      valued |= saleValue.value().signum() > 0;
      stolenExcluded |= saleValue.excludesStolen();
    }
    return valued && basis != null
        ? new LagerSaleValueSummary(
            total, basis, unpriced, stolenExcluded, filtered ? Scope.FILTERED : Scope.SHOWN)
        : null;
  }

  /**
   * The sum row of the marked entries, shown even at zero, so the row never keeps the view's total
   * while a selection is active.
   *
   * @param value the backend's value of the marked entries
   * @return the sum row of the selection
   */
  @NotNull
  public static LagerSaleValueSummary ofSelection(@NotNull InventorySelectionSaleValueDto value) {
    return new LagerSaleValueSummary(
        value.total(),
        value.basis(),
        value.unpricedMaterials(),
        value.stolenExcluded(),
        Scope.SELECTION);
  }
}
