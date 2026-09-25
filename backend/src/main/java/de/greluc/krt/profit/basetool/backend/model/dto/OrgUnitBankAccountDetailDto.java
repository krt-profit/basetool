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
import org.jetbrains.annotations.Nullable;

/**
 * The read-only account detail an org-unit viewer sees on the org-unit bank page (REQ-BANK-038):
 * the shared {@link BankAccountDetailDto} with all-{@code false} capabilities plus the org-unit
 * affordances.
 *
 * @param detail the shared account detail with all-{@code false} capabilities
 * @param canExportStatement whether the caller may export the holder-redacted statement; always
 *     {@code true}
 * @param canSetTarget whether the caller may set or clear the balance target
 * @param canConfigureVisibility whether the caller may manage the account's visibility
 * @param canRequest whether the caller may raise a booking request (REQ-BANK-039)
 * @param canConfigureApprovalLimits whether the caller may set or clear approval limits
 *     (REQ-BANK-041)
 * @param applicableLimit the caller's approval limit for this account, or {@code null} when none
 *     applies
 * @param approvalExempt {@code true} iff the caller is the responsible holder and needs no
 *     approval; a bank employee still confirms the request
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
