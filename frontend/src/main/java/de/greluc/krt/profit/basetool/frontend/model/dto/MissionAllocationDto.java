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

package de.greluc.krt.profit.basetool.frontend.model.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Frontend mirror of the backend mission quantity slice of an inventory entry (Variante C,
 * REQ-INV-027): the amount of an entry's stock earmarked to one mission, rendered as a chip with
 * its amount.
 *
 * @param missionId the earmarked mission's id.
 * @param missionName the earmarked mission's name (chip label).
 * @param missionPlannedStartTime the earmarked mission's planned start time, or {@code null}.
 * @param amount the SCU/piece amount of the entry earmarked to this mission.
 */
public record MissionAllocationDto(
    UUID missionId, String missionName, Instant missionPlannedStartTime, Double amount) {}
