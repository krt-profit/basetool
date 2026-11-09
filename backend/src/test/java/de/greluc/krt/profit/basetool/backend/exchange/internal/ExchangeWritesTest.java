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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/**
 * Every exchange write, a change-set op or a bank request, lands on the one series {@code
 * basetool_exchange_writes_total{client_id, resource, outcome}} (REQ-OBS-024).
 */
class ExchangeWritesTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  @Test
  void aChangeSetOpAndABankRequestShareTheSeriesWithTheirFeedAsResource() {
    new ExchangeBankMetrics(registry).countWrite("versekit", "applied");
    ExchangeWrites.count(registry, "versekit", ExchangeFeedResource.ORG_STOCK, "applied");

    assertThat(count("bank_request")).isEqualTo(1.0d);
    assertThat(count("org_stock")).isEqualTo(1.0d);
  }

  private double count(String resource) {
    return registry
        .get("basetool.exchange.writes")
        .tag("client_id", "versekit")
        .tag("resource", resource)
        .tag("outcome", "applied")
        .counter()
        .count();
  }
}
