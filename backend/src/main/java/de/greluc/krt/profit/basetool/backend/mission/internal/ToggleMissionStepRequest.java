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

import jakarta.validation.constraints.NotNull;

/**
 * Request DTO for toggling an Ablauf step's shared {@code done} flag. {@code stepsVersion} is the
 * mission's expected {@code steps_version} section counter — a stale value yields HTTP 409.
 *
 * @param done the new done state to persist
 * @param stepsVersion the expected mission steps-section version (optimistic-lock guard)
 */
public record ToggleMissionStepRequest(@NotNull Boolean done, @NotNull Long stepsVersion) {}
