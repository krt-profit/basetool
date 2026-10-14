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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

import de.greluc.krt.profit.basetool.backend.purchasing.api.BudgetPeriod;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PauseReason;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * The purchasing settings as the admin page and the state read show them (REQ-PUR-002).
 *
 * @param enabled whether the purchasing surface is switched on
 * @param governingAreaId the governing Bereich, or {@code null}
 * @param governingAreaName its name, or {@code null}
 * @param fundingAccountId the funding account, or {@code null}
 * @param fundingAccountNo its number, or {@code null}
 * @param fundingAccountName its name, or {@code null}
 * @param defaultMonthlyLimit the monthly limit of a delegate without a personal one
 * @param budgetCap the most that may be requested per period, or {@code null} for no cap
 * @param budgetPeriod the period the cap applies to
 * @param settlementDueDays the days to settle after delivery closed
 * @param allowedQualityTierIds the quality tiers purchasing may buy
 * @param paused whether purchasing is paused for the whole organisation
 * @param pauseReason why, or {@code null} while running
 * @param version the optimistic-locking version to echo
 */
public record PurchasingSettingsDto(
    boolean enabled,
    @Nullable UUID governingAreaId,
    @Nullable String governingAreaName,
    @Nullable UUID fundingAccountId,
    @Nullable String fundingAccountNo,
    @Nullable String fundingAccountName,
    long defaultMonthlyLimit,
    @Nullable Long budgetCap,
    BudgetPeriod budgetPeriod,
    int settlementDueDays,
    List<UUID> allowedQualityTierIds,
    boolean paused,
    @Nullable PauseReason pauseReason,
    Long version) {}
