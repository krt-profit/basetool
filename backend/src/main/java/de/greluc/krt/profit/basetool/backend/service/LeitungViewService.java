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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.mapper.KommandoGroupMapper;
import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.Organisationsleitung;
import de.greluc.krt.profit.basetool.backend.model.dto.BereichLeadershipRole;
import de.greluc.krt.profit.basetool.backend.model.dto.KommandoGroupDto;
import de.greluc.krt.profit.basetool.backend.model.dto.LeitungMemberDto;
import de.greluc.krt.profit.basetool.backend.model.dto.LeitungUnitDto;
import de.greluc.krt.profit.basetool.backend.model.dto.LeitungViewDto;
import de.greluc.krt.profit.basetool.backend.repository.KommandoGroupRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only aggregator for the delegated Leitung view (REQ-ROLE-004): the org units the caller may
 * appoint into or manage, grouped by tier, with rosters and capability flags.
 *
 * <p>A unit is returned only when the caller's own delegated reach allows acting on it, so a plain
 * member receives empty lists. Appointments are written through separate, individually authorized
 * endpoints.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LeitungViewService {

  private final AuthHelperService authHelperService;
  private final OrgRoleManagementSecurityService roleSecurity;
  private final SpecialCommandSecurityService specialCommandSecurity;
  private final OrgUnitRepository orgUnitRepository;
  private final OrgUnitMembershipRepository membershipRepository;
  private final KommandoGroupRepository kommandoGroupRepository;
  private final KommandoGroupMapper kommandoGroupMapper;

  /**
   * Builds the caller's delegated Leitung view: the OL(s), Bereiche, Staffeln and Spezialkommandos
   * they may appoint into or manage the roster of. Tier lists are empty for a caller with no such
   * reach.
   *
   * @param authentication the current authentication, forwarded to the delegated verdicts; never
   *     {@code null}.
   * @return the assembled view; never {@code null}.
   */
  @NotNull
  public LeitungViewDto buildView(@NotNull Authentication authentication) {
    boolean admin = authHelperService.isAdmin();

    List<LeitungUnitDto> ols = new ArrayList<>();
    for (OrgUnit ol : sortedByName(orgUnitRepository.findActiveOrganisationsleitung())) {
      if (admin) {
        ols.add(unit(ol, true, false));
      }
    }

    List<LeitungUnitDto> bereiche = new ArrayList<>();
    for (OrgUnit bereich : sortedByName(orgUnitRepository.findActiveBereiche())) {
      boolean canAppointLead =
          admin
              || roleSecurity.canAppointBereichRole(
                  bereich.getId(), BereichLeadershipRole.LEITER, authentication);
      boolean canManageRoster =
          admin
              || roleSecurity.canAppointBereichRole(
                  bereich.getId(), BereichLeadershipRole.KOORDINATOR, authentication);
      if (canAppointLead || canManageRoster) {
        bereiche.add(unit(bereich, canAppointLead, canManageRoster));
      }
    }

    List<LeitungUnitDto> squadrons = new ArrayList<>();
    List<LeitungUnitDto> specialCommands = new ArrayList<>();
    for (OrgUnit unit : sortedByName(orgUnitRepository.findActiveSquadronsAndSpecialCommands())) {
      if (unit.getKind() == OrgUnitKind.SQUADRON) {
        boolean canAppointLead =
            admin
                || roleSecurity.canAssignSquadronRank(
                    unit.getId(), MembershipRole.STAFFELLEITER, authentication);
        boolean canManageRoster =
            admin || roleSecurity.canManageKommandoGroups(unit.getId(), authentication);
        if (canAppointLead || canManageRoster) {
          squadrons.add(unit(unit, canAppointLead, canManageRoster));
        }
      } else if (unit.getKind() == OrgUnitKind.SPECIAL_COMMAND) {
        boolean canAppointLead =
            admin || roleSecurity.canAppointSkLead(unit.getId(), authentication);
        boolean canManageRoster =
            admin || specialCommandSecurity.canManageMembers(unit.getId(), authentication);
        if (canAppointLead || canManageRoster) {
          specialCommands.add(unit(unit, canAppointLead, canManageRoster));
        }
      }
    }

    return new LeitungViewDto(admin, ols, bereiche, squadrons, specialCommands);
  }

  /**
   * Maps one managed org unit to its view DTO: its roster (lead first, then by name) and, for a
   * Staffel, its Kommandogruppen.
   *
   * @param orgUnit the managed org unit; never {@code null}.
   * @param canAppointLead whether the caller may set the top seat.
   * @param canManageRoster whether the caller may manage the subordinate roster.
   * @return the unit DTO; never {@code null}.
   */
  @NotNull
  private LeitungUnitDto unit(
      @NotNull OrgUnit orgUnit, boolean canAppointLead, boolean canManageRoster) {
    List<LeitungMemberDto> members =
        membershipRepository.findAllByIdOrgUnitId(orgUnit.getId()).stream()
            .map(LeitungViewService::member)
            .sorted(
                Comparator.comparingInt(LeitungViewService::rosterRank)
                    .thenComparing(
                        m -> m.userDisplayName() == null ? "" : m.userDisplayName(),
                        String.CASE_INSENSITIVE_ORDER))
            .toList();
    List<KommandoGroupDto> groups =
        orgUnit.getKind() == OrgUnitKind.SQUADRON
            ? kommandoGroupRepository.findBySquadronIdOrderBySortIndexAsc(orgUnit.getId()).stream()
                .map(kommandoGroupMapper::toDto)
                .toList()
            : List.of();
    return new LeitungUnitDto(
        orgUnit.getId(),
        orgUnit.getName(),
        orgUnit.getShorthand(),
        orgUnit.getKind(),
        canAppointLead,
        canManageRoster,
        members,
        groups,
        orgUnit instanceof Organisationsleitung ol ? ol.getGrandAdmiralUserId() : null);
  }

  /**
   * Maps a membership row to a roster DTO, reading the lazy {@code user} for the display label.
   *
   * @param m the membership row; never {@code null}.
   * @return the roster DTO.
   */
  @NotNull
  private static LeitungMemberDto member(@NotNull OrgUnitMembership m) {
    return new LeitungMemberDto(
        m.getId().getUserId(),
        m.getUser() == null ? null : m.getUser().getEffectiveName(),
        m.getRole(),
        m.getKommandoGroup() == null ? null : m.getKommandoGroup().getId(),
        m.getVersion() == null ? 0L : m.getVersion());
  }

  /**
   * Stable roster ordering rank: leadership ranks float to the top of their unit's roster, plain
   * members sink to the bottom; ties break on the display name.
   *
   * @param m the roster row; never {@code null}.
   * @return the sort rank (lower sorts first).
   */
  private static int rosterRank(@NotNull LeitungMemberDto m) {
    return m.role() == MembershipRole.MEMBER ? 1 : 0;
  }

  /**
   * Returns the given org units sorted case-insensitively by name.
   *
   * @param units the org units; never {@code null}.
   * @return a new name-sorted list.
   */
  private static List<OrgUnit> sortedByName(@NotNull List<OrgUnit> units) {
    return units.stream()
        .sorted(
            Comparator.comparing(
                u -> u.getName() == null ? "" : u.getName(), String.CASE_INSENSITIVE_ORDER))
        .toList();
  }
}
