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

package de.greluc.krt.profit.basetool.backend.refinery.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.RefiningMethodDto;
import de.greluc.krt.profit.basetool.backend.model.dto.LocationDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionReferenceDto;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryGoodDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Request body of a refinery-order create.
 *
 * <p>It carries no id, owner, profit, owning squadron, version or status: the owner comes from the
 * token or the path, the profit is computed, and the order always starts {@code OPEN}. {@code
 * owningOrgUnitId} is the picker output, resolved by {@code
 * OwnerScopeService.resolveOrgUnitForPickerOutputNullable}; an inadmissible pick is rejected with
 * 400 (REQ-ORG-016).
 *
 * @param location the refinery location
 * @param mission the linked mission, or {@code null}
 * @param startedAt the run's start, or {@code null} for now
 * @param durationMinutes the run's duration in minutes
 * @param expenses the refining expenses
 * @param otherExpenses further expenses
 * @param oreSales the ore sales
 * @param refiningMethod the refining method, or {@code null}
 * @param goods the goods put into the run
 * @param owningOrgUnitId the org unit to stamp, or {@code null} for the owner's single membership
 */
public record RefineryOrderCreateRequest(
    @NotNull LocationDto location,
    MissionReferenceDto mission,
    Instant startedAt,
    @PositiveOrZero Long durationMinutes,
    @PositiveOrZero @DecimalMax("1000000000.0") Double expenses,
    @PositiveOrZero @DecimalMax("1000000000.0") Double otherExpenses,
    @PositiveOrZero @DecimalMax("1000000000.0") Double oreSales,
    RefiningMethodDto refiningMethod,
    @NotEmpty List<@Valid RefineryGoodDto> goods,
    UUID owningOrgUnitId) {}
