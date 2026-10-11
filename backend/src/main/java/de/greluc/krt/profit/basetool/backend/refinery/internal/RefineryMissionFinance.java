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

package de.greluc.krt.profit.basetool.backend.refinery.internal;

import de.greluc.krt.profit.basetool.backend.mission.api.MissionFinanceContribution;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionFinanceContributor;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adds the profit and the expenses of the refinery orders linked to a mission to the mission's
 * finance totals. An order's profit is {@code oreSales − expenses − otherExpenses} with {@code
 * null} as zero; a zero profit adds nothing, and an order counts as an expense line when its {@code
 * expenses} are positive.
 */
@Component
@RequiredArgsConstructor
public class RefineryMissionFinance implements MissionFinanceContributor {

  private final RefineryOrderRepository refineryOrderRepository;

  @Override
  @Transactional(readOnly = true)
  public @NotNull MissionFinanceContribution contributionTo(@NotNull UUID missionId) {
    BigDecimal profit = BigDecimal.ZERO;
    BigDecimal expenseSum = BigDecimal.ZERO;
    long expenseCount = 0L;
    for (RefineryOrder order : refineryOrderRepository.findByMissionId(missionId)) {
      double sales = order.getOreSales() != null ? order.getOreSales() : 0d;
      double costs = order.getExpenses() != null ? order.getExpenses() : 0d;
      double otherCosts = order.getOtherExpenses() != null ? order.getOtherExpenses() : 0d;
      double orderProfit = sales - costs - otherCosts;
      if (orderProfit != 0d) {
        profit = profit.add(BigDecimal.valueOf(orderProfit));
      }
      if (costs > 0d) {
        expenseSum = expenseSum.add(BigDecimal.valueOf(costs));
        expenseCount++;
      }
    }
    return new MissionFinanceContribution(profit, expenseSum, expenseCount);
  }
}
