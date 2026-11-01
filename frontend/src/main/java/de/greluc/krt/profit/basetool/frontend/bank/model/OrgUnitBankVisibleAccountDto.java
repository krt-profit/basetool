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

package de.greluc.krt.profit.basetool.frontend.bank.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.BackendEnumAsString;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Frontend mirror of the backend's collective read of the viewable org-unit bank accounts
 * (REQ-BANK-064): an account's identity, balance, target and 30-day trend, never holders, custody
 * or history.
 *
 * @param accountId the account's id
 * @param accountNo the human-readable {@code KB-<n>} account number
 * @param accountName the account's display name
 * @param type the account type
 * @param orgUnitId the owning org unit's id, or {@code null} for a special account
 * @param orgUnitName the owning org unit's name, or {@code null} for a special account
 * @param orgUnitShorthand the owning org unit's shorthand, or {@code null} when absent
 * @param orgUnitKind the owning org unit's kind, or {@code null} for a special account
 * @param balance the current balance in whole aUEC
 * @param balanceTarget the balance goal (REQ-BANK-036), or {@code null} when none is set
 * @param delta30d the signed net balance change over the last 30 days
 * @param sparkline the end-of-day balances of the last 30 days, oldest first
 */
@DtoMirror
public record OrgUnitBankVisibleAccountDto(
    UUID accountId,
    String accountNo,
    String accountName,
    @BackendEnumAsString String type,
    @Nullable UUID orgUnitId,
    @Nullable String orgUnitName,
    @Nullable String orgUnitShorthand,
    @Nullable @BackendEnumAsString String orgUnitKind,
    BigDecimal balance,
    @Nullable BigDecimal balanceTarget,
    BigDecimal delta30d,
    List<BigDecimal> sparkline) {}
