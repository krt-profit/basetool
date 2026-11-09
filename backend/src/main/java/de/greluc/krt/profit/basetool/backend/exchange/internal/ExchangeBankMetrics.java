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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Counts the bank's traffic on the exchange (REQ-BXCH-006, REQ-OBS-024): reads by registered client
 * and route, request writes by client and outcome, refusals by reason. Every label is a registry
 * client id or a fixed enumeration, never an id, a path or an amount (REQ-OBS-011).
 */
@Component
@RequiredArgsConstructor
public class ExchangeBankMetrics {

  private final MeterRegistry meterRegistry;

  /** Registers the refusal counter for every reason at zero, so a first refusal is an increase. */
  @PostConstruct
  void registerRefusalCounters() {
    for (BankExchangeRefusal reason : BankExchangeRefusal.values()) {
      refusalCounter(reason);
    }
  }

  /**
   * Counts one bank read served.
   *
   * @param clientId the registry client id the relay bounded
   * @param route the bank route
   */
  public void countRead(@NotNull String clientId, @NotNull BankExchangeReadRoute route) {
    meterRegistry
        .counter(
            ExchangeMetricNames.EXCHANGE_BANK_READS,
            MetricNames.TAG_CLIENT_ID,
            clientId,
            ExchangeMetricNames.TAG_ROUTE,
            route.getTag())
        .increment();
  }

  /**
   * Counts one bank request write on {@code
   * basetool_exchange_writes_total{resource="bank_request"}} (REQ-BXCH-004).
   *
   * @param clientId the registry client id the relay bounded
   * @param outcome {@code applied} or {@code rejected}
   */
  public void countWrite(@NotNull String clientId, @NotNull String outcome) {
    meterRegistry
        .counter(
            ExchangeMetricNames.EXCHANGE_WRITES,
            MetricNames.TAG_CLIENT_ID,
            clientId,
            ExchangeMetricNames.TAG_RESOURCE,
            ExchangeFeedResource.BANK_REQUEST.name().toLowerCase(Locale.ROOT),
            MetricNames.TAG_OUTCOME,
            outcome)
        .increment();
  }

  /**
   * Counts one bank refusal.
   *
   * @param reason the bounded reason
   */
  public void countRefused(@NotNull BankExchangeRefusal reason) {
    refusalCounter(reason).increment();
  }

  /**
   * Returns the refusal counter of one reason.
   *
   * @param reason the bounded reason
   * @return the counter, registered on first use
   */
  private @NotNull Counter refusalCounter(@NotNull BankExchangeRefusal reason) {
    return meterRegistry.counter(
        ExchangeMetricNames.EXCHANGE_BANK_REFUSED, MetricNames.TAG_REASON, reason.getTag());
  }
}
