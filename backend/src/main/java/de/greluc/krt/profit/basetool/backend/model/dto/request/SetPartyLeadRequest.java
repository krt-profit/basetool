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

package de.greluc.krt.profit.basetool.backend.model.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Request DTO for assigning or clearing a mission's party lead (Partyleiter).
 *
 * <p>A {@code guestName} without {@code userId} is matched case-insensitively against members: a
 * unique match links the member, none stores a guest handle, several return 409. Submitting neither
 * clears the party lead.
 *
 * @param userId explicit registered-user reference, or {@code null}
 * @param guestName free-text party-lead handle, or {@code null}; capped at 100 characters
 * @param version expected {@code partyLeadVersion} section counter of the mission
 */
public record SetPartyLeadRequest(
    UUID userId, @Size(max = 100) String guestName, @NotNull Long version) {}
