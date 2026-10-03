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

import de.greluc.krt.profit.basetool.ingest.registry.ExchangeRegistryReader;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeUnavailableException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes {@code basetool_exchange_registry_mirror_age_seconds}, the seconds since the gateway
 * last read the registry mirror successfully, and reads the mirror on a fixed delay so the gauge
 * does not depend on exchange traffic (REQ-XCH-003, REQ-XCH-028).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExchangeRegistryMirrorAge {

  /** The reader whose last successful read the gauge reports. */
  private final ExchangeRegistryReader registryReader;

  /** The registry the gauge is published to. */
  private final MeterRegistry meterRegistry;

  /** Registers the gauge, {@code NaN} until the first successful read. */
  @PostConstruct
  public void register() {
    Gauge.builder(
            MetricNames.EXCHANGE_REGISTRY_MIRROR_AGE,
            registryReader,
            ExchangeRegistryReader::secondsSinceLastRead)
        .description("Seconds since the gateway last read the exchange registry mirror.")
        .baseUnit("seconds")
        .register(meterRegistry);
  }

  /**
   * Reads the mirror through the reader's cache; a failure leaves the last good read in place and
   * the gauge growing.
   */
  @Scheduled(
      fixedDelayString = "${app.exchange.registry-refresh-interval:PT30S}",
      initialDelayString = "${app.exchange.registry-refresh-interval:PT30S}")
  public void refresh() {
    try {
      registryReader.current();
    } catch (ExchangeUnavailableException e) {
      log.debug("Exchange registry refresh failed: {}", e.getMessage());
    }
  }
}
