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

package de.greluc.krt.profit.basetool.frontend.model.dto;

import java.util.List;

/**
 * Frontend mirror of the backend blueprint requirement-modifier DTO: one slot's stat contribution
 * over a quality band, with ordered {@code segments} for stepped stats.
 *
 * <p>The slider spans {@code effectiveQualityMin..effectiveQualityMax}, which covers all segments.
 */
public record BlueprintRequirementModifierDto(
    String propertyKey,
    String label,
    String betterWhen,
    Double qualityMin,
    Double qualityMax,
    Double modifierAtMinQuality,
    Double modifierAtMaxQuality,
    String valueRangeType,
    List<BlueprintRequirementModifierSegmentDto> segments,
    Double effectiveQualityMin,
    Double effectiveQualityMax) {}
