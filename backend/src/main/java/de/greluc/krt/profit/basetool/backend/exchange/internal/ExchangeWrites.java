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

import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeMetricNames;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.jetbrains.annotations.NotNull;

/**
 * The one builder of {@code basetool_exchange_writes_total{client_id, resource, outcome}}: every
 * exchange write counts here, labelled with the feed its resource is sequenced in (REQ-OBS-024).
 */
final class ExchangeWrites {

  /** Not instantiable. */
  private ExchangeWrites() {}

  /**
   * Counts one exchange write.
   *
   * @param meterRegistry the registry the counter binds to
   * @param clientId the registry client id the relay bounded
   * @param resource the feed of the written resource; its lower-case name is the label
   * @param outcome the bounded outcome, such as {@code applied} or {@code rejected}
   */
  static void count(
      @NotNull MeterRegistry meterRegistry,
      @NotNull String clientId,
      @NotNull ExchangeFeedResource resource,
      @NotNull String outcome) {
    meterRegistry
        .counter(
            ExchangeMetricNames.EXCHANGE_WRITES,
            MetricNames.TAG_CLIENT_ID,
            clientId,
            ExchangeMetricNames.TAG_RESOURCE,
            resource.name().toLowerCase(Locale.ROOT),
            MetricNames.TAG_OUTCOME,
            outcome)
        .increment();
  }
}
