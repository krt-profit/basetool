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

import de.greluc.krt.profit.basetool.backend.model.BankAccountStatus;
import de.greluc.krt.profit.basetool.backend.model.BankAccountType;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Balance-only view of one bank account on the org-unit bank page (REQ-BANK-021, REQ-BANK-028).
 *
 * <p>Carries no transaction history, holder distribution or audit trail. A special account ({@link
 * BankAccountType#SPECIAL}) has {@code null} org-unit fields and {@code canRequest == false}. Only
 * {@link BankAccountStatus#ACTIVE} accounts are listed.
 *
 * @param accountId the account's id; keys the booking-request form for an own-level account
 * @param accountNo the human-readable {@code KB-<n>} account number
 * @param accountName the account's display name
 * @param status the account status; always {@link BankAccountStatus#ACTIVE} here
 * @param type the account type, used to label a special account
 * @param orgUnitId the owning org unit's id, or {@code null} for a special account
 * @param orgUnitName the owning org unit's name, or {@code null} for a special account
 * @param orgUnitShorthand the owning org unit's shorthand, or {@code null} when absent
 * @param orgUnitKind the owning org unit's kind, or {@code null} for a special account
 * @param balance the current balance in whole aUEC (ADR-0010)
 * @param canRequest {@code true} iff this is the caller's own-level org-unit account, so a booking
 *     request may be made against it
 * @param delta30d the signed net balance change over the last 30 days (REQ-BANK-016)
 * @param sparkline the end-of-day balances of the last 30 days, oldest first, ending with the
 *     current balance
 * @param balanceTarget the balance goal (REQ-BANK-036), or {@code null} when none is set
 * @param canManageSettings {@code true} iff the caller may open the account's settings
 * @param approvalLimit the caller's approval limit for this account (REQ-BANK-041), or {@code null}
 *     when none applies
 * @param approvalExempt {@code true} iff the caller is the responsible holder and bound by no
 *     approval ceiling
 */
public record OrgUnitBankBalanceDto(
    UUID accountId,
    String accountNo,
    String accountName,
    BankAccountStatus status,
    BankAccountType type,
    @Nullable UUID orgUnitId,
    @Nullable String orgUnitName,
    @Nullable String orgUnitShorthand,
    @Nullable OrgUnitKind orgUnitKind,
    BigDecimal balance,
    boolean canRequest,
    BigDecimal delta30d,
    List<BigDecimal> sparkline,
    @Nullable BigDecimal balanceTarget,
    boolean canManageSettings,
    @Nullable BigDecimal approvalLimit,
    boolean approvalExempt) {}
