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
 * Request DTO for a partial update of the flags section of a mission ({@code isInternal}).
 *
 * <p>The {@code version} field is the dedicated {@code mission.flags_version} section counter — not
 * the global {@code Mission.@Version}. Concurrent edits on the core or schedule section therefore
 * never invalidate a flags patch in flight (and vice versa).
 */
public record PatchMissionFlagsRequest(@NotNull Boolean isInternal, @NotNull Long version) {}
