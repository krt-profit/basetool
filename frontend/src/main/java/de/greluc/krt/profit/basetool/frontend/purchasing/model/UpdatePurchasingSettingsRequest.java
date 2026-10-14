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

package de.greluc.krt.profit.basetool.frontend.purchasing.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.BackendEnumAsString;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code UpdatePurchasingSettingsRequest}; must match it field for
 * field.
 *
 * @param enabled whether the surface is on
 * @param governingAreaId the governing Bereich
 * @param fundingAccountId the funding account
 * @param defaultMonthlyLimit the default monthly limit
 * @param budgetCap the budget cap, or {@code null}
 * @param budgetPeriod the budget period
 * @param settlementDueDays the settlement due days
 * @param allowedQualityTierIds the quality tiers purchasing may buy
 * @param version the echoed version
 */
@DtoMirror
public record UpdatePurchasingSettingsRequest(
    boolean enabled,
    UUID governingAreaId,
    UUID fundingAccountId,
    @NotNull @PositiveOrZero Long defaultMonthlyLimit,
    @PositiveOrZero Long budgetCap,
    @NotNull @BackendEnumAsString String budgetPeriod,
    @NotNull @Min(1) @Max(90) Integer settlementDueDays,
    List<UUID> allowedQualityTierIds,
    @NotNull Long version) {}
