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

import de.greluc.krt.profit.basetool.frontend.kernel.model.PayoutPreference;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Form-binding object for Participant input. {@code orgUnitIds} carries the multi-select org-unit
 * picker used only for guest entries; for a registered participant the affiliations are derived
 * server-side and the list is left empty.
 */
public record ParticipantForm(
    UUID userId,
    @Size(max = 255) String guestName,
    UUID desiredJobTypeId,
    UUID plannedMissionJobTypeId,
    @Size(max = 1000) String comment,
    List<UUID> orgUnitIds,
    String startTime,
    String endTime,
    PayoutPreference payoutPreference,
    Long version) {}
