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
import de.greluc.krt.profit.basetool.backend.model.Department;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * One bank dashboard KPI card (REQ-BANK-016): balance, 30-day delta, sparkline series and the
 * owning Bereich used for grouping.
 *
 * @param id the account's id
 * @param accountNo the account's display number
 * @param name the account's display name
 * @param type the account type
 * @param status lifecycle state; closed accounts render dimmed
 * @param balance current compute-on-read balance
 * @param delta30d net change over the last 30 days (signed)
 * @param sparkline end-of-day balances of the last 30 days, oldest first, last entry = current
 *     balance
 * @param bereichId the owning Bereich's id, or {@code null}
 * @param bereichName the owning Bereich's display name, or {@code null}
 * @param bereichDepartment the owning Bereich's department (drives the group tint), or {@code null}
 */
public record BankDashboardAccountDto(
    UUID id,
    String accountNo,
    String name,
    BankAccountType type,
    BankAccountStatus status,
    BigDecimal balance,
    BigDecimal delta30d,
    List<BigDecimal> sparkline,
    @Nullable UUID bereichId,
    @Nullable String bereichName,
    @Nullable Department bereichDepartment) {}
