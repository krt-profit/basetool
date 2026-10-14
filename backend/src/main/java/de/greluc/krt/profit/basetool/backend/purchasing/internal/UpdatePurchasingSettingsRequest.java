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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Stores the purchasing settings (REQ-PUR-002). PUT semantics: an omitted optional field clears it.
 *
 * @param enabled whether the purchasing surface is on
 * @param governingAreaIds the governing Bereiche; empty leaves only admins and the
 *     Organisationsleitung governing
 * @param fundingAccountId an active {@code CARTEL} or {@code SPECIAL} account, or {@code null}
 * @param defaultMonthlyLimit the monthly limit of a delegate without a personal one
 * @param budgetCap the most that may be requested per period, or {@code null} for no cap
 * @param budgetPeriod the period the cap applies to
 * @param settlementDueDays the days to settle after delivery closed, 1 to 90
 * @param allowedQualityTierIds the quality tiers purchasing may buy; empty allows none
 * @param version the echoed optimistic-locking version
 */
public record UpdatePurchasingSettingsRequest(
    boolean enabled,
    @Nullable Set<UUID> governingAreaIds,
    @Nullable UUID fundingAccountId,
    @NotNull @PositiveOrZero Long defaultMonthlyLimit,
    @Nullable @PositiveOrZero Long budgetCap,
    @NotNull BudgetPeriod budgetPeriod,
    @NotNull @Min(1) @Max(90) Integer settlementDueDays,
    @Nullable Set<UUID> allowedQualityTierIds,
    @NotNull Long version) {}
