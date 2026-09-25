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

package de.greluc.krt.profit.basetool.backend.dto.scwiki;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One linear segment of a piecewise-linear {@link ScWikiBlueprintModifierDto} (SC Wiki {@code
 * blueprint_modifier.value_segments[]}): within {@link #qualityMin}..{@link #qualityMax} the
 * multiplier moves linearly from {@link #modifierAtStart} to {@link #modifierAtEnd}.
 *
 * @param qualityMin the segment's start ingredient quality, or {@code null}
 * @param qualityMax the segment's end ingredient quality, or {@code null}
 * @param modifierAtStart the stat multiplier at {@link #qualityMin}, or {@code null}
 * @param modifierAtEnd the stat multiplier at {@link #qualityMax}, or {@code null}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScWikiBlueprintModifierSegmentDto(
    @JsonProperty("quality_min") Double qualityMin,
    @JsonProperty("quality_max") Double qualityMax,
    @JsonProperty("modifier_at_start") Double modifierAtStart,
    @JsonProperty("modifier_at_end") Double modifierAtEnd) {}
