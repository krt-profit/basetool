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

package de.greluc.krt.profit.basetool.backend.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Tuning of the refinery screenshot import's fuzzy material and method matching, under {@code
 * krt.refinery-import.*}.
 *
 * @param fuzzyAcceptThreshold the minimum {@code BlueprintFuzzyMatcher} score for auto-applying a
 *     material candidate, still flagged {@code LOW_CONFIDENCE_MATERIAL}
 * @param methodFuzzyAcceptThreshold the minimum score for accepting the nearest refining method
 * @param suggestionFloor the minimum score for a candidate to be offered as a suggestion
 * @param suggestionLimit the maximum number of suggestions per unmatched or low-confidence issue
 */
@Validated
@ConfigurationProperties(prefix = "krt.refinery-import")
public record RefineryImportProperties(
    @DefaultValue("0.9") @NotNull @DecimalMin("0.5") @DecimalMax("1.0") Double fuzzyAcceptThreshold,
    @DefaultValue("0.6") @NotNull @DecimalMin("0.5") @DecimalMax("1.0")
        Double methodFuzzyAcceptThreshold,
    @DefaultValue("0.5") @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double suggestionFloor,
    @DefaultValue("5") @NotNull @Min(1) @Max(20) Integer suggestionLimit) {}
