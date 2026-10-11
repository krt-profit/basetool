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

package de.greluc.krt.profit.basetool.frontend.refinery.model;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.LocationDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.RefiningMethodDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import de.greluc.krt.profit.basetool.frontend.kernel.model.MissionReferenceDto;
import java.time.Instant;
import java.util.List;

/**
 * Frontend mirror of the backend {@code RefineryOrderUpdateRequest}; must match it field for field.
 *
 * @param location the refinery location
 * @param mission the linked mission, or {@code null} to unlink
 * @param startedAt the run's start
 * @param durationMinutes the run's duration in minutes
 * @param expenses the refining expenses
 * @param otherExpenses further expenses
 * @param oreSales the ore sales
 * @param refiningMethod the refining method, or {@code null} to clear it
 * @param status the new status, or {@code null} to keep the current one
 * @param goods the goods of the run, replacing the stored ones
 * @param version the last-seen version
 */
@DtoMirror
public record RefineryOrderUpdateRequest(
    LocationDto location,
    MissionReferenceDto mission,
    Instant startedAt,
    Long durationMinutes,
    Double expenses,
    Double otherExpenses,
    Double oreSales,
    RefiningMethodDto refiningMethod,
    RefineryOrderStatus status,
    List<RefineryGoodDto> goods,
    Long version) {}
