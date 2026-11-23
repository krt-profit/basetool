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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The caps on the aUEC orders a member creates through clients (REQ-OXCH-009); orders created in
 * the Basetool count for neither.
 *
 * @param openOrderCap the number of open or in-progress orders the member created through any
 *     client at which the next client create is refused with {@code 409 JOB_ORDER_OPEN_ORDER_CAP}
 * @param dailyCreateCap the number of orders the member created through clients within the UTC day,
 *     whatever their status since, at which the next client create is refused with {@code 409
 *     JOB_ORDER_DAILY_CREATE_CAP}
 */
@Validated
@ConfigurationProperties("app.joborder.exchange")
public record JobOrderExchangeProperties(
    @DefaultValue("20") @Min(1) @Max(MAX_CAP) int openOrderCap,
    @DefaultValue("20") @Min(1) @Max(MAX_CAP) int dailyCreateCap) {

  /** The highest configurable cap; above it a cap stops being a brake on a looping client. */
  public static final int MAX_CAP = 1000;
}
