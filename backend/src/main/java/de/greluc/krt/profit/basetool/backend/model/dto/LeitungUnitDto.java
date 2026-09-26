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

import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import java.util.List;
import java.util.UUID;

/**
 * One manageable org unit in the delegated Leitung view, with its roster and the two capability
 * flags that gate its appointment surfaces (REQ-ROLE-004).
 *
 * <p>{@code canAppointLead} allows setting the unit's top seat; {@code canManageRoster} allows
 * managing its subordinate ranks, and is always {@code false} on the Organisationsleitung. Admin
 * gets both flags on every unit.
 *
 * @param id the org unit id; always populated.
 * @param name the org unit name.
 * @param shorthand the org unit shorthand (kürzel).
 * @param kind the org-unit kind, which decides the rank options and section.
 * @param canAppointLead whether the caller may set this unit's top seat.
 * @param canManageRoster whether the caller may manage this unit's subordinate roster.
 * @param members the unit's current roster rows; never {@code null}.
 * @param groups the unit's Kommandogruppen, empty for every kind but {@code SQUADRON}; never {@code
 *     null}.
 * @param grandAdmiralUserId the Grand Admiral's account id on the {@code ORGANISATIONSLEITUNG}
 *     (REQ-ORG-021); {@code null} otherwise.
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
