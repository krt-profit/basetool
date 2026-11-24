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
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Counts the job orders' traffic on the exchange (REQ-OXCH-009, REQ-OBS-025): reads by registered
 * client and route, writes by client and outcome, refusals by reason, and the fingerprint
 * recomputations by outcome (REQ-OXCH-003). Every label is a registry client id or a fixed
 * enumeration, never an id, a path or an amount (REQ-OBS-011).
 */
@Component
@RequiredArgsConstructor
public class ExchangeJobOrderMetrics {

  private final MeterRegistry meterRegistry;

  /**
   * Registers every refusal reason and fingerprint outcome at zero, so a first count is an
   * increase.
   */
  @PostConstruct
  void registerCounters() {
    registerRefusalCounters();
    for (JobOrderFingerprintOutcome outcome : JobOrderFingerprintOutcome.values()) {
      fingerprintCounter(outcome);
    }
  }

  /** Registers the refusal counter for every reason at zero, so a first refusal is an increase. */
  void registerRefusalCounters() {
    for (JobOrderExchangeRefusal reason : JobOrderExchangeRefusal.values()) {
      refusalCounter(reason);
    }
  }

  /**
   * Counts one job-order read served.
   *
   * @param clientId the registry client id the relay bounded
   * @param route the job-order route
   */
  public void countRead(@NotNull String clientId, @NotNull JobOrderExchangeReadRoute route) {
    meterRegistry
        .counter(
            ExchangeMetricNames.EXCHANGE_JOB_ORDER_READS,
            MetricNames.TAG_CLIENT_ID,
            clientId,
            ExchangeMetricNames.TAG_ROUTE,
            route.getTag())
        .increment();
  }

  /**
   * Counts one job-order refusal.
   *
   * @param reason the bounded reason
   */
  public void countRefused(@NotNull JobOrderExchangeRefusal reason) {
    refusalCounter(reason).increment();
  }

  /**
   * Counts one refused job-order write: the refusal under its reason and the write as {@link
   * ExchangeOps#REJECTED}.
   *
   * @param clientId the registry client id the relay bounded
   * @param reason the bounded reason
   * @param refusal the exception the client's answer comes from
   * @param <E> the exception's type
   * @return the refusal, for the caller to throw
   */
  public <E extends RuntimeException> @NotNull E refusedWrite(
      @NotNull String clientId, @NotNull JobOrderExchangeRefusal reason, @NotNull E refusal) {
    countRefused(reason);
    countWrite(clientId, ExchangeOps.REJECTED);
    return refusal;
  }

  /**
   * Counts one job-order write on {@code basetool_exchange_writes_total{resource="job_order"}}.
   *
   * @param clientId the registry client id the relay bounded
   * @param outcome {@link ExchangeOps#APPLIED} or {@link ExchangeOps#REJECTED}
   */
  public void countWrite(@NotNull String clientId, @NotNull String outcome) {
    ExchangeWrites.count(meterRegistry, clientId, ExchangeFeedResource.JOB_ORDER, outcome);
  }

  /**
   * Counts the outcomes of one committed recomputation batch of the fingerprint worker.
   *
   * @param changed the orders whose fingerprint changed and was written
   * @param unchanged the orders whose fingerprint equalled the stored one
   */
  public void countFingerprints(int changed, int unchanged) {
    fingerprintCounter(JobOrderFingerprintOutcome.CHANGED).increment(changed);
    fingerprintCounter(JobOrderFingerprintOutcome.UNCHANGED).increment(unchanged);
  }

  /**
   * Returns the fingerprint counter of one outcome.
   *
   * @param outcome the outcome
   * @return the counter, registered on first use
   */
  private @NotNull Counter fingerprintCounter(@NotNull JobOrderFingerprintOutcome outcome) {
    return meterRegistry.counter(
        ExchangeMetricNames.EXCHANGE_JOB_ORDER_FINGERPRINTS,
        MetricNames.TAG_OUTCOME,
        outcome.getTag());
  }

  /**
   * Returns the refusal counter of one reason.
   *
   * @param reason the bounded reason
   * @return the counter, registered on first use
   */
  private @NotNull Counter refusalCounter(@NotNull JobOrderExchangeRefusal reason) {
    return meterRegistry.counter(
        ExchangeMetricNames.EXCHANGE_JOB_ORDER_REFUSED, MetricNames.TAG_REASON, reason.getTag());
  }
}
