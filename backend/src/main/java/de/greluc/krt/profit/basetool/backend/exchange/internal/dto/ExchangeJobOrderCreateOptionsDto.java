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

package de.greluc.krt.profit.basetool.backend.exchange.internal.dto;

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderClientCreateCaps;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The choices a client offers before a member creates an aUEC order (REQ-OXCH-004): both lists are
 * empty when the member may create no order, and the read names no member.
 *
 * @param requestingUnits the member's writer units: the active units they directly belong to and
 *     are a Logistician of
 * @param responsibleUnits the eligible Profit units
 * @param suggestedResponsible the canonical Profit area when it is among the responsible units,
 *     else {@code null}
 * @param writesEnabled whether the job-order writes switch is on, so a create can be served
 * @param limits both caps on client creates and the member's own figures against them
 *     (REQ-OXCH-009)
 */
public record ExchangeJobOrderCreateOptionsDto(
    @NotNull @Unmodifiable List<ExchangeOrgUnitDto> requestingUnits,
    @NotNull @Unmodifiable List<ExchangeOrgUnitDto> responsibleUnits,
    @Nullable UUID suggestedResponsible,
    boolean writesEnabled,
    @NotNull Limits limits) {

  /**
   * Both caps on the aUEC orders a member creates through clients and the member's own figures
   * against them; orders created in the Basetool count for neither.
   *
   * @param openOrderCap the open or in-progress client-created orders at which a create answers
   *     {@code 409 JOB_ORDER_OPEN_ORDER_CAP}
   * @param openOrders the member's open or in-progress orders created through any client
   * @param dailyCreateCap the client creates within a UTC day at which a create answers {@code 409
   *     JOB_ORDER_DAILY_CREATE_CAP}
   * @param createdToday the orders the member created through clients since the start of the UTC
   *     day, cancelled ones included
   */
  @Schema(name = "ExchangeJobOrderCreateLimits")
  public record Limits(int openOrderCap, long openOrders, int dailyCreateCap, long createdToday) {

    /**
     * Copies the job orders' caps and figures into the exchange answer.
     *
     * @param limits the caps and the member's figures
     * @return the same figures in the answer's shape
     */
    public static @NotNull Limits of(@NotNull JobOrderClientCreateCaps.Limits limits) {
      return new Limits(
          limits.openOrderCap(),
          limits.openOrders(),
          limits.dailyCreateCap(),
          limits.createdToday());
    }
  }
}
