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

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.UUID;

/**
 * Frontend mirror of the backend's {@code MissionObjectiveDto} — one classified goal (Ziel) of a
 * mission.
 *
 * @param id the goal id
 * @param title the required goal text (the bullet label)
 * @param kind the classification (primary / secondary / non-goal) driving the grouped display
 * @param orderIndex the zero-based position within the mission's goal list
 */
@DtoMirror
public record MissionObjectiveDto(
    UUID id, String title, MissionObjectiveKind kind, int orderIndex) {}
