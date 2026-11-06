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

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Write DTO for creating a personal inventory entry. The location display name is NOT accepted from
 * the client; the server takes it from the referenced {@code location} row and persists a snapshot.
 *
 * @param name the item name
 * @param note an optional free-text note
 * @param locationId the {@code location.id} of a place the owner may pick (REQ-P4K-039)
 * @param quantity the count, at least one
 */
public record PersonalInventoryItemCreateRequest(
    @NotBlank @Size(max = 120) String name,
    @Size(max = 2000) String note,
    @NotNull UUID locationId,
    @NotNull @Min(1) Integer quantity) {}
