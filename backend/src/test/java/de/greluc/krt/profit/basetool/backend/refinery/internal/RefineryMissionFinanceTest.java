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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.mission.api.MissionFinanceContribution;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link RefineryMissionFinance}: the profit and the expense lines the refinery adds
 * to a mission's finance totals.
 */
@ExtendWith(MockitoExtension.class)
class RefineryMissionFinanceTest {

  private static final UUID MISSION_ID = UUID.randomUUID();

  @Mock private RefineryOrderRepository refineryOrderRepository;

  @InjectMocks private RefineryMissionFinance finance;

  @Test
  void aMissionWithoutOrders_contributesNothing() {
    stub();

    MissionFinanceContribution contribution = finance.contributionTo(MISSION_ID);

    assertEquals(BigDecimal.ZERO, contribution.profit());
    assertEquals(BigDecimal.ZERO, contribution.expenseSum());
    assertEquals(0L, contribution.expenseCount());
  }

  @Test
  void anOrdersProfit_isSalesMinusExpensesMinusOtherExpenses() {
    stub(order(1000.0, 200.0, 50.0));

    assertEquals(new BigDecimal("750.0"), finance.contributionTo(MISSION_ID).profit());
  }

  @Test
  void anOrdersLoss_isNegative() {
    stub(order(100.0, 300.0, 50.0));

    assertEquals(new BigDecimal("-250.0"), finance.contributionTo(MISSION_ID).profit());
  }

  @Test
  void anOrderWithZeroProfit_addsNothingToTheProfit() {
    stub(order(500.0, 300.0, 200.0));

    assertEquals(BigDecimal.ZERO, finance.contributionTo(MISSION_ID).profit());
  }

  @Test
  void nullFigures_countAsZero() {
    stub(order(null, 100.0, null), order(500.0, null, 50.0), order(500.0, 100.0, null));

    assertEquals(new BigDecimal("750.0"), finance.contributionTo(MISSION_ID).profit());
  }

  @Test
  void anOrderWithoutAnyFigure_contributesNothing() {
    stub(order(null, null, null));

    MissionFinanceContribution contribution = finance.contributionTo(MISSION_ID);

    assertEquals(BigDecimal.ZERO, contribution.profit());
    assertEquals(0L, contribution.expenseCount());
  }

  @Test
  void severalOrders_accumulate() {
    stub(order(1000.0, 100.0, 0.0), order(0.0, 50.0, 0.0), order(200.0, 100.0, 50.0));

    MissionFinanceContribution contribution = finance.contributionTo(MISSION_ID);

    assertEquals(new BigDecimal("900.0"), contribution.profit());
    assertEquals(new BigDecimal("250.0"), contribution.expenseSum());
    assertEquals(3L, contribution.expenseCount());
  }

  @Test
  void onlyPositiveExpenses_countAsExpenseLines() {
    stub(order(500.0, 300.0, 50.0), order(500.0, 0.0, 70.0), order(500.0, null, null));

    MissionFinanceContribution contribution = finance.contributionTo(MISSION_ID);

    assertEquals(new BigDecimal("300.0"), contribution.expenseSum());
    assertEquals(1L, contribution.expenseCount());
  }

  private void stub(RefineryOrder... orders) {
    when(refineryOrderRepository.findByMissionId(MISSION_ID)).thenReturn(List.of(orders));
  }

  private static RefineryOrder order(Double oreSales, Double expenses, Double otherExpenses) {
    RefineryOrder order = new RefineryOrder();
    order.setOreSales(oreSales);
    order.setExpenses(expenses);
    order.setOtherExpenses(otherExpenses);
    return order;
  }
}
