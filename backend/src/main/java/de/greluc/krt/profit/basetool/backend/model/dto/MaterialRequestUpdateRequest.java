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

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Owner-only payload for editing a wanted-listing (REQ-MARKET-016); a stale {@code version} yields
 * 409.
 *
 * @param desiredAmount the new desired quantity, SCU for a material request or whole pieces for an
 *     item request; must be positive.
 * @param minQuality the new optional minimum desired quality (0–1000), or {@code null} for no
 *     floor.
 * @param remark the new Markdown description, at most 20 000 characters (may be blank).
 * @param version the optimistic-lock version the client last saw.
 */
public record MaterialRequestUpdateRequest(
    @NotNull @Positive Double desiredAmount,
    @Min(0) @Max(1000) Integer minQuality,
    @Size(max = 20000) String remark,
    @NotNull @Min(0) Long version) {}
