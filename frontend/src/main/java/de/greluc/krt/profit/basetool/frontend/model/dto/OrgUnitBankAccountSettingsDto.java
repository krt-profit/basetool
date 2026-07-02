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
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Frontend mirror of an org-unit account's responsibility settings (REQ-BANK-035/-036) rendered in
 * the holder/OL settings modal: the current balance target and visibility grants plus which
 * controls the caller may use. The role/grant codes arrive as plain strings rendered through {@code
 * bank.orgUnit.visibility.role.*} i18n keys.
 *
 * @param accountId the account
 * @param accountNo the display number
 * @param accountName the display name
 * @param type account-type enum name
 * @param orgUnitKind owning org-unit kind enum name, or {@code null}
 * @param balanceTarget the current balance target, or {@code null}
 * @param version the account version, echoed when setting the target
 * @param canSetTarget whether the caller may set/clear the target
 * @param canConfigureVisibility whether the caller may add/remove visibility grants
 * @param visibilityConfigurable whether this account type supports configurable visibility at all
 * @param allMembersSupported whether the all-members toggle applies
 * @param areaMembersSupported whether the "Mitglieder des Bereichs" cascade toggle applies — only
 *     for AREA (Bereichskonto) accounts (REQ-BANK-048)
 * @param roleBucketsGlobal whether {@code availableRoleCodes} are global role codes (Sonderkonto)
 * @param availableRoleCodes the role buckets the caller may toggle, in display order
 * @param grantedRoleCodes the role buckets currently granted
 * @param allMembersGranted whether the all-members grant is set
 * @param areaMembersGranted whether the "Mitglieder des Bereichs" cascade grant is set
 *     (REQ-BANK-048)
 * @param grantedUsers the individually granted users
 * @param canConfigureApprovalLimits whether the caller may set/clear approval limits (REQ-BANK-041)
 * @param approvalLimits the account's per-tier approval limits with the org-unit edit affordance
 */
public record OrgUnitBankAccountSettingsDto(
    UUID accountId,
    String accountNo,
    String accountName,
    @BackendEnumAsString String type,
    @Nullable @BackendEnumAsString String orgUnitKind,
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
