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

import de.greluc.krt.profit.basetool.backend.inventory.api.StockPatchWipe;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockWipeResult;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderPatchWipe;
import de.greluc.krt.profit.basetool.backend.materialexchange.api.MaterialExchangePatchWipe;
import de.greluc.krt.profit.basetool.backend.supply.api.SupplyPatchWipe;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The stock wipe of a patch change (REQ-PATCH-012, REQ-PATCH-013): when a stock group or a wiping
 * exception is selected, every member's rows created before the live time that the options select
 * go, after their offers ended and their pledges were withdrawn; their earmarks go with them.
 *
 * <p>The rows are selected, locked and selected again on their locked facts, so a row a concurrent
 * write changed is judged as it is now; the offers, pledges and the delete then work on exactly
 * those rows.
 */
@Component
@RequiredArgsConstructor
class StockWipeEffect implements PatchEffect {

  private final WipeSurvey survey;
  private final StockPatchWipe stock;
  private final MaterialExchangePatchWipe market;
  private final SupplyPatchWipe supply;
  private final JobOrderPatchWipe jobOrders;

  @Override
  public @NotNull PatchCounts preview(@NotNull PatchChangePlan plan) {
    if (!plan.options().wipesStock()) {
      return PatchCounts.none();
    }
    WipeSurvey.Result result = survey.survey(plan);
    return counts(
        result.selection().wipedRowIds().size(),
        result.selection().wipedMembers(),
        result.jobOrders(),
        result.offers(),
        result.donations());
  }

  @Override
  public @NotNull PatchCounts apply(@NotNull PatchChangeRun run) {
    PatchChangeOptions options = run.plan().options();
    if (!options.wipesStock()) {
      return PatchCounts.none();
    }
    WipeSelection planned = WipeSelection.of(stock.rowsCreatedBefore(run.plan().liveAt()), options);
    WipeSelection selection = WipeSelection.of(stock.lockForWipe(planned.wipedRowIds()), options);
    Set<UUID> rows = selection.wipedRowIds();
    int offers = market.end(rows, run.patchChangeId(), run.label());
    int donations = supply.withdraw(rows, run.patchChangeId(), run.label());
    StockWipeResult result =
        stock.wipe(selection.wipedRowsByGroup(), run.patchChangeId(), run.label());
    jobOrders.recordStockLoss(result.jobOrders(), run.patchChangeId(), run.label());
    return counts(result.rows(), result.holders(), result.jobOrders().size(), offers, donations);
  }

  /**
   * Builds the stock wipe's counts.
   *
   * @param rows the rows wiped
   * @param members the members who held them
   * @param orders the job orders earmarked on them
   * @param offers the offers ended
   * @param donations the pledges withdrawn
   * @return the counts
   */
  private static @NotNull PatchCounts counts(
      long rows, long members, long orders, long offers, long donations) {
    return PatchCounts.of(PatchCount.STOCK_ROWS_WIPED, rows)
        .plus(PatchCounts.of(PatchCount.STOCK_MEMBERS_AFFECTED, members))
        .plus(PatchCounts.of(PatchCount.JOB_ORDERS_AFFECTED, orders))
        .plus(PatchCounts.of(PatchCount.MARKET_OFFERS_ENDED, offers))
        .plus(PatchCounts.of(PatchCount.DONATIONS_WITHDRAWN, donations));
  }
}
