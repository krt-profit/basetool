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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Unified create/update write request for a {@code PromotionCategory}; {@code version} is required
 * only on update via the {@code OnUpdate} group.
 *
 * @param topicId identifier of the parent promotion topic (required)
 * @param name the category name (required, non-blank)
 * @param description free-text description, or {@code null}
 * @param sortOrder the display sort order (required)
 * @param version optimistic-lock version; required on update, ignored on create
 */
public record PromotionCategoryWriteRequest(
    @NotNull UUID topicId,
    @NotBlank @Size(max = DtoConstraints.MAX_SHORT_NAME) String name,
    @Size(max = DtoConstraints.MAX_DESCRIPTION) String description,
    @NotNull Integer sortOrder,
    @NotNull(groups = OnUpdate.class) Long version) {}
