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

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Publishes {@code basetool_exchange_clients{status}}, the registry clients per status, from the
 * registry snapshots the mirror sync already reads; a scrape never queries the database
 * (REQ-XCH-028).
 */
@Component
@RequiredArgsConstructor
public class ExchangeClientGauges {

  /** The registry the gauges are published to. */
  private final MeterRegistry meterRegistry;

  /** The last counted number of clients per status. */
  private final Map<ExchangeClientStatus, AtomicInteger> counts =
      new EnumMap<>(ExchangeClientStatus.class);

  /** Registers one gauge per status at zero. */
  @PostConstruct
  void register() {
    for (ExchangeClientStatus status : ExchangeClientStatus.values()) {
      AtomicInteger count = new AtomicInteger();
      counts.put(status, count);
      Gauge.builder(MetricNames.EXCHANGE_CLIENTS, count, AtomicInteger::get)
          .description("Exchange registry clients per status.")
          .tag(MetricNames.TAG_STATUS, status.name())
          .register(meterRegistry);
    }
  }

  /**
   * Sets every status gauge to the number of clients the snapshot holds in that status.
   *
   * @param snapshot the registry as last read
   */
  public void update(@NotNull ExchangeRegistrySnapshot snapshot) {
    Map<ExchangeClientStatus, Integer> tally = new EnumMap<>(ExchangeClientStatus.class);
    for (ExchangeRegistrySnapshot.Client client : snapshot.clients().values()) {
      tally.merge(client.status(), 1, Integer::sum);
    }
    counts.forEach((status, count) -> count.set(tally.getOrDefault(status, 0)));
  }
}
