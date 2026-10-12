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

import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.RefiningMethodDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionReferenceDto;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryGoodDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.List;

/**
 * Request body of a refinery-order update.
 *
 * <p>It carries no id, owner, profit or owning squadron: the order comes from the path, its owner
 * and org unit never change, and the profit is computed. A logistician acting for the owner uses
 * the per-target path.
 *
 * @param location the refinery location
 * @param mission the linked mission, or {@code null} to unlink
 * @param startedAt the run's start, or {@code null} for now
 * @param durationMinutes the run's duration in minutes
 * @param expenses the refining expenses
 * @param otherExpenses further expenses
 * @param oreSales the ore sales
 * @param refiningMethod the refining method, or {@code null} to clear it
 * @param status the new status name, or {@code null} to keep the current one
 * @param goods the goods of the run, replacing the stored ones
 * @param version the last-seen version, or {@code null} to skip the optimistic-lock check
 */
public record RefineryOrderUpdateRequest(
    @NotNull LocationDto location,
    MissionReferenceDto mission,
    Instant startedAt,
    @PositiveOrZero Long durationMinutes,
    @PositiveOrZero @DecimalMax("1000000000.0") Double expenses,
    @PositiveOrZero @DecimalMax("1000000000.0") Double otherExpenses,
    @PositiveOrZero @DecimalMax("1000000000.0") Double oreSales,
    RefiningMethodDto refiningMethod,
    String status,
    @NotEmpty List<@Valid RefineryGoodDto> goods,
    Long version) {}
