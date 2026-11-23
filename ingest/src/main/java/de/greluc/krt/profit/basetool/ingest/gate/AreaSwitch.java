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

package de.greluc.krt.profit.basetool.ingest.gate;

import de.greluc.krt.profit.basetool.ingest.contract.ExchangeRoutes;
import de.greluc.krt.profit.basetool.ingest.observability.ExchangeRefusals;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeRegistry;
import java.util.Arrays;
import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.http.HttpMethod;

/**
 * A switch of one area of the exchange beside the global switch, in the order the gate checks them
 * (REQ-BXCH-006, REQ-OXCH-009): which requests it stops and what it answers while it is off, always
 * {@code 503} with {@code Retry-After} {@value ExchangeGateFilter#AREA_RETRY_AFTER_SECONDS}.
 */
@RequiredArgsConstructor
enum AreaSwitch {

  /** The bank switch: every path at or below {@link ExchangeRoutes#BANK_PATH}. */
  BANK(ExchangeRefusals.BANK_EXCHANGE_DISABLED, ExchangeGateFilter.BANK_EXCHANGE_DISABLED_DETAIL),

  /** The job-order family switch: every path at or below {@link ExchangeRoutes#JOB_ORDERS_PATH}. */
  JOB_ORDERS(
      ExchangeRefusals.JOB_ORDER_EXCHANGE_DISABLED,
      ExchangeGateFilter.JOB_ORDER_EXCHANGE_DISABLED_DETAIL),

  /**
   * The job-order writes switch: every {@code POST} at or below {@link
   * ExchangeRoutes#JOB_ORDERS_PATH}, since every job-order write is a {@code POST}.
   */
  JOB_ORDER_WRITES(
      ExchangeRefusals.JOB_ORDER_WRITES_DISABLED,
      ExchangeGateFilter.JOB_ORDER_WRITES_DISABLED_DETAIL);

  /** The problem code of the refusal while the switch is off. */
  @Getter @NotNull private final String code;

  /** The detail of the refusal while the switch is off. */
  @Getter @NotNull private final String detail;

  /**
   * Returns the switches that gate a request, in the order the gate checks them.
   *
   * @param method the request's HTTP method
   * @param path the raw request path
   * @return the gating switches, empty for a request outside every switched area
   */
  static @NotNull @Unmodifiable List<AreaSwitch> gating(
      @NotNull String method, @NotNull String path) {
    return Arrays.stream(values()).filter(area -> area.gates(method, path)).toList();
  }

  /**
   * Tells whether this switch gates a request.
   *
   * @param method the request's HTTP method
   * @param path the raw request path
   * @return {@code true} when the request lies in this switch's area
   */
  boolean gates(@NotNull String method, @NotNull String path) {
    return switch (this) {
      case BANK -> ExchangeRoutes.isBank(path);
      case JOB_ORDERS -> ExchangeRoutes.isJobOrder(path);
      case JOB_ORDER_WRITES -> HttpMethod.POST.matches(method) && ExchangeRoutes.isJobOrder(path);
    };
  }

  /**
   * Tells whether this switch is off in the registry.
   *
   * @param registry the registry
   * @return {@code true} when the switch stops its area
   */
  boolean isOff(@NotNull ExchangeRegistry registry) {
    return switch (this) {
      case BANK -> !registry.bankEnabled();
      case JOB_ORDERS -> !registry.jobOrdersEnabled();
      case JOB_ORDER_WRITES -> !registry.jobOrderWritesEnabled();
    };
  }
}
