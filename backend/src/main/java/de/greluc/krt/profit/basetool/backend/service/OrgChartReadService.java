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

import de.greluc.krt.profit.basetool.backend.mapper.OrgChartPositionMapper;
import de.greluc.krt.profit.basetool.backend.model.OrgChartPosition;
import de.greluc.krt.profit.basetool.backend.model.OrgChartPositionType;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.Organisationsleitung;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.AreaLeadershipDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BereichChartDto;
import de.greluc.krt.profit.basetool.backend.model.dto.CommandChartDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OlChartDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgChartDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgChartNodeDto;
import de.greluc.krt.profit.basetool.backend.model.dto.SpecialCommandChartDto;
import de.greluc.krt.profit.basetool.backend.model.dto.SquadronChartDto;
import de.greluc.krt.profit.basetool.backend.repository.OrgChartPositionRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only chart-assembly half of {@link OrgChartService}, split out under audit Thema 7 (#14). It
 * owns {@link #getOrgChart()} and the nested projection helpers that fold the persisted {@code
 * OrgChartPosition} rows into the nested {@link OrgChartDto} read model (OL tier, per-Bereich
 * tiers, ungrouped Staffeln/SKs, Kommandos and their Ensigns). The position editor CRUD, the
 * cardinality/scope write guards and the {@code @Transactional(propagation = MANDATORY)} chart
 * mirror hooks (invoked from the membership / Kommandogruppe write flows) stay in {@link
 * OrgChartService}, which keeps the shared {@code MAX_*} cardinality constants this half references
 * for its "can add another" flags.
 *
 * <p>Like {@link OrgChartService} the chart is deliberately <em>not</em> org-unit-scoped — it is
 * descriptive and grants nothing — so it wires no {@code OwnerScopeService}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrgChartReadService {

  private final OrgChartPositionRepository positionRepository;
  private final OrgUnitRepository orgUnitRepository;
  private final OrgChartPositionMapper mapper;

  /**
   * Assembles the entire chart as one nested read model: the Bereichsleitung plus a column for each
   * active Staffel and Spezialkommando, ordered by name. Open to every authenticated user.
   *
   * @return the assembled chart; never {@code null}. Empty scopes render as empty groups.
   */
  public OrgChartDto getOrgChart() {
    List<OrgUnit> units = orgUnitRepository.findActiveSquadronsAndSpecialCommands();
    List<OrgUnit> bereiche = orgUnitRepository.findActiveBereiche();
    List<OrgUnit> ols = orgUnitRepository.findActiveOrganisationsleitung();
    OrgUnit ol = ols.isEmpty() ? null : ols.getFirst();
    Set<UUID> bereichIds = bereiche.stream().map(OrgUnit::getId).collect(Collectors.toSet());

    final List<OrgChartPosition> areaPositions =
        positionRepository.findAllByOrgUnitIsNullOrderBySortIndexAscCreatedAtAsc();

    // Positions for every org-unit-bound tier: profit-eligible Staffeln/SKs + Bereiche + the OL.
    Set<UUID> chartedUnitIds = new HashSet<>();
    units.forEach(u -> chartedUnitIds.add(u.getId()));
    chartedUnitIds.addAll(bereichIds);
    if (ol != null) {
      chartedUnitIds.add(ol.getId());
    }
    List<OrgChartPosition> unitPositions =
        chartedUnitIds.isEmpty()
            ? List.of()
            : positionRepository.findAllByOrgUnitIdInOrderBySortIndexAscCreatedAtAsc(
                chartedUnitIds);
    Map<UUID, List<OrgChartPosition>> positionsByUnit =
        unitPositions.stream().collect(Collectors.groupingBy(p -> p.getOrgUnit().getId()));

    // OL tier at the very top (null when no OL exists, so the chart omits the tier). The Grand
    // Admiral (REQ-ORG-021) is surfaced above the rest of the OL. It is held by EITHER an account
    // (an OL member split out of the member list — keeps the OL_MEMBER rank, so rights are
    // unaffected) OR a free-text name for a member without an account (a synthesized node that
    // grants
    // nothing, like every other free-text holder) — the two are mutually exclusive.
    OlChartDto olTier = null;
    if (ol != null) {
      List<OrgChartNodeDto> olMembers =
          nodesOfType(
              positionsByUnit.getOrDefault(ol.getId(), List.of()), OrgChartPositionType.OL_MEMBER);
      Organisationsleitung olEntity = ol instanceof Organisationsleitung o ? o : null;
      UUID grandAdmiralUserId = olEntity == null ? null : olEntity.getGrandAdmiralUserId();
      String grandAdmiralName = olEntity == null ? null : olEntity.getGrandAdmiralDisplayName();
      OrgChartNodeDto grandAdmiral = null;
      List<OrgChartNodeDto> members = olMembers;
      if (grandAdmiralUserId != null) {
        OrgChartNodeDto account =
            olMembers.stream()
                .filter(node -> grandAdmiralUserId.equals(node.userId()))
                .findFirst()
                .orElse(null);
        if (account != null) {
          grandAdmiral = account;
          members = olMembers.stream().filter(node -> !node.equals(account)).toList();
        }
      } else if (grandAdmiralName != null) {
        grandAdmiral =
            new OrgChartNodeDto(
                null, OrgChartPositionType.OL_MEMBER, null, null, grandAdmiralName, 0, null);
      }
      olTier = new OlChartDto(ol.getId(), ol.getName(), ol.getShorthand(), grandAdmiral, members);
    }

    // One tier per Bereich: its Bereichsleitung sub-tree + the Staffeln/SKs wired under it.
    List<BereichChartDto> bereichDtos =
        bereiche.stream()
            .sorted(Comparator.comparing(OrgUnit::getName, String.CASE_INSENSITIVE_ORDER))
            .map(b -> buildBereich(b, units, positionsByUnit))
            .toList();

    // Ungrouped/legacy tier: active Staffeln/SKs NOT wired under a (charted) Bereich. Until
    // an admin creates Bereiche and assigns parents this holds every unit, so the chart degrades to
    // the pre-#692 single-tree view.
    List<SquadronChartDto> ungroupedSquadrons =
        units.stream()
            .filter(u -> u.getKind() == OrgUnitKind.SQUADRON)
            .filter(u -> !hasChartedBereichParent(u, bereichIds))
            .sorted(Comparator.comparing(OrgUnit::getName, String.CASE_INSENSITIVE_ORDER))
            .map(s -> buildSquadron(s, positionsByUnit.getOrDefault(s.getId(), List.of())))
            .toList();
    List<SpecialCommandChartDto> ungroupedSpecialCommands =
        units.stream()
            .filter(u -> u.getKind() == OrgUnitKind.SPECIAL_COMMAND)
            .filter(u -> !hasChartedBereichParent(u, bereichIds))
            .sorted(Comparator.comparing(OrgUnit::getName, String.CASE_INSENSITIVE_ORDER))
            .map(sk -> buildSpecialCommand(sk, positionsByUnit.getOrDefault(sk.getId(), List.of())))
            .toList();

    return new OrgChartDto(
        olTier,
        bereichDtos,
        buildAreaLeadership(areaPositions),
        ungroupedSquadrons,
        ungroupedSpecialCommands);
  }

  /**
   * {@code true} iff {@code unit}'s parent is one of the charted Bereiche — i.e. the unit renders
   * under that Bereich's tier rather than in the ungrouped tier. A {@code null} parent (or a parent
   * that is not an active Bereich) means the unit stays ungrouped, preserving the pre-#692 view.
   *
   * @param unit the Staffel/SK to classify; never {@code null}.
   * @param bereichIds the ids of the active Bereiche.
   * @return {@code true} iff the unit is grouped under a Bereich.
   */
  private static boolean hasChartedBereichParent(OrgUnit unit, Set<UUID> bereichIds) {
    return unit.getParent() != null && bereichIds.contains(unit.getParent().getId());
  }

  /**
   * Assembles one Bereich tier (epic #692, REQ-ORG-018): its Bereichsleitung sub-tree plus the
   * Staffeln/SKs whose parent is this Bereich, carrying the Bereich's Bereichsfarbe.
   *
   * @param bereich the Bereich org unit.
   * @param units all active Staffeln/SKs (filtered here to this Bereich's children).
   * @param positionsByUnit positions grouped by org-unit id.
   * @return the assembled Bereich tier; never {@code null}.
   */
  private BereichChartDto buildBereich(
      OrgUnit bereich, List<OrgUnit> units, Map<UUID, List<OrgChartPosition>> positionsByUnit) {
    List<SquadronChartDto> squadrons =
        units.stream()
            .filter(u -> u.getKind() == OrgUnitKind.SQUADRON)
            .filter(u -> u.getParent() != null && bereich.getId().equals(u.getParent().getId()))
            .sorted(Comparator.comparing(OrgUnit::getName, String.CASE_INSENSITIVE_ORDER))
            .map(s -> buildSquadron(s, positionsByUnit.getOrDefault(s.getId(), List.of())))
            .toList();
    List<SpecialCommandChartDto> specialCommands =
        units.stream()
            .filter(u -> u.getKind() == OrgUnitKind.SPECIAL_COMMAND)
            .filter(u -> u.getParent() != null && bereich.getId().equals(u.getParent().getId()))
            .sorted(Comparator.comparing(OrgUnit::getName, String.CASE_INSENSITIVE_ORDER))
            .map(sk -> buildSpecialCommand(sk, positionsByUnit.getOrDefault(sk.getId(), List.of())))
            .toList();
    return new BereichChartDto(
        bereich.getId(),
        bereich.getName(),
        bereich.getShorthand(),
        bereich.getDepartment(),
        buildBereichLeadership(positionsByUnit.getOrDefault(bereich.getId(), List.of())),
        squadrons,
        specialCommands);
  }

  /**
   * Builds a Bereich's Bereichsleitung as an {@link AreaLeadershipDto} (reused for layout
   * symmetry): the Bereichsleiter as {@code lead}, the Bereichskoordinatoren as {@code
   * coordinators}, the Bereichsoperatoren as {@code operators}; {@code commanders} is always empty
   * (a Bereich has no commander rank).
   *
   * @param positions the Bereich's positions.
   * @return the Bereichsleitung DTO; never {@code null}.
   */
  private AreaLeadershipDto buildBereichLeadership(List<OrgChartPosition> positions) {
    OrgChartNodeDto lead =
        positions.stream()
            .filter(p -> p.getPositionType() == OrgChartPositionType.BEREICHSLEITER)
            .findFirst()
            .map(mapper::toNode)
            .orElse(null);
    return new AreaLeadershipDto(
        lead,
        List.of(),
        nodesOfType(positions, OrgChartPositionType.BEREICHSKOORDINATOR),
        nodesOfType(positions, OrgChartPositionType.BEREICHSOPERATOR));
  }

  private AreaLeadershipDto buildAreaLeadership(List<OrgChartPosition> positions) {
    OrgChartNodeDto lead =
        positions.stream()
            .filter(p -> p.getPositionType() == OrgChartPositionType.AREA_LEAD)
            .findFirst()
            .map(mapper::toNode)
            .orElse(null);
    return new AreaLeadershipDto(
        lead,
        nodesOfType(positions, OrgChartPositionType.AREA_COMMANDER),
        nodesOfType(positions, OrgChartPositionType.AREA_COORDINATOR),
        nodesOfType(positions, OrgChartPositionType.AREA_OPERATOR));
  }

  private SquadronChartDto buildSquadron(OrgUnit unit, List<OrgChartPosition> positions) {
    OrgChartNodeDto lead =
        positions.stream()
            .filter(p -> p.getPositionType() == OrgChartPositionType.SQUADRON_LEAD)
            .findFirst()
            .map(mapper::toNode)
            .orElse(null);
    List<OrgChartPosition> commands =
        positions.stream()
            .filter(p -> p.getPositionType() == OrgChartPositionType.COMMAND_LEAD)
            .toList();
    List<CommandChartDto> commandDtos =
        commands.stream().map(cmd -> buildCommand(cmd, positions)).toList();
    List<OrgChartNodeDto> directEnsigns =
        positions.stream()
            .filter(p -> p.getPositionType() == OrgChartPositionType.ENSIGN)
            .filter(p -> p.getParent() == null)
            .map(mapper::toNode)
            .toList();
    long ensignCount =
        positions.stream().filter(p -> p.getPositionType() == OrgChartPositionType.ENSIGN).count();
    return new SquadronChartDto(
        unit.getId(),
        unit.getName(),
        unit.getShorthand(),
        lead,
        commandDtos,
        directEnsigns,
        commands.size() < OrgChartService.MAX_COMMAND_LEADS,
        ensignCount < OrgChartService.MAX_ENSIGNS);
  }

  /**
   * Projects one Kommando row plus its children into a {@link CommandChartDto}. The Kommandoleiter
   * lives on the Kommando row itself, so it is carried inline ({@code null} while vacant); the Stv.
   * and Ensigns are the rows whose {@code parent_id} points back at this Kommando. The row's {@code
   * kommando_group} link is projected so the chart editor can render a group-linked Kommando
   * read-only (epic #800, REQ-ROLE-006) — it is managed under Organisation -&gt; Leitung.
   *
   * @param command the Kommando ({@code COMMAND_LEAD}) row, with its user fetched.
   * @param siblings every position of the owning Staffel, used to find this Kommando's children.
   * @return the assembled Kommando DTO; never {@code null}.
   */
  private CommandChartDto buildCommand(OrgChartPosition command, List<OrgChartPosition> siblings) {
    User leader = command.getUser();
    OrgChartNodeDto deputy =
        siblings.stream()
            .filter(p -> p.getPositionType() == OrgChartPositionType.DEPUTY_COMMAND_LEAD)
            .filter(p -> isChildOf(p, command))
            .findFirst()
            .map(mapper::toNode)
            .orElse(null);
    List<OrgChartNodeDto> ensigns =
        siblings.stream()
            .filter(p -> p.getPositionType() == OrgChartPositionType.ENSIGN)
            .filter(p -> isChildOf(p, command))
            .map(mapper::toNode)
            .toList();
    return new CommandChartDto(
        command.getId(),
        command.getName(),
        command.getVersion(),
        command.getSortIndex(),
        command.getKommandoGroup() != null ? command.getKommandoGroup().getId() : null,
        leader != null ? leader.getId() : null,
        leader != null ? leader.getEffectiveName() : null,
        command.getDisplayName(),
        deputy,
        ensigns);
  }

  private SpecialCommandChartDto buildSpecialCommand(
      OrgUnit unit, List<OrgChartPosition> positions) {
    List<OrgChartNodeDto> commanders = nodesOfType(positions, OrgChartPositionType.SK_COMMANDER);
    return new SpecialCommandChartDto(
        unit.getId(),
        unit.getName(),
        unit.getShorthand(),
        commanders,
        commanders.size() < OrgChartService.MAX_SK_COMMANDERS);
  }

  private List<OrgChartNodeDto> nodesOfType(
      List<OrgChartPosition> positions, OrgChartPositionType type) {
    return positions.stream().filter(p -> p.getPositionType() == type).map(mapper::toNode).toList();
  }

  private static boolean isChildOf(OrgChartPosition child, OrgChartPosition parent) {
    return child.getParent() != null && parent.getId().equals(child.getParent().getId());
  }
}
