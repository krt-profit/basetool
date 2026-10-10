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

import de.greluc.krt.profit.basetool.frontend.kernel.model.BackendEnumAsString;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.List;
import java.util.UUID;

/**
 * Write payload for adding a participant to a mission; neither a user nor a name adds the caller.
 *
 * @param userId the registered member to add, or {@code null}
 * @param guestName the free-text handle to resolve, or {@code null}
 * @param desiredJobTypeId the desired mission job type, or {@code null}
 * @param comment the participant's comment, or {@code null}
 * @param orgUnitIds the org units the participant flies for, or {@code null}
 * @param payoutPreference the payout preference constant, or {@code null}
 */
@DtoMirror
public record AddExternalParticipantRequest(
    UUID userId,
    String guestName,
    UUID desiredJobTypeId,
    String comment,
    List<UUID> orgUnitIds,
    @BackendEnumAsString String payoutPreference) {}
