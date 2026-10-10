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

package de.greluc.krt.profit.basetool.backend.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Pins every {@link FinanceType} predicate over all constants (ADR-0238). */
class FinanceTypeTest {

  @Test
  void incomeAddsAndExpenseSubtractsTheAmount() {
    BigDecimal amount = new BigDecimal("1250.50");
    Map<FinanceType, BigDecimal> expected =
        Map.of(FinanceType.INCOME, amount, FinanceType.EXPENSE, new BigDecimal("-1250.50"));

    assertThat(expected).containsOnlyKeys(Arrays.asList(FinanceType.values()));
    for (FinanceType type : FinanceType.values()) {
      assertThat(type.signed(amount)).as(type.name()).isEqualTo(expected.get(type));
    }
  }
}
