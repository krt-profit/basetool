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

package de.greluc.krt.profit.basetool.frontend.model.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Frontend mirror of the backend operation record; component order matches the backend.
 *
 * @param id operation primary key
 * @param name operation name
 * @param description optional free-text description
 * @param status current operation status (string mirror of the backend enum)
 * @param owningSquadron squadron that owns the operation (multi-tenant scope marker)
 * @param version optimistic-lock version
 * @param createdAt creation timestamp (UTC)
 * @param updatedAt last-update timestamp (UTC)
 * @param payoutPreliminary {@code true} when at least one mission lacks {@code actualStartTime} or
 *     {@code actualEndTime}; {@code null} outside the detail endpoint, treated as unknown
 */
public record OperationDto(
    UUID id,
    String name,
    String description,
    @BackendEnumAsString String status,
    SquadronReferenceDto owningSquadron,
    Long version,
    Instant createdAt,
    Instant updatedAt,
    Boolean payoutPreliminary) {}
