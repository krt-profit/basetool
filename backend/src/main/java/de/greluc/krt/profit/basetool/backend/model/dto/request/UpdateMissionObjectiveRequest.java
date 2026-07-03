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

package de.greluc.krt.profit.basetool.backend.model.dto.request;

import de.greluc.krt.profit.basetool.backend.model.MissionObjectiveKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for editing a mission goal's text and/or classification. {@code objectivesVersion} is
 * the mission's expected {@code objectives_version} section counter — a stale value yields HTTP
 * 409.
 *
 * @param title the required goal text (≤500 chars)
 * @param kind the classification (primary / secondary / non-goal)
 * @param objectivesVersion the expected mission goals-section version (optimistic-lock guard)
 */
public record UpdateMissionObjectiveRequest(
    @NotBlank @Size(max = 500) String title,
    @NotNull MissionObjectiveKind kind,
    @NotNull Long objectivesVersion) {}
