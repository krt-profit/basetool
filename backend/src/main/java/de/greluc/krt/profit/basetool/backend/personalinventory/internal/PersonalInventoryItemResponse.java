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

package de.greluc.krt.profit.basetool.backend.personalinventory.internal;

import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Read DTO for a personal inventory entry. The owner identifier ({@code ownerUserId}) is
 * intentionally NOT exposed: clients only ever see their own items via the user-scoped endpoints,
 * and the admin endpoints already provide the owner sub via the URL path.
 *
 * @param id the item id
 * @param name the item name
 * @param note the free-text note, or {@code null}
 * @param locationId the {@code location.id}, or {@code null} when the place no longer resolves
 * @param locationType the kind of place, or {@code null} when the place no longer resolves
 * @param locationName the location name snapshot taken at the last location change
 * @param quantity the count
 * @param version the optimistic-lock version a write echoes back
 * @param createdAt when the item was created
 * @param updatedAt when the item was last changed
 */
public record PersonalInventoryItemResponse(
    UUID id,
    String name,
    @Nullable String note,
    @Nullable UUID locationId,
    @Nullable PersonalInventoryLocationType locationType,
    String locationName,
    Integer quantity,
    Long version,
    Instant createdAt,
    Instant updatedAt) {}
