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

package de.greluc.krt.profit.basetool.backend.promotion.internal;

import de.greluc.krt.profit.basetool.backend.validation.DtoConstraints;
import de.greluc.krt.profit.basetool.backend.validation.OnUpdate;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Unified create/update write request for a {@code RankRequirement}; {@code version} is required
 * only on update via the {@code OnUpdate} group.
 *
 * @param fromRank ordinal of the current rank (required, non-negative)
 * @param toRank ordinal of the rank being promoted to (required, non-negative)
 * @param topicId identifier of the scoping promotion topic, or {@code null}
 * @param categoryId identifier of the scoping promotion category, or {@code null}
 * @param minimumLevel the minimum promotion level a member must reach (required)
 * @param requiredCount the number of qualifying categories required (required, at least one)
 * @param description free-text description, or {@code null}
 * @param version optimistic-lock version; required on update, ignored on create
 */
public record RankRequirementWriteRequest(
    @NotNull @Min(0) Integer fromRank,
    @NotNull @Min(0) Integer toRank,
    UUID topicId,
    UUID categoryId,
    @NotNull PromotionLevel minimumLevel,
    @NotNull @Min(1) Integer requiredCount,
    @Size(max = DtoConstraints.MAX_DESCRIPTION) String description,
    @NotNull(groups = OnUpdate.class) Long version) {}
