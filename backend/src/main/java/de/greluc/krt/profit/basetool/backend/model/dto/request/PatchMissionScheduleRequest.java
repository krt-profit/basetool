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

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import org.jetbrains.annotations.Nullable;

/**
 * Request DTO for a partial update of a mission's schedule section; all timestamps are UTC.
 *
 * <p>{@code version} is the {@code mission.schedule_version} section counter, so core and flags
 * edits do not conflict with it.
 */
public record PatchMissionScheduleRequest(
    @Nullable Instant meetingTime,
    @Nullable Instant plannedStartTime,
    @Nullable Instant plannedEndTime,
    @Nullable Instant actualStartTime,
    @Nullable Instant actualEndTime,
    @NotNull Long version) {}
