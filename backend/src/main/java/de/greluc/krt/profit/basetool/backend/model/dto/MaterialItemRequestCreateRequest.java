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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Payload for posting a craftable-item wanted-listing (Gesuch) to the Materialbörse
 * (REQ-MARKET-015).
 *
 * <p>The {@link #productKey} must resolve to an item an active blueprint produces; the display
 * name, owner and squadron are set server-side.
 *
 * @param productKey the normalized blueprint product key of the requested item.
 * @param minQuality the optional minimum desired quality (0–1000), or {@code null} for no floor.
 * @param quantity the desired whole-piece quantity; at least 1.
 * @param remark the Markdown description, at most 20 000 characters (may be blank).
 */
public record MaterialItemRequestCreateRequest(
    @NotBlank @Size(max = 255) String productKey,
    @Min(0) @Max(1000) Integer minQuality,
    @NotNull @Min(1) @Max(1_000_000) Integer quantity,
    @Size(max = 20000) String remark) {}
