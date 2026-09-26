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
 * The number of participants registered for one mission, computed in one grouped query per page
 * (REQ-DATA-003).
 *
 * @param missionId the mission the count belongs to
 * @param registered number of participant rows; a mission with none produces no row, so the caller
 *     supplies the zero
 */
public record MissionParticipantCount(UUID missionId, long registered) {}
