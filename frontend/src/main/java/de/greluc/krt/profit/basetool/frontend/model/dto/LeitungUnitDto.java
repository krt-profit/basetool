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

import java.util.List;
import java.util.UUID;

/**
 * Frontend mirror of one manageable org unit in the Leitung view (REQ-ROLE-004), with the
 * tier-relative capability flags that gate the appointment buttons.
 *
 * @param id the org unit id.
 * @param name the org unit name.
 * @param shorthand the org unit shorthand.
 * @param kind the org-unit kind, driving which rank options + section the page renders.
 * @param canAppointLead whether the caller may set this unit's top seat.
 * @param canManageRoster whether the caller may manage this unit's subordinate roster; for a
 *     Spezialkommando, its members.
 * @param members the unit's roster rows.
 * @param groups the unit's Kommandogruppen (Staffel only; empty otherwise).
 * @param grandAdmiralUserId the Grand Admiral's account id (REQ-ORG-021), set only on the
 *     Organisationsleitung, or {@code null}.
 */
public record LeitungUnitDto(
    UUID id,
    String name,
    String shorthand,
    OrgUnitKind kind,
    boolean canAppointLead,
    boolean canManageRoster,
    List<LeitungMemberDto> members,
    List<KommandoGroupDto> groups,
    UUID grandAdmiralUserId) {}
