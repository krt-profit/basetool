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

import java.time.Instant;
import java.util.UUID;

/**
 * Boundary DTO for one of the caller's owned blueprints (#327). The internal {@code ownerUserId} is
 * intentionally never exposed.
 *
 * @param id entry primary key
 * @param productKey normalized product identity
 * @param productName display name of the owned product
 * @param outputItemId resolved output {@code game_item} id, or {@code null} if unresolved
 * @param acquiredAt optional in-game acquisition time
 * @param note optional free-form note
 * @param removable whether the owner may delete this entry; {@code false} for an auto-granted
 *     default blueprint (REQ-INV-016), which the UI uses to hide the delete control and which the
 *     delete endpoint enforces server-side
 * @param version optimistic-lock version
 * @param createdAt row creation timestamp
 * @param updatedAt row last-update timestamp
 */
public record PersonalBlueprintResponse(
    UUID id,
    String productKey,
    String productName,
    UUID outputItemId,
    Instant acquiredAt,
    String note,
    boolean removable,
    Long version,
    Instant createdAt,
    Instant updatedAt) {}
