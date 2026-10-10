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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeInsuranceDto;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ExchangeFeedMappingTest {

  @Test
  void insuranceReadsAsLifetimeOrMonths() {
    assertThat(ExchangeShipFeedService.insurance("LTI"))
        .isEqualTo(new ExchangeInsuranceDto("LTI", null));
    assertThat(ExchangeShipFeedService.insurance("120"))
        .isEqualTo(new ExchangeInsuranceDto("MONTHS", 120));
    assertThat(ExchangeShipFeedService.insurance("0"))
        .isEqualTo(new ExchangeInsuranceDto("MONTHS", 0));
  }

  @Test
  void aShipStoredWithoutInsuranceReadsAsZeroMonths() {
    assertThat(ExchangeShipFeedService.insurance(null))
        .isEqualTo(new ExchangeInsuranceDto("MONTHS", 0));
    assertThat(ExchangeShipFeedService.insurance("forever"))
        .isEqualTo(new ExchangeInsuranceDto("MONTHS", 0));
  }

  @Test
  void anAmountIsRoundedToItsUnitInPlainNotation() {
    assertThat(ExchangeStockFeedService.amount(3.2504, "SCU")).isEqualTo(new BigDecimal("3.25"));
    assertThat(ExchangeStockFeedService.amount(10.0, "SCU").toPlainString()).isEqualTo("10");
    assertThat(ExchangeStockFeedService.amount(0.0005, "SCU")).isEqualTo(new BigDecimal("0.001"));
    assertThat(ExchangeStockFeedService.amount(2.4, "PIECE").toPlainString()).isEqualTo("2");
  }
}
