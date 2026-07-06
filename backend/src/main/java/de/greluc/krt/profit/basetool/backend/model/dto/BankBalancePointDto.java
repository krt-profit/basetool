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

package de.greluc.krt.profit.basetool.backend.model.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One sampled point of an account's balance-over-time series (REQ-BANK-049): the running account
 * balance as of the end of a calendar bucket. Points are ordered oldest-first; the last point's
 * balance equals the account's balance at the period end (the current balance when the period ends
 * "now"). Bucketed in UTC, matching the ledger's storage zone.
 *
 * @param date the inclusive end instant of the bucket the balance is sampled at (UTC)
 * @param balance the running account balance as of {@code date} (whole aUEC)
 */
public record BankBalancePointDto(Instant date, BigDecimal balance) {}
