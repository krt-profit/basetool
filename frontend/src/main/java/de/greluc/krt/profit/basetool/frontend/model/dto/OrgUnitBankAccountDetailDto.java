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
import org.jetbrains.annotations.Nullable;

/**
 * Frontend mirror of the read-only account detail an org-unit viewer sees (REQ-BANK-038): the
 * shared account detail (with all-false capabilities) plus the org-unit-side affordances.
 *
 * @param detail the shared account detail (account + balance + target + 30-day delta + booking
 *     count)
 * @param canExportStatement whether the caller may export the Halter-redacted Kontoauszug
 * @param canSetTarget whether the caller may set/clear the balance target
 * @param canConfigureVisibility whether the caller may manage who else may view the account
 * @param canRequest whether the caller may raise a booking request against this account
 * @param canConfigureApprovalLimits whether the caller may set/clear this account's approval limits
 *     (REQ-BANK-041)
 * @param applicableLimit the caller's resolved approval limit for this account (REQ-BANK-041), or
 *     {@code null} when no limit applies — which means approval is required, unless {@code
 *     approvalExempt} is set
 * @param approvalExempt {@code true} iff the caller is this account's responsible holder and is
 *     bound by no approval ceiling (REQ-BANK-041, owner decision)
 */
public record OrgUnitBankAccountDetailDto(
    BankAccountDetailDto detail,
    boolean canExportStatement,
    boolean canSetTarget,
    boolean canConfigureVisibility,
    boolean canRequest,
    boolean canConfigureApprovalLimits,
    @Nullable BigDecimal applicableLimit,
    boolean approvalExempt) {}
