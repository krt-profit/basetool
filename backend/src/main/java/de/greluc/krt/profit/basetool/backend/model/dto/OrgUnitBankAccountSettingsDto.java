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
 * The current responsibility settings of one bank account, for the holder/OL settings panel on the
 * org-unit bank page (REQ-BANK-035/-036). Returned only to a caller who may manage the account
 * (responsible holder, or — for Sonderkonten — an OL member); the booleans tell the frontend which
 * controls to render.
 *
 * <p>The available role buckets depend on the account's owning org-unit kind:
 *
 * <ul>
 *   <li>Squadron → {@code KOMMANDOLEITER}, {@code STELLV_KOMMANDOLEITER}, {@code ENSIGN}
 *       (membership roles);
 *   <li>Spezialkommando → none (only all-members + individual users);
 *   <li>Bereich → {@code BEREICHSKOORDINATOR}, {@code BEREICHSOPERATOR} (membership roles);
 *   <li>Sonderkonto ({@link BankAccountType#SPECIAL}) → global roles (e.g. {@code OFFICER}) with
 *       {@link #roleBucketsGlobal()} {@code true};
 *   <li>{@code CARTEL} / {@code CARTEL_BANK} → visibility is not configurable ({@link
 *       #visibilityConfigurable()} {@code false}).
 * </ul>
 *
 * @param accountId the account
 * @param accountNo the human-readable {@code KB-<n>} number
 * @param accountName the display name
 * @param type the account type
 * @param orgUnitKind the owning org unit's kind, or {@code null} for {@code SPECIAL}/{@code
 *     CARTEL_BANK}
 * @param balanceTarget the current balance target, or {@code null} if none is set
 * @param version the account's optimistic-locking version, echoed when setting/clearing the target
 * @param canSetTarget whether the caller may set/clear the balance target
 * @param canConfigureVisibility whether the caller may add/remove visibility grants
 * @param visibilityConfigurable whether this account type supports configurable visibility at all
 *     ({@code false} for {@code CARTEL}/{@code CARTEL_BANK})
 * @param allMembersSupported whether the all-members toggle applies to this account
 * @param areaMembersSupported whether the "Mitglieder des Bereichs" cascade toggle applies — only
 *     for {@code AREA} (Bereichskonto) accounts (REQ-BANK-048)
 * @param roleBucketsGlobal {@code true} when {@link #availableRoleCodes()} are global role codes
 *     (Sonderkonto), {@code false} when they are {@code MembershipRole} names
 * @param availableRoleCodes the role buckets the caller may toggle for this account, in display
 *     order
 * @param grantedRoleCodes the role buckets currently granted
 * @param allMembersGranted whether the all-members grant is currently set
 * @param areaMembersGranted whether the "Mitglieder des Bereichs" cascade grant is currently set
 *     (REQ-BANK-048)
 * @param grantedUsers the individually granted users, with resolved display names
 * @param canConfigureApprovalLimits whether the caller may set/clear approval limits (REQ-BANK-041
 *     — responsible holder, bank management or admin)
 * @param approvalLimits the account's per-tier approval limits with the org-unit edit affordance
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
