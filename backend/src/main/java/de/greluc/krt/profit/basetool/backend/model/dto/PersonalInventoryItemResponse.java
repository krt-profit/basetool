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

import de.greluc.krt.profit.basetool.backend.model.PersonalInventoryLocationType;
import java.time.Instant;
import java.util.UUID;

/**
 * Read DTO for a personal inventory entry. The owner identifier ({@code ownerUserId}) is
 * intentionally NOT exposed: clients only ever see their own items via the user-scoped endpoints,
 * and the admin endpoints already provide the owner sub via the URL path.
 */
public record PersonalInventoryItemResponse(
    UUID id,
    String name,
    String note,
    Integer locationUexId,
    PersonalInventoryLocationType locationType,
    String locationName,
    Integer quantity,
    Long version,
    Instant createdAt,
    Instant updatedAt) {}
