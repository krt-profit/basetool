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

package de.greluc.krt.profit.basetool.backend.model.projection;

import java.util.UUID;

/**
 * How many members and guests are registered for one mission.
 *
 * <p>A {@code MissionParticipant} row <em>is</em> the registration — there is no separate
 * accept/decline state — so the row count is the figure the mission list shows as "{n} angemeldet".
 * Produced by one grouped statement over a whole page of missions rather than by touching each
 * mission's lazy {@code participants} collection, which would be a per-mission SELECT
 * (REQ-DATA-003).
 *
 * @param missionId the mission the count belongs to
 * @param registered number of participant rows; a mission with none produces no row at all, so the
 *     caller supplies the zero
 */
public record MissionParticipantCount(UUID missionId, long registered) {}
