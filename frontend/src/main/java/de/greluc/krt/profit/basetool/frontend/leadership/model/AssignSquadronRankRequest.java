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

package de.greluc.krt.profit.basetool.frontend.leadership.model;

import de.greluc.krt.profit.basetool.frontend.model.BackendEnumAsString;
import java.util.UUID;

/**
 * Body of the backend {@code PUT /api/v1/squadrons/{squadronId}/ranks/{userId}} rank assignment
 * (REQ-ROLE-004).
 *
 * @param role the backend {@code MembershipRole} name
 * @param kommandoGroupId the Kommandogruppe the rank belongs to, or {@code null}
 * @param version the membership's optimistic-lock version
 */
public record AssignSquadronRankRequest(
    @BackendEnumAsString String role, UUID kommandoGroupId, Long version) {}
