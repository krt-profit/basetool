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

package de.greluc.krt.profit.basetool.ingest.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.ingest.registry.ExchangeRegistry;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeRegistryReader;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeUnavailableException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExchangeRefusalsTest {

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final ExchangeRegistryReader registryReader = mock(ExchangeRegistryReader.class);
  private final ExchangeRefusals refusals = new ExchangeRefusals(meters, registryReader);

  @BeforeEach
  void setUp() {
    when(registryReader.current())
        .thenReturn(
            new ExchangeRegistry(
                1L,
                true,
                Map.of(
                    "versekit",
                    new ExchangeRegistry.Client(
                        "VerseKit", true, Set.of("exchange.connect"), null, null, null))));
    refusals.register();
  }

  @Test
  void aRegisteredClientIsItsOwnLabel() {
    assertThat(refusals.clientLabel("versekit")).isEqualTo("versekit");
  }

  @Test
  void anyOtherAzpIsUnregisteredSoTheLabelStaysBounded() {
    assertThat(refusals.clientLabel("some-realm-client"))
        .isEqualTo(MetricNames.EXCHANGE_CLIENT_UNREGISTERED);
  }

  @Test
  void noAzpIsNone() {
    assertThat(refusals.clientLabel(null)).isEqualTo(MetricNames.EXCHANGE_CLIENT_NONE);
    assertThat(refusals.clientLabel(" ")).isEqualTo(MetricNames.EXCHANGE_CLIENT_NONE);
  }

  @Test
  void anUnreadableRegistryIsUnknown() {
    when(registryReader.current()).thenThrow(new ExchangeUnavailableException("down", null));

    assertThat(refusals.clientLabel("versekit")).isEqualTo(MetricNames.EXCHANGE_CLIENT_UNKNOWN);
  }

  @Test
  void everyReasonIsRegisteredAtZeroAndCountedPerClient() {
    assertThat(meters.get(MetricNames.EXCHANGE_REFUSED).counters())
        .hasSize(ExchangeRefusals.CODES.size())
        .allSatisfy(
            counter ->
                assertThat(counter.getId().getTag(MetricNames.TAG_CLIENT_ID))
                    .isEqualTo(MetricNames.EXCHANGE_CLIENT_NONE));

    refusals.count(ExchangeRefusals.SCOPE_MISSING, "versekit");

    Counter counted =
        meters
            .get(MetricNames.EXCHANGE_REFUSED)
            .tag(MetricNames.TAG_REASON, "scope_missing")
            .tag(MetricNames.TAG_CLIENT_ID, "versekit")
            .counter();
    assertThat(counted.count()).isEqualTo(1.0d);
  }
}
