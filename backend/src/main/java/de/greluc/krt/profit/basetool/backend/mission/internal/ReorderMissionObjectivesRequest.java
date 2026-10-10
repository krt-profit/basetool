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

package de.greluc.krt.profit.basetool.backend.mission.internal;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Request DTO for reordering a mission's goals. {@code objectiveIds} is the complete set of the
 * mission's goal ids in the desired new order; the service reassigns {@code orderIndex} 0..n-1 in
 * that order. {@code objectivesVersion} is the mission's expected {@code objectives_version}
 * section counter — a stale value yields HTTP 409.
 *
 * @param objectiveIds the mission's goal ids in the desired order (must be the complete set)
 * @param objectivesVersion the expected mission goals-section version (optimistic-lock guard)
 */
public record ReorderMissionObjectivesRequest(
    @NotNull @NotEmpty List<UUID> objectiveIds, @NotNull Long objectivesVersion) {}
