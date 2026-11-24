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

import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeProblemException;
import java.util.EnumMap;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * How {@link ExchangeGate} refuses a request that a switch stops while it is off (REQ-XCH-025): the
 * requests the switch covers, the gate's refusal reason, the gateway's problem for the same
 * situation and the route family's own refusal counter.
 *
 * @param covers tells whether a request falls under the switch
 * @param reason the {@code reason} tag of the gate's refusal counter
 * @param problem builds the refusal with the gateway's code and status
 * @param countFamily counts the refusal on its route family's metric
 */
record ExchangeSwitchRefusal(
    @NotNull Coverage covers,
    @NotNull String reason,
    @NotNull Supplier<ExchangeProblemException> problem,
    @NotNull Runnable countFamily) {

  /** Tells whether a request falls under a switch. */
  @FunctionalInterface
  interface Coverage {

    /**
     * Tests a request.
     *
     * @param method the request's HTTP method, or {@code null} outside a servlet request
     * @param path the raw request URI, or {@code null} outside a servlet request
     * @return {@code true} when the switch stops the request while it is off
     */
    boolean test(@Nullable String method, @Nullable String path);
  }

  /**
   * Maps every switch to its refusal, in the switches' declaration order.
   *
   * @param bankMetrics counts a refused bank route
   * @param jobOrderMetrics counts a refused job-order route
   * @return one refusal per {@link ExchangeSwitch} constant
   */
  static @NotNull EnumMap<ExchangeSwitch, ExchangeSwitchRefusal> byArea(
      ExchangeBankMetrics bankMetrics, ExchangeJobOrderMetrics jobOrderMetrics) {
    EnumMap<ExchangeSwitch, ExchangeSwitchRefusal> refusals = new EnumMap<>(ExchangeSwitch.class);
    for (ExchangeSwitch target : ExchangeSwitch.values()) {
      refusals.put(target, of(target, bankMetrics, jobOrderMetrics));
    }
    return refusals;
  }

  /**
   * Builds the refusal of one switch.
   *
   * @param target the switch
   * @param bankMetrics counts a refused bank route
   * @param jobOrderMetrics counts a refused job-order route
   * @return the switch's refusal
   */
  private static @NotNull ExchangeSwitchRefusal of(
      @NotNull ExchangeSwitch target,
      ExchangeBankMetrics bankMetrics,
      ExchangeJobOrderMetrics jobOrderMetrics) {
    return switch (target) {
      case GLOBAL ->
          new ExchangeSwitchRefusal(
              (_, _) -> true,
              ExchangeGate.REASON_SWITCH_OFF,
              ExchangeProblemException::exchangeDisabled,
              ExchangeSwitchRefusal::countNoFamily);
      case BANK ->
          new ExchangeSwitchRefusal(
              (_, path) -> BankExchangeRoutes.isBank(path),
              ExchangeGate.REASON_BANK_SWITCH_OFF,
              ExchangeProblemException::bankExchangeDisabled,
              () -> bankMetrics.countRefused(BankExchangeRefusal.SWITCHED_OFF));
      case JOB_ORDERS ->
          new ExchangeSwitchRefusal(
              (_, path) -> JobOrderExchangeRoutes.isJobOrder(path),
              ExchangeGate.REASON_JOB_ORDER_SWITCH_OFF,
              ExchangeProblemException::jobOrderExchangeDisabled,
              () -> jobOrderMetrics.countRefused(JobOrderExchangeRefusal.SWITCHED_OFF));
      case JOB_ORDER_WRITES ->
          new ExchangeSwitchRefusal(
              JobOrderExchangeRoutes::isWrite,
              ExchangeGate.REASON_JOB_ORDER_WRITES_SWITCH_OFF,
              ExchangeProblemException::jobOrderWritesDisabled,
              () -> jobOrderMetrics.countRefused(JobOrderExchangeRefusal.WRITES_SWITCHED_OFF));
    };
  }

  /** Counts nothing beyond the gate's own counter, for a switch that covers no single family. */
  private static void countNoFamily() {}
}
