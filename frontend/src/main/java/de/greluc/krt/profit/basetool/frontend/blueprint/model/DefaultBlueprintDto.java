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

package de.greluc.krt.profit.basetool.frontend.blueprint.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.time.Instant;
import java.util.UUID;

/**
 * Read DTO mirroring the backend {@code DefaultBlueprintResponse} (REQ-INV-017): one entry of the
 * admin-managed default-blueprint set, rendered on the admin default-blueprints page.
 *
 * @param id entry id (used by the remove action)
 * @param productKey normalized product key
 * @param productName canonical display name
 * @param outputItemId resolved output {@code game_item} id, or {@code null}
 * @param scwikiKey representative SC Wiki recipe key, or {@code null}
 * @param createdBy admin {@code sub} that added the entry, or {@code "system"} for the seed
 * @param createdAt row creation timestamp
 * @param version optimistic-lock version
 */
@DtoMirror
public record DefaultBlueprintDto(
    UUID id,
    String productKey,
    String productName,
    UUID outputItemId,
    String scwikiKey,
    String createdBy,
    Instant createdAt,
    Long version) {}
