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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The job-order family's counters on the exchange (REQ-OXCH-009, REQ-OBS-025): every refusal reason
 * exists at zero before the first refusal, and reads are counted by registry client and route.
 */
class ExchangeJobOrderMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final ExchangeJobOrderMetrics metrics = new ExchangeJobOrderMetrics(registry);

  @Test
  void everyRefusalReasonOfTheFamilyIsRegisteredAtZero() {
    metrics.registerRefusalCounters();

    assertThat(registry.find("basetool.exchange.job.order.refused").counters())
        .allSatisfy(counter -> assertThat(counter.count()).isZero())
        .extracting(counter -> counter.getId().getTag("reason"))
        .containsExactlyInAnyOrder(
            "not_found",
            "not_permitted",
            "units_not_offered",
            "version_conflict",
            "responsibility_invalid",
            "closed",
            "money_started",
            "target_too_low",
            "open_order_cap",
            "daily_create_cap",
            "switched_off",
            "writes_switched_off");
  }

  @Test
  void aRefusalCountsOnItsOwnReasonOnly() {
    metrics.registerRefusalCounters();

    metrics.countRefused(JobOrderExchangeRefusal.WRITES_SWITCHED_OFF);

    assertThat(refused("writes_switched_off")).isEqualTo(1.0d);
    assertThat(refused("switched_off")).isZero();
  }

  @Test
  void aReadIsCountedByClientAndRoute() {
    metrics.countRead("versekit", JobOrderExchangeReadRoute.SNAPSHOT);
    metrics.countRead("versekit", JobOrderExchangeReadRoute.CREATE_OPTIONS);
    metrics.countRead("versekit", JobOrderExchangeReadRoute.CREATE_OPTIONS);

    List<Meter.Id> ids =
        registry.find("basetool.exchange.job.order.reads").counters().stream()
            .map(Counter::getId)
            .toList();
    assertThat(ids)
        .extracting(id -> id.getTag("client_id") + " " + id.getTag("route"))
        .containsExactlyInAnyOrder("versekit snapshot", "versekit create_options");
    assertThat(
            registry
                .get("basetool.exchange.job.order.reads")
                .tag("route", "create_options")
                .counter()
                .count())
        .isEqualTo(2.0d);
    assertThat(JobOrderExchangeReadRoute.FEED.getTag()).isEqualTo("feed");
  }

  /**
   * Reads one refusal counter.
   *
   * @param reason the reason
   * @return the count
   */
  private double refused(String reason) {
    return registry
        .get("basetool.exchange.job.order.refused")
        .tag("reason", reason)
        .counter()
        .count();
  }
}
