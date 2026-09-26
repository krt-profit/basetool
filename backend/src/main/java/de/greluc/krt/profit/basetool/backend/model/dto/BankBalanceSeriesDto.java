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
import java.util.List;

/**
 * Account-detail balance chart payload (REQ-BANK-049): the end-of-bucket balance series over the
 * chosen period plus the balance target for the reference line. Computed on demand from the ledger;
 * carries no holder or counterparty data.
 *
 * @param points the end-of-bucket balances, oldest first; possibly empty
 * @param balanceTarget the account's balance target (REQ-BANK-036), or {@code null} when none is
 *     set
 */
public record BankBalanceSeriesDto(List<BankBalancePointDto> points, BigDecimal balanceTarget) {}
