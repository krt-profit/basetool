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
 * Write payload for posting a material wanted-listing (Gesuch) to the Materialbörse ("Material
 * suchen", REQ-MARKET-015) — the request-side counterpart to {@link
 * MaterialExchangeReleaseRequest}.
 *
 * <p>Unlike a material release there is no backing Lager row: the requester names the catalogue
 * material directly, the desired quantity in the material's own unit (SCU or Stück), and an
 * optional minimum quality they are looking for. Owner and squadron are stamped from the acting
 * member; there is no ownership or stock check. A member may post several requests for the same
 * material (no de-duplication).
 *
 * @param materialId the catalogue material being requested; must exist.
 * @param minQuality the optional minimum desired quality (0–1000), or {@code null} for no floor.
 * @param requestedAmount the desired quantity in the material's own unit; must be positive.
 * @param remark the free-form Markdown description, at most 20 000 characters (may be blank).
 */
public record MaterialRequestCreateRequest(
    @NotNull UUID materialId,
    @Min(0) @Max(1000) Integer minQuality,
    @NotNull @Positive Double requestedAmount,
    @Size(max = 20000) String remark) {}
