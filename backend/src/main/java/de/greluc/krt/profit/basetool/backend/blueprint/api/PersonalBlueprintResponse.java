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

package de.greluc.krt.profit.basetool.backend.blueprint.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One of the caller's owned blueprints; the owner id is never exposed.
 *
 * @param id entry primary key
 * @param productKey normalized product identity
 * @param productName display name of the owned product
 * @param outputItemId resolved output {@code game_item} id, or {@code null}
 * @param acquiredAt optional in-game acquisition time
 * @param note optional free-form note
 * @param removable {@code false} for an auto-granted default blueprint (REQ-INV-016)
 * @param version optimistic-lock version
 * @param createdAt row creation timestamp
 * @param updatedAt row last-update timestamp
 * @param source where the entry came from, or {@code null} when that was not recorded (REQ-INV-054)
 * @param sourceClientId the exchange client that added it, or {@code null}
 * @param sourceClientName the registry display name of {@code sourceClientId}, or {@code null} when
 *     there is no client or it is no longer registered; a reader then shows the id
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
    Instant updatedAt,
    BlueprintSource source,
    String sourceClientId,
    String sourceClientName) {}
