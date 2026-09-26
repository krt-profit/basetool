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
 * Frontend mirror of the backend's org-unit bank balance payload (REQ-BANK-021, REQ-BANK-028): the
 * account's identity and balance, never its history, holders or audit.
 *
 * <p>Only active accounts are delivered. A special account carries {@code null} org-unit fields and
 * {@code canRequest = false}.
 *
 * @param accountId the account's id
 * @param accountNo the server-generated display number ({@code KB-0042})
 * @param accountName the account's display name
 * @param status lifecycle enum name (always {@code ACTIVE} here)
 * @param type account-type enum name ({@code ORG_UNIT} / {@code AREA} / {@code CARTEL} / {@code
 *     SPECIAL}); labels a special account that has no org-unit identity
 * @param orgUnitId the owning org unit's id, or {@code null} for a special account
 * @param orgUnitName the owning org unit's long-form name, or {@code null} for a special account
 * @param orgUnitShorthand the owning org unit's shorthand, or {@code null}
 * @param orgUnitKind kind enum name ({@code SQUADRON} / {@code SPECIAL_COMMAND} / {@code BEREICH} /
 *     {@code ORGANISATIONSLEITUNG}), or {@code null} for a special account
 * @param balance the current balance (signed whole aUEC)
 * @param canRequest {@code true} iff this is the caller's own-level org-unit account; {@code false}
 *     for a view-only subordinate or special account
 * @param delta30d the net balance change over the last 30 days (signed)
 * @param sparkline the end-of-day balances of the last 30 days, oldest first, the last entry being
 *     the current balance
 * @param balanceTarget the balance goal (REQ-BANK-036), or {@code null} when none is set
 * @param canManageSettings {@code true} iff the caller may open the account's settings
 * @param approvalLimit the caller's approval limit for this account (REQ-BANK-041), or {@code null}
 *     when approval is always required unless {@code approvalExempt} is set
 * @param approvalExempt {@code true} iff the caller is the account's responsible holder and bound
 *     by no approval ceiling
 */
public record OrgUnitBankBalanceDto(
    UUID accountId,
    String accountNo,
    String accountName,
    @BackendEnumAsString String status,
    @BackendEnumAsString String type,
    @Nullable UUID orgUnitId,
    @Nullable String orgUnitName,
    @Nullable String orgUnitShorthand,
    @Nullable @BackendEnumAsString String orgUnitKind,
    BigDecimal balance,
    boolean canRequest,
    BigDecimal delta30d,
    List<BigDecimal> sparkline,
    @Nullable BigDecimal balanceTarget,
    boolean canManageSettings,
    @Nullable BigDecimal approvalLimit,
    boolean approvalExempt) {}
