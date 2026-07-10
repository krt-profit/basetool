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

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Data transfer record carrying Refinery Order payload.
 *
 * <p>The trailing {@code owningOrgUnitId} field is the R5.d picker output: when present on create
 * (POST), the service stamps the new refinery order onto the picked org unit instead of the order
 * owner's home Staffel. {@code null} preserves the legacy stamping path. Validation against the
 * order owner's memberships happens at the service layer via {@code
 * OwnerScopeService.resolveSquadronForPickerOutput}.
 */
public record RefineryOrderDto(
    UUID id,
    UserReferenceDto owner,
    @NotNull LocationDto location,
    MissionReferenceDto mission,
    Instant startedAt,
    @PositiveOrZero Long durationMinutes,
    // Upper cap mirrors the mission-finance amount cap (1e9, audit C-2); audit L-10 closes the
    // missing upper bound so a runaway refinery money value cannot inflate the operation roll-up.
    @PositiveOrZero @DecimalMax("1000000000.0") Double expenses,
    @PositiveOrZero @DecimalMax("1000000000.0") Double otherExpenses,
    @PositiveOrZero @DecimalMax("1000000000.0") Double oreSales,
    Double profit,
    RefiningMethodDto refiningMethod,
    String status,
    // @Valid on the element type cascades the @NotNull/@Min(1) constraints on each RefineryGoodDto
    // into the list elements (audit M-4 sweep): without it a good with inputQuantity <= 0 or a null
    // material would bypass bean validation and reach the service.
    @NotEmpty List<@Valid RefineryGoodDto> goods,
    SquadronReferenceDto owningSquadron,
    Long version,
    UUID owningOrgUnitId) {}
