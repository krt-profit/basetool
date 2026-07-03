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

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jetbrains.annotations.Nullable;

/**
 * Request DTO for adding an Ablauf step to a mission. The new step is appended at the end of the
 * timeline (next {@code orderIndex}). {@code stepsVersion} is the mission's expected {@code
 * steps_version} section counter — a stale value yields HTTP 409.
 *
 * @param title the required step title (≤500 chars)
 * @param meta the optional free-text "Zeit / Ort" hint (≤200 chars)
 * @param stepsVersion the expected mission steps-section version (optimistic-lock guard)
 */
public record AddMissionStepRequest(
    @NotBlank @Size(max = 500) String title,
    @Nullable @Size(max = 200) String meta,
    @NotNull Long stepsVersion) {}
