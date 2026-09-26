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
import java.util.UUID;

/**
 * Payload for posting a material wanted-listing (Gesuch) to the Materialbörse (REQ-MARKET-015).
 *
 * <p>Owner and squadron are set server-side; several requests for the same material are allowed.
 *
 * @param materialId the catalogue material being requested; must exist.
 * @param minQuality the optional minimum desired quality (0–1000), or {@code null} for no floor.
 * @param requestedAmount the desired quantity in the material's own unit; must be positive.
 * @param remark the Markdown description, at most 20 000 characters (may be blank).
 */
public record MaterialRequestCreateRequest(
    @NotNull UUID materialId,
    @Min(0) @Max(1000) Integer minQuality,
    @NotNull @Positive Double requestedAmount,
    @Size(max = 20000) String remark) {}
