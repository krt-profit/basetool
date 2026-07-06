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

package de.greluc.krt.profit.basetool.frontend.model.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Frontend mirror of one point of the account balance-over-time series (REQ-BANK-049): the running
 * account balance sampled at the end of a UTC calendar bucket. Points are oldest-first.
 *
 * @param date the bucket-end instant the balance is sampled at (UTC)
 * @param balance the running account balance as of {@code date} (whole aUEC)
 */
public record BankBalancePointDto(Instant date, BigDecimal balance) {}
