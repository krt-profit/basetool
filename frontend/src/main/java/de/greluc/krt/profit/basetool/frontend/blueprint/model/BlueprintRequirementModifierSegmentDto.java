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

package de.greluc.krt.profit.basetool.frontend.blueprint.model;

import de.greluc.krt.profit.basetool.frontend.model.DtoMirror;

/**
 * Frontend mirror of the backend blueprint modifier-segment DTO (one linear step of a stepped /
 * piecewise-linear stat curve, used by the admin blueprint slider to compute non-linear values).
 */
@DtoMirror
public record BlueprintRequirementModifierSegmentDto(
    Double qualityMin, Double qualityMax, Double modifierAtStart, Double modifierAtEnd) {}
