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
 * Write payload for posting a craftable-item wanted-listing (Gesuch) to the Materialbörse ("Item
 * suchen", REQ-MARKET-015) — the request-side counterpart to {@link
 * MaterialExchangeItemReleaseRequest}.
 *
 * <p>The {@link #productKey} is the normalized blueprint {@code product_key} chosen from the
 * blueprint-product search; the service validates it against {@code
 * BlueprintProductService.resolveByProductKey(...)}, so only items an active blueprint produces can
 * be requested, and it snapshots the canonical display name from that resolution rather than
 * trusting client text. The requester states the desired whole-piece {@link #quantity} and may add
 * an optional minimum quality (a pure preference — items carry no intrinsic quality,
 * REQ-MARKET-015). Owner and squadron are stamped from the acting member.
 *
 * @param productKey the normalized blueprint product key of the item being requested; must resolve
 *     to an item an active blueprint produces.
 * @param minQuality the optional minimum desired quality (0–1000), or {@code null} for no floor.
 * @param quantity the desired whole-piece quantity; must be at least 1.
 * @param remark the free-form Markdown description, at most 20 000 characters (may be blank).
 */
public record MaterialItemRequestCreateRequest(
    @NotBlank @Size(max = 255) String productKey,
    @Min(0) @Max(1000) Integer minQuality,
    @NotNull @Min(1) @Max(1_000_000) Integer quantity,
    @Size(max = 20000) String remark) {}
