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
 * Write payload for editing an existing wanted-listing ("Gesuch bearbeiten", REQ-MARKET-016).
 * Carries the new desired quantity, minimum quality and description. Only the owner may edit; the
 * echoed {@code version} guards against a concurrent edit via the {@code support.OptimisticLock}
 * check (409 on mismatch).
 *
 * <p>The edit is kind-aware: {@link #desiredAmount} carries the new SCU quantity for a material
 * request and the new whole-piece quantity for an item request (the service interprets it by the
 * request's kind, rejecting a non-whole value for an item request).
 *
 * @param desiredAmount the new desired quantity (SCU for a material request, whole pieces for an
 *     item request); must be positive.
 * @param minQuality the new optional minimum desired quality (0–1000), or {@code null} for no
 *     floor.
 * @param remark the new free-form Markdown description, at most 20 000 characters (may be blank).
 * @param version the optimistic-lock version the client last saw.
 */
public record MaterialRequestUpdateRequest(
    @NotNull @Positive Double desiredAmount,
    @Min(0) @Max(1000) Integer minQuality,
    @Size(max = 20000) String remark,
    @NotNull @Min(0) Long version) {}
