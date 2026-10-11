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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What a mission shows of the operation it belongs to.
 *
 * @param id the operation
 * @param name the operation's name
 * @param description the operation's description, or {@code null}
 * @param status the name of the operation's lifecycle status
 * @param owningOrgUnitId the owning org unit, or {@code null} for an ownerless leadership operation
 * @param owningOrgUnitName the owning org unit's name, or {@code null} without an owner
 * @param owningOrgUnitShorthand the owning org unit's shorthand, or {@code null}
 * @param version the operation's row version
 * @param createdAt the creation timestamp
 * @param updatedAt the last-update timestamp
 */
public record MissionOperationSummary(
    @NotNull UUID id,
    @NotNull String name,
    @Nullable String description,
    @NotNull String status,
    @Nullable UUID owningOrgUnitId,
    @Nullable String owningOrgUnitName,
    @Nullable String owningOrgUnitShorthand,
    @Nullable Long version,
    @Nullable Instant createdAt,
    @Nullable Instant updatedAt) {}
