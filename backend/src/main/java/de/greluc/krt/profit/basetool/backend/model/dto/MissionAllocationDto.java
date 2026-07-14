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

package de.greluc.krt.profit.basetool.backend.model.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbound projection of one mission slice of an inventory entry (Variante C, REQ-INV-027): the
 * earmarked mission plus the {@code amount} of the entry's stock allocated to it. Rendered as a
 * blue chip with its amount; the sum of an entry's mission slices stays ≤ the entry amount,
 * independently of the job-order split.
 *
 * @param missionId the earmarked mission's id
 * @param missionName the mission's name (the chip label)
 * @param missionPlannedStartTime the mission's planned start, for the chip's date suffix, or {@code
 *     null}
 * @param amount the quantity of the entry's stock allocated to this mission (SCU, 3-decimal)
 */
public record MissionAllocationDto(
    UUID missionId, String missionName, Instant missionPlannedStartTime, Double amount) {}
