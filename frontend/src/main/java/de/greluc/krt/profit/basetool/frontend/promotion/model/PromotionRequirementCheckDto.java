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

package de.greluc.krt.profit.basetool.frontend.promotion.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.UUID;

/**
 * Frontend mirror of the backend's {@code PromotionRequirementCheckResponse}. {@code minimumLevel}
 * is kept as a {@link String} – the page templates only need to render its name and never compare
 * grades client-side.
 *
 * @param requirementId the persistent id of the underlying rank requirement
 * @param topicId the topic this check belongs to, {@code null} for global rules
 * @param topicName display name of {@code topicId}
 * @param categoryId the specific category targeted, or {@code null} for topic-wide rules
 * @param categoryName display name of {@code categoryId}
 * @param minimumLevel the minimum level (LEVEL_A/LEVEL_B/LEVEL_C) the rule demands
 * @param requiredCount how many categories must reach the minimum (1 for category-wide)
 * @param achievedCount how many currently reach the minimum
 * @param satisfied {@code true} iff {@code achievedCount >= requiredCount}
 * @param description free-text description rendered next to the rule
 */
@DtoMirror
public record PromotionRequirementCheckDto(
    UUID requirementId,
    UUID topicId,
    String topicName,
    UUID categoryId,
    String categoryName,
    String minimumLevel,
    int requiredCount,
    int achievedCount,
    boolean satisfied,
    String description) {}
