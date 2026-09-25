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

import de.greluc.krt.profit.basetool.backend.model.PayoutPreference;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Data transfer record carrying a mission participant. {@code orgUnits} lists the participant's
 * affiliations (zero or more Staffeln and Spezialkommandos). A row without a {@code user} is an
 * external participant recorded by the mission leadership (ADR-0159).
 */
public record MissionParticipantDto(
    UUID id,
    UserDto user,
    String guestName,
    List<OrgUnitReferenceDto> orgUnits,
    JobTypeDto desiredMissionJobType,
    JobTypeDto plannedMissionJobType,
    String comment,
    Instant startTime,
    Instant endTime,
    PayoutPreference payoutPreference,
    Long version) {}
