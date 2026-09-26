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

import de.greluc.krt.profit.basetool.backend.model.BankAccountType;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * The responsibility settings of one bank account for the holder/OL settings panel on the org-unit
 * bank page (REQ-BANK-035/-036). The available role buckets depend on the owning org-unit kind; a
 * Sonderkonto uses global roles, and {@code CARTEL}/{@code CARTEL_BANK} accounts have no
 * configurable visibility.
 *
 * @param accountId the account
 * @param accountNo the human-readable {@code KB-<n>} number
 * @param accountName the display name
 * @param type the account type
 * @param orgUnitKind the owning org unit's kind, or {@code null} for {@code SPECIAL}/{@code
 *     CARTEL_BANK}
 * @param balanceTarget the current balance target, or {@code null} if none is set
 * @param version the account's optimistic-lock version
 * @param canSetTarget whether the caller may set or clear the balance target
 * @param canConfigureVisibility whether the caller may add or remove visibility grants
 * @param visibilityConfigurable whether this account type supports configurable visibility
 * @param allMembersSupported whether the all-members toggle applies
 * @param areaMembersSupported whether the Bereich-members toggle applies; only for {@code AREA}
 *     accounts (REQ-BANK-048)
 * @param roleBucketsGlobal {@code true} when {@link #availableRoleCodes()} are global role codes,
 *     {@code false} for {@code MembershipRole} names
 * @param availableRoleCodes the role buckets the caller may toggle, in display order
 * @param grantedRoleCodes the role buckets currently granted
 * @param allMembersGranted whether the all-members grant is set
 * @param areaMembersGranted whether the Bereich-members grant is set (REQ-BANK-048)
 * @param grantedUsers the individually granted users with display names
 * @param canConfigureApprovalLimits whether the caller may set or clear approval limits
 *     (REQ-BANK-041)
 * @param approvalLimits the per-tier approval limits with the org-unit edit affordance
 */
public record OrgUnitBankAccountSettingsDto(
    UUID accountId,
    String accountNo,
    String accountName,
    BankAccountType type,
    @Nullable OrgUnitKind orgUnitKind,
    @Nullable BigDecimal balanceTarget,
    Long version,
    boolean canSetTarget,
    boolean canConfigureVisibility,
    boolean visibilityConfigurable,
    boolean allMembersSupported,
    boolean areaMembersSupported,
    boolean roleBucketsGlobal,
    List<String> availableRoleCodes,
    List<String> grantedRoleCodes,
    boolean allMembersGranted,
    boolean areaMembersGranted,
    List<OrgUnitBankViewUserDto> grantedUsers,
    boolean canConfigureApprovalLimits,
    BankApprovalLimitsDto approvalLimits) {}
