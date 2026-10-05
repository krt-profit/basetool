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
import java.util.List;
import java.util.UUID;

/**
 * Write payload for editing a mission participant.
 *
 * @param desiredMissionJobTypeId the desired mission job type, or {@code null}
 * @param plannedMissionJobTypeId the planned mission job type, or {@code null}
 * @param comment the participant's comment, or {@code null}
 * @param guestName the guest handle, or {@code null}
 * @param startTime the participation start, or {@code null}
 * @param endTime the participation end, or {@code null}
 * @param orgUnitIds the org units the participant flies for, or {@code null}
 * @param payoutPreference the payout preference constant, or {@code null}
 * @param version the participant version the client read
 */
public record UpdateParticipantRequest(
    UUID desiredMissionJobTypeId,
    UUID plannedMissionJobTypeId,
    String comment,
    String guestName,
    Instant startTime,
    Instant endTime,
    List<UUID> orgUnitIds,
    @BackendEnumAsString String payoutPreference,
    Long version) {}
