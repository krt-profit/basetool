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

/**
 * The job-order route family is judged on the decoded path, as routing matches it, and its writes
 * are its {@code POST}s (REQ-OXCH-009).
 */
class JobOrderExchangeRoutesTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/exchange/job-orders",
        "/api/v1/exchange/job-orders/",
        "/api/v1/exchange/job-orders/feed",
        "/api/v1/exchange/job%2Dorders/create-options",
        "/api/v1/exchange/%6Aob-orders/1/cancel",
        "/api/v1/exchange/job-orders;x=1/1/target"
      })
  void everySpellingOfAJobOrderPathIsInTheFamily(String path) {
    assertThat(JobOrderExchangeRoutes.isJobOrder(path)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/exchange/job-orders-archive",
        "/api/v1/exchange/me/store-choices/job-orders",
        "/api/v1/exchange/bank/requests",
        "/api/v1/exchange"
      })
  void aLookalikeOrForeignPathIsNot(String path) {
    assertThat(JobOrderExchangeRoutes.isJobOrder(path)).isFalse();
  }

  @Test
  void noPathIsNoJobOrderPath() {
    assertThat(JobOrderExchangeRoutes.isJobOrder(null)).isFalse();
  }

  @Test
  void onlyAPostInTheFamilyIsAWrite() {
    assertThat(JobOrderExchangeRoutes.isWrite("POST", "/api/v1/exchange/job-orders")).isTrue();
    assertThat(JobOrderExchangeRoutes.isWrite("POST", "/api/v1/exchange/job-orders/1/comment"))
        .isTrue();
    assertThat(JobOrderExchangeRoutes.isWrite("GET", "/api/v1/exchange/job-orders")).isFalse();
    assertThat(JobOrderExchangeRoutes.isWrite("POST", "/api/v1/exchange/me/stock/changes"))
        .isFalse();
    assertThat(JobOrderExchangeRoutes.isWrite(null, "/api/v1/exchange/job-orders")).isFalse();
  }
}
