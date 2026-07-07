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

import java.util.Set;
import java.util.UUID;

/**
 * Data transfer record carrying Mission Crew payload. {@code version} is the crew's
 * {@code @Version}, surfaced so the crew edit form can echo it back on the next save and the
 * optimistic-lock check can reject a stale job-type overwrite (#1131).
 */
public record MissionCrewDto(
    UUID id, UUID participantId, String participantName, Long version, Set<JobTypeDto> jobTypes) {}
