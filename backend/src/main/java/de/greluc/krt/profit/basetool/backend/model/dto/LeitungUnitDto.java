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
 * One manageable org unit in the delegated Leitung view (epic #800, REQ-ROLE-004), with the two
 * delegated-capability flags that gate its two appointment surfaces and its current roster. The
 * meaning of the two caps is tier-relative (the page branches on {@link #kind}):
 *
 * <ul>
 *   <li><b>canAppointLead</b> — may set the unit's top seat: a Staffelleiter on a {@code SQUADRON}
 *       (the parent Bereichsleiter), an SK-Leiter on a {@code SPECIAL_COMMAND} (the parent
 *       Bereichsleiter), a Bereichsleiter on a {@code BEREICH} (a pure OL member), an OL member on
 *       the {@code ORGANISATIONSLEITUNG} (admin only).
 *   <li><b>canManageRoster</b> — may manage the in-unit subordinate ranks: Kommandoleiter / stellv.
 *       Kommandoleiter / Ensign and the Kommandogruppen on a {@code SQUADRON} (its Staffelleiter),
 *       Koordinatoren / Operatoren on a {@code BEREICH} (its Bereichsleiter); always {@code false}
 *       on an SK / OL.
 * </ul>
 *
 * <p>Admin sees and may act on every unit (both caps {@code true}); a delegated leader sees only
 * the units their tier can act on, with the matching cap(s) set.
 *
 * @param id the org unit id; always populated.
 * @param name the org unit name.
 * @param shorthand the org unit shorthand (kürzel).
 * @param kind the org-unit kind, so the page renders the right rank options and section.
 * @param canAppointLead whether the caller may set this unit's top seat (see class doc).
 * @param canManageRoster whether the caller may manage this unit's subordinate roster (see class
 *     doc).
 * @param members the unit's current roster rows (leadership, plus all members for a squadron so a
 *     Staffelleiter can promote any member); never {@code null}.
 * @param groups the unit's Kommandogruppen ({@code SQUADRON} only; empty for every other kind);
 *     never {@code null}.
 * @param grandAdmiralUserId the account id of this unit's Grand Admiral (REQ-ORG-021), or {@code
 *     null}. Only ever populated on the {@code ORGANISATIONSLEITUNG}, so the page can badge the
 *     current holder and offer the promote / vacate actions; {@code null} for every other kind.
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
