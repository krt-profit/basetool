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

/**
 * Account detail page payload: the account, its facts-strip figures and the caller's booking
 * capabilities, from which the UI renders its action buttons (REQ-BANK-009).
 *
 * @param account the account incl. its compute-on-read balance
 * @param delta30d net change over the last 30 days (signed whole aUEC)
 * @param bookingCount total number of account ledger legs on the account
 * @param capabilities the caller's evaluated capabilities on this account
 * @param approvalLimits the account's per-tier approval limits (REQ-BANK-041) for display
 */
public record BankAccountDetailDto(
    BankAccountDto account,
    BigDecimal delta30d,
    long bookingCount,
    BankCapabilitiesDto capabilities,
    BankApprovalLimitsDto approvalLimits) {}
