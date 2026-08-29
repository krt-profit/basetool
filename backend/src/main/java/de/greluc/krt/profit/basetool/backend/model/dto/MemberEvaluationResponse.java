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
import java.time.Instant;
import java.util.UUID;

/**
 * Read DTO for {@code MemberEvaluation}. Note: {@code userId} is included for admin views; personal
 * views are already scoped to the caller.
 */
public record MemberEvaluationResponse(
    UUID id,
    Long version,
    UUID userId,
    UUID categoryId,
    String categoryName,
    UUID topicId,
    String topicName,
    PromotionLevel assignedLevel,
    Instant createdAt,
    Instant updatedAt) {}
