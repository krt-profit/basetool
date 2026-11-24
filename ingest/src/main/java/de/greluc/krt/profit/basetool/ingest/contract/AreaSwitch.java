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

package de.greluc.krt.profit.basetool.ingest.contract;

import java.util.Arrays;
import java.util.List;
import java.util.function.BiPredicate;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.http.HttpMethod;

/**
 * A switch of one area of the exchange beside the global switch, in the order the gate checks them
 * (REQ-BXCH-006, REQ-OXCH-009): the registry mirror member that carries it, which requests it stops
 * and the {@code 503} problem code and detail it answers with while it is off. A new area switch is
 * one constant here.
 */
@RequiredArgsConstructor
public enum AreaSwitch {

  /** The bank switch: every path at or below {@link ExchangeRoutes#BANK_PATH}. */
  BANK(
      "bankEnabled",
      (method, path) -> ExchangeRoutes.isBank(path),
      "BANK_EXCHANGE_DISABLED",
      "The bank is switched off for connected applications; every other area keeps working."),

  /** The job-order family switch: every path at or below {@link ExchangeRoutes#JOB_ORDERS_PATH}. */
  JOB_ORDERS(
      "jobOrdersEnabled",
      (method, path) -> ExchangeRoutes.isJobOrder(path),
      "JOB_ORDER_EXCHANGE_DISABLED",
      "Job orders are switched off for connected applications; every other area keeps working."),

  /**
   * The job-order writes switch: every {@code POST} at or below {@link
   * ExchangeRoutes#JOB_ORDERS_PATH}, since every job-order write is a {@code POST}.
   */
  JOB_ORDER_WRITES(
      "jobOrderWritesEnabled",
      (method, path) -> HttpMethod.POST.matches(method) && ExchangeRoutes.isJobOrder(path),
      "JOB_ORDER_WRITES_DISABLED",
      "Job-order writes are switched off for connected applications; job-order reads and every"
          + " other area keep working.");

  /**
   * The boolean member of the registry mirror document that carries the switch; a document without
   * it reads the switch as off.
   */
  @Getter @NotNull private final String mirrorField;

  /** Tells from a request's HTTP method and raw path whether it lies in the switch's area. */
  @NotNull private final BiPredicate<String, String> area;

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
  public static @NotNull @Unmodifiable List<AreaSwitch> gating(
      @NotNull String method, @NotNull String path) {
    return Arrays.stream(values()).filter(target -> target.gates(method, path)).toList();
  }

  /**
   * Tells whether this switch gates a request.
   *
   * @param method the request's HTTP method
   * @param path the raw request path
   * @return {@code true} when the request lies in this switch's area
   */
  public boolean gates(@NotNull String method, @NotNull String path) {
    return area.test(method, path);
  }
}
