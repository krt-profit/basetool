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

import java.util.UUID;

/**
 * Frontend mirror of one Leitung roster row (REQ-ROLE-004).
 *
 * @param userId the member's account id.
 * @param userDisplayName the member's display label.
 * @param role the functional-rank enum name ({@code MEMBER}, {@code STAFFELLEITER}, …), rendered
 *     via the {@code leitung.rank.*} message keys.
 * @param kommandoGroupId the bound Kommandogruppe id for an in-Kommando rank, or {@code null}.
 * @param version the membership row's optimistic-lock version, echoed on a squadron-rank write.
 */
public record LeitungMemberDto(
    UUID userId,
    String userDisplayName,
    @BackendEnumAsString String role,
    UUID kommandoGroupId,
    long version) {}
