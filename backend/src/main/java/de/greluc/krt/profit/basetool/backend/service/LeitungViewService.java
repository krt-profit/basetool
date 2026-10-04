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
import de.greluc.krt.profit.basetool.backend.model.dto.KommandoGroupDto;
import de.greluc.krt.profit.basetool.backend.model.dto.LeitungMemberDto;
import de.greluc.krt.profit.basetool.backend.model.dto.LeitungUnitDto;
import de.greluc.krt.profit.basetool.backend.model.dto.LeitungViewDto;
import de.greluc.krt.profit.basetool.backend.orgunit.api.BereichLeadershipRole;
import de.greluc.krt.profit.basetool.backend.repository.KommandoGroupRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only aggregator for the delegated Leitung view (REQ-ROLE-004): the org units the caller
 * leads and those below them, grouped by tier, with rosters and capability flags.
 *
 * <p>Visibility follows the caller's leadership ranks — a Staffel or SK rank shows its own unit, an
 * area rank its Bereich with the Bereich's Staffeln and SKs, an OL membership every unit — so a
 * plain member receives empty lists. The capability flags alone decide what may be changed;
 * appointments are written through separate, individually authorized endpoints.
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
  private final OrgUnitCascadeService cascadeService;

  /**
   * Builds the caller's Leitung view: the OL(s), Bereiche, Staffeln and Spezialkommandos they lead
   * or that lie below a unit they lead, each with the capability flags of the delegated verdicts.
   * An admin sees every unit; tier lists are empty for a caller without a leadership rank.
   *
   * @param authentication the current authentication, forwarded to the delegated verdicts; never
   *     {@code null}.
   * @return the assembled view; never {@code null}.
   */
  @NotNull
  public LeitungViewDto buildView(@NotNull Authentication authentication) {
    boolean admin = authHelperService.isAdmin();
    UUID callerId = authHelperService.currentUserId().orElse(null);
    Set<UUID> visible = admin ? Set.of() : visibleOrgUnitIds(callerId);

    List<LeitungUnitDto> ols = new ArrayList<>();
    for (OrgUnit ol : sortedByName(orgUnitRepository.findActiveOrganisationsleitung())) {
      if (admin || visible.contains(ol.getId())) {
        ols.add(unit(ol, admin, false, callerId));
      }
    }

    List<LeitungUnitDto> bereiche = new ArrayList<>();
    for (OrgUnit bereich : sortedByName(orgUnitRepository.findActiveBereiche())) {
      if (!admin && !visible.contains(bereich.getId())) {
        continue;
      }
      boolean canAppointLead =
          admin
              || roleSecurity.canAppointBereichRole(
                  bereich.getId(), BereichLeadershipRole.LEITER, authentication);
      boolean canManageRoster =
          admin
              || roleSecurity.canAppointBereichRole(
                  bereich.getId(), BereichLeadershipRole.KOORDINATOR, authentication);
      bereiche.add(unit(bereich, canAppointLead, canManageRoster, callerId));
    }

    List<LeitungUnitDto> squadrons = new ArrayList<>();
    List<LeitungUnitDto> specialCommands = new ArrayList<>();
    for (OrgUnit unit : sortedByName(orgUnitRepository.findActiveSquadronsAndSpecialCommands())) {
      if (!admin && !visible.contains(unit.getId())) {
        continue;
      }
      if (unit.getKind() == OrgUnitKind.SQUADRON) {
        boolean canAppointLead =
            admin
                || roleSecurity.canAssignSquadronRank(
                    unit.getId(), MembershipRole.STAFFELLEITER, authentication);
        boolean canManageRoster =
            admin || roleSecurity.canManageKommandoGroups(unit.getId(), authentication);
        squadrons.add(unit(unit, canAppointLead, canManageRoster, callerId));
      } else if (unit.getKind() == OrgUnitKind.SPECIAL_COMMAND) {
        boolean canAppointLead =
            admin || roleSecurity.canAppointSkLead(unit.getId(), authentication);
        boolean canManageRoster =
            admin || specialCommandSecurity.canManageMembers(unit.getId(), authentication);
        specialCommands.add(unit(unit, canAppointLead, canManageRoster, callerId));
      }
    }

    return new LeitungViewDto(admin, ols, bereiche, squadrons, specialCommands);
  }

  /**
   * The org units a non-admin caller leads: every unit they hold a leadership rank on, plus the
   * cascade of an area rank (the Bereich's Staffeln and SKs) or an OL membership (every unit).
   *
   * @param callerId the caller's account id, or {@code null} for an unidentifiable caller.
   * @return the visible org-unit ids; empty for a caller without a leadership rank.
   */
  @NotNull
  private Set<UUID> visibleOrgUnitIds(@Nullable UUID callerId) {
    if (callerId == null) {
      return Set.of();
    }
    List<OrgUnitMembership> leaderships =
        membershipRepository.findAllByIdUserId(callerId).stream()
            .filter(m -> m.getRole() != null && m.getRole().confersOwnLevelOversight())
            .toList();
    Set<UUID> visible = new HashSet<>(cascadeService.cascadedOfficerReach(leaderships));
    for (OrgUnitMembership m : leaderships) {
      visible.add(m.getId().getOrgUnitId());
    }
    return visible;
  }

  /**
   * Maps one managed org unit to its view DTO: its roster (lead first, then by name) and, for a
   * Staffel, its Kommandogruppen.
   *
   * @param orgUnit the managed org unit; never {@code null}.
   * @param canAppointLead whether the caller may set the top seat.
   * @param canManageRoster whether the caller may manage the subordinate roster.
   * @param callerId the caller's account id, marking their own roster row; may be {@code null}.
   * @return the unit DTO; never {@code null}.
   */
  @NotNull
  private LeitungUnitDto unit(
      @NotNull OrgUnit orgUnit,
      boolean canAppointLead,
      boolean canManageRoster,
      @Nullable UUID callerId) {
    List<LeitungMemberDto> members =
        membershipRepository.findAllByIdOrgUnitId(orgUnit.getId()).stream()
            .map(m -> member(m, callerId))
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
   * @param callerId the caller's account id, or {@code null}; the row matching it is flagged.
   * @return the roster DTO.
   */
  @NotNull
  private static LeitungMemberDto member(@NotNull OrgUnitMembership m, @Nullable UUID callerId) {
    return new LeitungMemberDto(
        m.getId().getUserId(),
        m.getUser() == null ? null : m.getUser().getEffectiveName(),
        m.getRole(),
        m.getKommandoGroup() == null ? null : m.getKommandoGroup().getId(),
        m.getVersion() == null ? 0L : m.getVersion(),
        m.getId().getUserId().equals(callerId));
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
