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

package de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The crafted-item stat multiplier endpoints of a {@link ScWikiBlueprintModifierDto} (SC Wiki
 * {@code blueprint_modifier_range}). The value applied to the output stat interpolates from {@link
 * #atMinQuality} (at the bottom of the modifier's quality band) to {@link #atMaxQuality} (at the
 * top); e.g. {@code 0.95 .. 1.05} means the stat ranges from −5% to +5% depending on the consumed
 * ingredient's quality.
 *
 * @param atMinQuality multiplier applied at the minimum ingredient quality, or {@code null}
 * @param atMaxQuality multiplier applied at the maximum ingredient quality, or {@code null}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScWikiBlueprintModifierRangeDto(
    @JsonProperty("at_min_quality") Double atMinQuality,
    @JsonProperty("at_max_quality") Double atMaxQuality) {}
