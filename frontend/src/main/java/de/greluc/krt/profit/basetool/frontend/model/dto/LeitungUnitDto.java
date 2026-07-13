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
 * Frontend mirror of one manageable org unit in the Leitung view (epic #800, REQ-ROLE-004), with
 * the two delegated-capability flags the page uses to gate its appointment buttons. The two caps
 * are tier-relative (see the backend {@code LeitungUnitDto}); the page branches on {@code kind}.
 *
 * @param id the org unit id.
 * @param name the org unit name.
 * @param shorthand the org unit shorthand.
 * @param kind the org-unit kind, driving which rank options + section the page renders.
 * @param canAppointLead whether the caller may set this unit's top seat.
 * @param canManageRoster whether the caller may manage this unit's subordinate roster.
 * @param members the unit's roster rows.
 * @param groups the unit's Kommandogruppen (Staffel only; empty otherwise).
 * @param grandAdmiralUserId the account id of this unit's Grand Admiral (REQ-ORG-021), or {@code
 *     null} — only ever set on the Organisationsleitung, so the page can badge the holder and gate
 *     the promote / vacate actions.
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
