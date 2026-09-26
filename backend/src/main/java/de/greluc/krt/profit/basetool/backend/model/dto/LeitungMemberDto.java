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
import java.util.UUID;

/**
 * One roster row in the delegated Leitung view: a member of a managed org unit with their
 * functional rank and bound Kommandogruppe (REQ-ROLE-004).
 *
 * @param userId the member's account id; always populated.
 * @param userDisplayName the member's display name, falling back to the username; never empty.
 * @param role the member's functional rank in this org unit; {@code MEMBER} for a plain member.
 * @param kommandoGroupId the bound Kommandogruppe for a Kommandoleiter / stellv. Kommandoleiter /
 *     Ensign, or {@code null} for every other rank and a Staffelleiter-direct Ensign.
 * @param version the membership row's optimistic-lock version, required on a squadron-rank write.
 */
public record LeitungMemberDto(
    UUID userId, String userDisplayName, MembershipRole role, UUID kommandoGroupId, long version) {}
