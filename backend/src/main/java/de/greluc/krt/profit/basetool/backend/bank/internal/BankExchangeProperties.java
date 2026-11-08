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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The bank's limits on the exchange (REQ-BXCH-006).
 *
 * @param openRequestCap the most {@code PENDING} booking requests a member may hold before an
 *     exchange client's next request is refused with {@code 409 BANK_OPEN_REQUEST_CAP}; every
 *     pending request of the member counts, whichever channel raised it
 */
@Validated
@ConfigurationProperties("app.bank.exchange")
public record BankExchangeProperties(
    @DefaultValue("20") @Min(1) @Max(MAX_OPEN_REQUEST_CAP) int openRequestCap) {

  /** The highest configurable cap; above it the cap stops being a brake on request spam. */
  public static final int MAX_OPEN_REQUEST_CAP = 1000;
}
