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

import de.greluc.krt.profit.basetool.backend.model.PromotionLevel;
import java.util.UUID;

/**
 * Result of evaluating one {@code RankRequirement} against a member's evaluations.
 *
 * <p>A topic-scoped check has {@code categoryId == null}; for a category-scoped check {@link
 * #achievedCount} is 0 or 1.
 *
 * @param requirementId the id of the underlying {@code RankRequirement}
 * @param topicId the topic of the check, when known
 * @param topicName topic name for display
 * @param categoryId the targeted category, or {@code null} for a topic-wide check
 * @param categoryName category name, or {@code null} for a topic-wide check
 * @param minimumLevel the minimum {@link PromotionLevel} demanded
 * @param requiredCount how many categories must reach {@link #minimumLevel}
 * @param achievedCount how many categories currently reach {@link #minimumLevel}
 * @param satisfied {@code true} iff {@link #achievedCount} reaches {@link #requiredCount}
 * @param description the requirement's free-text description
 */
public record PromotionRequirementCheckResponse(
    UUID requirementId,
    UUID topicId,
    String topicName,
    UUID categoryId,
    String categoryName,
    PromotionLevel minimumLevel,
    int requiredCount,
    int achievedCount,
    boolean satisfied,
    String description) {}
