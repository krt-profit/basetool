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
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Request to add a mission participant, either a registered member by {@code userId} or an external
 * person without an account by {@code guestName} (ADR-0159). Free-text fields are size-capped.
 *
 * <p>{@code orgUnitIds} is honoured only for an external entry and taken as submitted; for a
 * registered member the affiliations are derived server-side. A {@code null} {@code
 * payoutPreference} falls back to the user's profile default, else {@code PAYOUT}.
 */
public record AddExternalParticipantRequest(
    UUID userId,
    @Size(max = 100) String guestName,
    UUID desiredJobTypeId,
    @Size(max = 1000) String comment,
    List<UUID> orgUnitIds,
    PayoutPreference payoutPreference) {}
