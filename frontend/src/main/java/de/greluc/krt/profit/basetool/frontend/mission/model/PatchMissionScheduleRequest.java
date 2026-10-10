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

package de.greluc.krt.profit.basetool.frontend.mission.model;

import java.time.Instant;

/**
 * Write payload replacing a mission's schedule section; a {@code null} instant clears that time.
 *
 * @param meetingTime the meeting time, or {@code null}
 * @param plannedStartTime the planned start, or {@code null}
 * @param plannedEndTime the planned end, or {@code null}
 * @param actualStartTime the actual start, or {@code null}
 * @param actualEndTime the actual end, or {@code null}
 * @param version the schedule version the client read
 */
public record PatchMissionScheduleRequest(
    Instant meetingTime,
    Instant plannedStartTime,
    Instant plannedEndTime,
    Instant actualStartTime,
    Instant actualEndTime,
    Long version) {}
