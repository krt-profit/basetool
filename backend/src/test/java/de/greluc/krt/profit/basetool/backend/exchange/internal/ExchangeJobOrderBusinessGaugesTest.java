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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/**
 * The job-order fingerprint backlog gauges (REQ-OXCH-003, REQ-OBS-025): the marked orders and the
 * age of the oldest mark in seconds, both zero before the first sample and with nothing marked.
 */
class ExchangeJobOrderBusinessGaugesTest {

  private final ExchangeJobOrderMarkRepository marks = mock(ExchangeJobOrderMarkRepository.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final ExchangeJobOrderBusinessGauges gauges = new ExchangeJobOrderBusinessGauges(marks);

  @Test
  void bothGaugesExistAtZeroBeforeTheFirstSampleAndTheAgeIsInSeconds() {
    gauges.register(registry);

    assertThat(count().value()).isZero();
    assertThat(age().value()).isZero();
    assertThat(age().getId().getBaseUnit()).isEqualTo("seconds");
  }

  @Test
  void aSampleReportsTheMarkedOrdersAndTheAgeOfTheOldestMark() {
    gauges.register(registry);
    when(marks.countMarkedOrders()).thenReturn(7L);
    when(marks.findOldestMarkedAt()).thenReturn(Instant.now().minus(10, ChronoUnit.MINUTES));

    gauges.sample();

    assertThat(count().value()).isEqualTo(7.0d);
    assertThat(age().value()).isBetween(600.0d, 660.0d);
  }

  @Test
  void anEmptyBacklogHasNoAge() {
    gauges.register(registry);
    when(marks.countMarkedOrders()).thenReturn(0L);
    when(marks.findOldestMarkedAt()).thenReturn(null);

    gauges.sample();

    assertThat(count().value()).isZero();
    assertThat(age().value()).isZero();
  }

  private Gauge count() {
    return registry.get("basetool.exchange.job.order.dirty.count").gauge();
  }

  private Gauge age() {
    return registry.get("basetool.exchange.job.order.dirty.oldest.age").gauge();
  }
}
