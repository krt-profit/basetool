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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The bank route family is judged on the decoded path, as routing matches it (REQ-BXCH-006). */
class BankExchangeRoutesTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/exchange/bank",
        "/api/v1/exchange/bank/",
        "/api/v1/exchange/bank/accounts",
        "/api/v1/exchange/b%61nk/accounts",
        "/api/v1/exchange/%62ank/requests/1/cancel",
        "/api/v1/exchange/bank;x=1/queue"
      })
  void everySpellingOfABankPathIsInTheFamily(String path) {
    assertThat(BankExchangeRoutes.isBank(path)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"/api/v1/exchange/bankers", "/api/v1/exchange/me/stock", "/api/v1/exchange"})
  void aLookalikeOrForeignPathIsNot(String path) {
    assertThat(BankExchangeRoutes.isBank(path)).isFalse();
  }

  @Test
  void noPathIsNoBankPath() {
    assertThat(BankExchangeRoutes.isBank(null)).isFalse();
  }
}
