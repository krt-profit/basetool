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

import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Request body to assign or change a squadron leadership rank of an existing Staffel member
 * (REQ-ROLE-003); squadron and user come from the path. A non-squadron rank is rejected with 400.
 *
 * <p>{@code kommandoGroupId} is required for {@link MembershipRole#KOMMANDOLEITER} / {@link
 * MembershipRole#STELLV_KOMMANDOLEITER}, optional for {@link MembershipRole#ENSIGN} and must be
 * {@code null} for {@link MembershipRole#STAFFELLEITER}.
 *
 * @param role the squadron rank to set; required
 * @param kommandoGroupId the Kommandogruppe of the same squadron to bind, or {@code null}
 * @param version the optimistic-lock version of the member's row last read; required
 */
public record AssignSquadronRankRequest(
    @NotNull MembershipRole role, UUID kommandoGroupId, @NotNull Long version) {}
