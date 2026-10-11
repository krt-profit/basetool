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
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code RefineryOrderCreateRequest}; must match it field for field.
 *
 * <p>{@code owningOrgUnitId} is the owning-org-unit picker output; {@code null} stamps the owner's
 * home Staffel.
 *
 * @param location the refinery location
 * @param mission the linked mission, or {@code null}
 * @param startedAt the run's start
 * @param durationMinutes the run's duration in minutes
 * @param expenses the refining expenses
 * @param otherExpenses further expenses
 * @param oreSales the ore sales
 * @param refiningMethod the refining method, or {@code null}
 * @param goods the goods put into the run
 * @param owningOrgUnitId the org unit to stamp, or {@code null}
 */
@DtoMirror
public record RefineryOrderCreateRequest(
    LocationDto location,
    MissionReferenceDto mission,
    Instant startedAt,
    Long durationMinutes,
    Double expenses,
    Double otherExpenses,
    Double oreSales,
    RefiningMethodDto refiningMethod,
    List<RefineryGoodDto> goods,
    UUID owningOrgUnitId) {}
