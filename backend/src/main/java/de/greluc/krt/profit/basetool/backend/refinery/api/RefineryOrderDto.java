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

package de.greluc.krt.profit.basetool.backend.refinery.api;

import de.greluc.krt.profit.basetool.backend.catalogue.api.RefiningMethodDto;
import de.greluc.krt.profit.basetool.backend.model.dto.LocationDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.SquadronReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.UserReferenceDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Refinery order payload.
 *
 * <p>{@code owningOrgUnitId} is the picker output on create, resolved by {@code
 * OwnerScopeService.resolveOrgUnitForPickerOutputNullable} for all four org-unit kinds; an
 * inadmissible pick is rejected with 400 (REQ-ORG-016).
 */
public record RefineryOrderDto(
    @Nullable UUID id,
    @Nullable UserReferenceDto owner,
    @Nullable @NotNull LocationDto location,
    @Nullable MissionReferenceDto mission,
    @Nullable Instant startedAt,
    @PositiveOrZero Long durationMinutes,
    @PositiveOrZero @DecimalMax("1000000000.0") Double expenses,
    @Nullable @PositiveOrZero @DecimalMax("1000000000.0") Double otherExpenses,
    @Nullable @PositiveOrZero @DecimalMax("1000000000.0") Double oreSales,
    @Nullable Double profit,
    @Nullable RefiningMethodDto refiningMethod,
    String status,
    @NotEmpty List<@Valid RefineryGoodDto> goods,
    @Nullable SquadronReferenceDto owningSquadron,
    @Nullable Long version,
    @Nullable UUID owningOrgUnitId) {}
