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

import java.util.UUID;

/**
 * Body of the backend rank-requirement create and update calls.
 *
 * @param fromRank the rank the step starts at
 * @param toRank the rank the step leads to
 * @param topicId the topic the requirement counts, or {@code null}
 * @param categoryId the category the requirement counts, or {@code null}
 * @param minimumLevel the backend {@code PromotionLevel} name
 * @param requiredCount how many evaluations must reach the level
 * @param description the description, or {@code null}
 * @param version the optimistic-lock version, required on update
 */
public record RankRequirementWriteRequest(
    Integer fromRank,
    Integer toRank,
    UUID topicId,
    UUID categoryId,
    @BackendEnumAsString String minimumLevel,
    Integer requiredCount,
    String description,
    Long version) {}
