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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The per-status registry client gauges (REQ-XCH-028). */
class ExchangeClientGaugesTest {

  private SimpleMeterRegistry meterRegistry;
  private ExchangeClientGauges gauges;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    gauges = new ExchangeClientGauges(meterRegistry);
    gauges.register();
  }

  @Test
  void everyStatusIsRegisteredAtZero() {
    for (ExchangeClientStatus status : ExchangeClientStatus.values()) {
      assertThat(value(status)).isZero();
    }
  }

  @Test
  void anUpdateCountsTheClientsPerStatus() {
    gauges.update(
        snapshot(
            Map.of(
                "a", ExchangeClientStatus.ACTIVE,
                "b", ExchangeClientStatus.ACTIVE,
                "c", ExchangeClientStatus.SUSPENDED)));

    assertThat(value(ExchangeClientStatus.ACTIVE)).isEqualTo(2.0d);
    assertThat(value(ExchangeClientStatus.SUSPENDED)).isEqualTo(1.0d);
  }

  @Test
  void aStatusNoLongerHeldFallsBackToZero() {
    gauges.update(snapshot(Map.of("a", ExchangeClientStatus.SUSPENDED)));
    gauges.update(snapshot(Map.of("a", ExchangeClientStatus.ACTIVE)));

    assertThat(value(ExchangeClientStatus.ACTIVE)).isEqualTo(1.0d);
    assertThat(value(ExchangeClientStatus.SUSPENDED)).isZero();
  }

  /**
   * Builds a snapshot of clients with the given statuses.
   *
   * @param statuses the status per client id
   * @return the snapshot
   */
  private static @NotNull ExchangeRegistrySnapshot snapshot(
      @NotNull Map<String, ExchangeClientStatus> statuses) {
    TreeMap<String, ExchangeRegistrySnapshot.Client> clients = new TreeMap<>();
    statuses.forEach(
        (id, status) ->
            clients.put(
                id,
                new ExchangeRegistrySnapshot.Client(
                    id, status, List.of("exchange.connect"), null, null, null)));
    return new ExchangeRegistrySnapshot(true, clients);
  }

  /**
   * Reads one status gauge.
   *
   * @param status the status
   * @return the gauge's value
   */
  private double value(@NotNull ExchangeClientStatus status) {
    return meterRegistry
        .get("basetool.exchange.clients")
        .tag("status", status.name())
        .gauge()
        .value();
  }
}
