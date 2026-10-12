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

package de.greluc.krt.profit.basetool.backend.platform.api;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Builders shared by the {@link BusinessGaugeSource}s: the queue-depth and the age gauge. */
public final class BusinessGauges {

  private BusinessGauges() {}

  /**
   * Registers a queue-depth gauge with an optional bounded {@code status} tag.
   *
   * @param registry the registry to register with
   * @param name the meter name
   * @param holder the mutable holder the sample writes the current count into
   * @param status the bounded status tag value, or empty for an unlabelled total
   */
  public static void countGauge(
      @NotNull MeterRegistry registry,
      @NotNull String name,
      @NotNull AtomicLong holder,
      @NotNull String... status) {
    Gauge.Builder<AtomicLong> builder = Gauge.builder(name, holder, AtomicLong::doubleValue);
    if (status.length == 1) {
      builder = builder.tag(MetricNames.TAG_STATUS, status[0]);
    }
    builder.register(registry);
  }

  /**
   * Registers an "oldest pending age" gauge whose value is a duration rendered in seconds.
   *
   * @param registry the registry to register with
   * @param name the meter name
   * @param holder the mutable holder the sample writes the current age (in seconds) into
   */
  public static void ageGauge(
      @NotNull MeterRegistry registry, @NotNull String name, @NotNull AtomicLong holder) {
    Gauge.builder(name, holder, AtomicLong::doubleValue)
        .baseUnit(MetricNames.UNIT_SECONDS)
        .register(registry);
  }

  /**
   * Converts the creation instant of the oldest waiting item into an age in seconds.
   *
   * @param oldest the earliest {@code createdAt} in the queue, or {@code null} when it is empty
   * @return the non-negative age in seconds, or {@code 0} when the queue is empty
   */
  public static long ageSeconds(@Nullable Instant oldest) {
    if (oldest == null) {
      return 0L;
    }
    return Math.max(0L, Instant.now().getEpochSecond() - oldest.getEpochSecond());
  }
}
