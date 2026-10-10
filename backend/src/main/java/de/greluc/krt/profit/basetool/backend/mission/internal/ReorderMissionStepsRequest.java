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
 * Request DTO for reordering a mission's Ablauf steps. {@code stepIds} is the complete set of the
 * mission's step ids in the desired new order; the service reassigns {@code orderIndex} 0..n-1 in
 * that order. {@code stepsVersion} is the mission's expected {@code steps_version} section counter
 * — a stale value yields HTTP 409.
 *
 * @param stepIds the mission's step ids in the desired order (must be the complete set)
 * @param stepsVersion the expected mission steps-section version (optimistic-lock guard)
 */
public record ReorderMissionStepsRequest(
    @NotNull @NotEmpty List<UUID> stepIds, @NotNull Long stepsVersion) {}
