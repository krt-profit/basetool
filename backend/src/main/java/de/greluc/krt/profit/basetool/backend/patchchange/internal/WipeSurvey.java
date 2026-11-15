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

import de.greluc.krt.profit.basetool.backend.hangar.api.ShipPatchWipe;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipWipeCount;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockPatchWipe;
import de.greluc.krt.profit.basetool.backend.kernel.TransactionMemo;
import de.greluc.krt.profit.basetool.backend.materialexchange.api.MaterialExchangePatchWipe;
import de.greluc.krt.profit.basetool.backend.supply.api.SupplyPatchWipe;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Reads what a patch change's wipe would touch now, without writing (REQ-PATCH-016): the selected
 * stock rows with their dependents and the in-game ships. One read serves every caller of the same
 * transaction and plan, so the preview's counts and its breakdown agree.
 */
@Component
@RequiredArgsConstructor
class WipeSurvey {

  private final StockPatchWipe stock;
  private final MaterialExchangePatchWipe market;
  private final SupplyPatchWipe supply;
  private final ShipPatchWipe ships;

  /**
   * Surveys a plan, reading it once per transaction.
   *
   * @param plan the live time and options under preview
   * @return what the wipe would touch
   */
  @NotNull
  Result survey(@NotNull PatchChangePlan plan) {
    return TransactionMemo.bound(this, Memo.class, Memo::new)
        .results
        .computeIfAbsent(plan, this::read);
  }

  /**
   * Reads what the wipe of one plan would touch.
   *
   * @param plan the live time and options
   * @return what the wipe would touch
   */
  private @NotNull Result read(@NotNull PatchChangePlan plan) {
    WipeSelection selection =
        WipeSelection.of(stock.rowsCreatedBefore(plan.liveAt()), plan.options());
    Set<UUID> wiped = selection.wipedRowIds();
    boolean any = !wiped.isEmpty();
    return new Result(
        selection,
        any ? stock.countEarmarkedOrders(wiped) : 0,
        any ? market.countEndable(wiped) : 0,
        any ? supply.countWithdrawable(wiped) : 0,
        ships.countWipeable(plan.liveAt()));
  }

  /**
   * What a wipe would touch.
   *
   * @param selection the stock rows created before the live time, classified by the options
   * @param jobOrders the job orders earmarked on the selected rows
   * @param offers the active Materialbörse offers on the selected rows
   * @param donations the pledges earmarked on the selected rows
   * @param ships the in-game ships created before the live time, selected or not
   */
  record Result(
      @NotNull WipeSelection selection,
      long jobOrders,
      long offers,
      long donations,
      @NotNull ShipWipeCount ships) {}

  /** The surveys of one transaction, by plan. */
  private static final class Memo {

    /** The surveys read so far. */
    private final Map<PatchChangePlan, Result> results = new HashMap<>();
  }
}
