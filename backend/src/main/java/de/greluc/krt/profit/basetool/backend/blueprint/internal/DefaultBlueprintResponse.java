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

package de.greluc.krt.profit.basetool.backend.blueprint.internal;

import java.time.Instant;
import java.util.UUID;

/**
 * Boundary DTO for one entry of the admin-managed default-blueprint set (REQ-INV-017), rendered on
 * the admin default-blueprints page.
 *
 * @param id entry primary key (used by the remove action)
 * @param productKey normalized product identity
 * @param productName display name of the default product
 * @param outputItemId resolved output {@code game_item} id, or {@code null} if unresolved
 * @param scwikiKey representative SC Wiki recipe key captured at add time, or {@code null}
 * @param createdBy admin {@code sub} that added the entry, or {@code "system"} for the seed
 * @param createdAt row creation timestamp
 * @param version optimistic-lock version
 */
public record DefaultBlueprintResponse(
    UUID id,
    String productKey,
    String productName,
    UUID outputItemId,
    String scwikiKey,
    String createdBy,
    Instant createdAt,
    Long version) {}
