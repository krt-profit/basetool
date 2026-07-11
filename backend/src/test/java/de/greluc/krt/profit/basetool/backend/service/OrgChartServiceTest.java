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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.OrgChartPositionMapperImpl;
import de.greluc.krt.profit.basetool.backend.model.Bereich;
import de.greluc.krt.profit.basetool.backend.model.Department;
import de.greluc.krt.profit.basetool.backend.model.KommandoGroup;
import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import de.greluc.krt.profit.basetool.backend.model.OrgChartPosition;
import de.greluc.krt.profit.basetool.backend.model.OrgChartPositionType;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.Organisationsleitung;
import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.BereichChartDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BereichLeadershipRole;
import de.greluc.krt.profit.basetool.backend.model.dto.CommandChartDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgChartDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgChartPositionCreateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgChartPositionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgChartPositionUpdateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.SquadronChartDto;
import de.greluc.krt.profit.basetool.backend.repository.OrgChartPositionRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Mockito unit tests for {@link OrgChartService}. The real generated {@link
 * OrgChartPositionMapperImpl} is wired in (not a mock) so the nested-tree assembly is asserted on
 * concrete node values; the three repositories are mocked. Pins the read assembly (grouping by
 * scope / type / parent, the inline Kommando leader, the {@code canAdd*} flags) and every write
 * guard (scope/type consistency, parent rules, cardinality limits, the name / nullable-holder
 * rules, one-user-per-scope, optimistic lock).
 */
@ExtendWith(MockitoExtension.class)
class OrgChartServiceTest {

  @Mock private OrgChartPositionRepository positionRepository;
  @Mock private OrgUnitRepository orgUnitRepository;
  @Mock private UserRepository userRepository;

  private OrgChartService service() {
    return new OrgChartService(
        positionRepository, orgUnitRepository, userRepository, new OrgChartPositionMapperImpl());
  }

  // Read/write split (#14): the chart assembly moved to OrgChartReadService, built from the same
  // mocked repositories + the real generated mapper so the nested-tree assertions stay unchanged.
  private OrgChartReadService readService() {
    return new OrgChartReadService(
        positionRepository, orgUnitRepository, new OrgChartPositionMapperImpl());
  }

  // ------------------------------------------------------------------ read assembly --

  @Test
  void getOrgChart_assemblesAreaSquadronAndSkIntoNestedTree() {
    Squadron squadron = squadron(UUID.randomUUID(), "IRIDIUM", "IRI");
    SpecialCommand sk = specialCommand(UUID.randomUUID(), "Alpha SK", "ASK");
    when(orgUnitRepository.findActiveSquadronsAndSpecialCommands())
        .thenReturn(List.of(squadron, sk));
    OrgChartPosition areaLead = pos(OrgChartPositionType.AREA_LEAD, null, null);
    OrgChartPosition areaCoordinator = pos(OrgChartPositionType.AREA_COORDINATOR, null, null);
    when(positionRepository.findAllByOrgUnitIsNullOrderBySortIndexAscCreatedAtAsc())
        .thenReturn(List.of(areaLead, areaCoordinator));

    OrgChartPosition squadronLead = pos(OrgChartPositionType.SQUADRON_LEAD, squadron, null);
    OrgChartPosition commandLead = pos(OrgChartPositionType.COMMAND_LEAD, squadron, null);
    OrgChartPosition deputy = pos(OrgChartPositionType.DEPUTY_COMMAND_LEAD, squadron, commandLead);
    OrgChartPosition commandEnsign = pos(OrgChartPositionType.ENSIGN, squadron, commandLead);
    OrgChartPosition directEnsign = pos(OrgChartPositionType.ENSIGN, squadron, null);
    OrgChartPosition skCommander = pos(OrgChartPositionType.SK_COMMANDER, sk, null);
    when(positionRepository.findAllByOrgUnitIdInOrderBySortIndexAscCreatedAtAsc(any()))
        .thenReturn(
            List.of(squadronLead, commandLead, deputy, commandEnsign, directEnsign, skCommander));

    OrgChartDto chart = readService().getOrgChart();

    assertNotNull(chart.areaLeadership().lead());
    assertEquals(OrgChartPositionType.AREA_LEAD, chart.areaLeadership().lead().positionType());
    assertEquals(1, chart.areaLeadership().coordinators().size());
    assertTrue(chart.areaLeadership().operators().isEmpty());
    assertTrue(chart.areaLeadership().commanders().isEmpty());

    assertEquals(1, chart.squadrons().size());
    SquadronChartDto squadronDto = chart.squadrons().getFirst();
    assertEquals("IRIDIUM", squadronDto.name());
    assertNotNull(squadronDto.lead());
    assertEquals(1, squadronDto.commands().size());
    CommandChartDto command = squadronDto.commands().getFirst();
    assertEquals(commandLead.getId(), command.positionId());
    assertNotNull(command.leaderUserId());
    assertNotNull(command.deputy());
    assertEquals(1, command.ensigns().size());
    assertEquals(1, squadronDto.directEnsigns().size());
    assertTrue(squadronDto.canAddCommand());
    assertTrue(squadronDto.canAddEnsign());

    assertEquals(1, chart.specialCommands().size());
    assertEquals(1, chart.specialCommands().getFirst().commanders().size());
    assertTrue(chart.specialCommands().getFirst().canAddCommander());
  }

  @Test
  void getOrgChart_leaderlessNamedCommand_carriesNameAndNullLeader() {
    Squadron squadron = squadron(UUID.randomUUID(), "IRIDIUM", "IRI");
    when(orgUnitRepository.findActiveSquadronsAndSpecialCommands()).thenReturn(List.of(squadron));
    when(positionRepository.findAllByOrgUnitIsNullOrderBySortIndexAscCreatedAtAsc())
        .thenReturn(List.of());

    OrgChartPosition command = pos(OrgChartPositionType.COMMAND_LEAD, squadron, null, null);
    command.setName("Alpha");
    OrgChartPosition deputy = pos(OrgChartPositionType.DEPUTY_COMMAND_LEAD, squadron, command);
    when(positionRepository.findAllByOrgUnitIdInOrderBySortIndexAscCreatedAtAsc(any()))
        .thenReturn(List.of(command, deputy));

    CommandChartDto dto = readService().getOrgChart().squadrons().getFirst().commands().getFirst();

    assertEquals("Alpha", dto.name());
    assertNull(dto.leaderUserId(), "a leaderless Kommando exposes no holder");
    assertNull(dto.leaderUserName());
    assertNotNull(dto.deputy(), "a Stv. may hang off a leaderless Kommando");
  }

  @Test
  void getOrgChart_freeTextCommandLeader_carriesLeaderDisplayName() {
    // A Kommandoleiter named on the chart with no Basetool account yet (REQ-ORG-020): the
    // COMMAND_LEAD row carries a free-text display_name and no user. buildCommand must surface it
    // as leaderDisplayName (not leaderUserId/leaderUserName) so the template renders the typed
    // name with the no-account marker rather than the vacant placeholder.
    Squadron squadron = squadron(UUID.randomUUID(), "IRIDIUM", "IRI");
    when(orgUnitRepository.findActiveSquadronsAndSpecialCommands()).thenReturn(List.of(squadron));
    when(positionRepository.findAllByOrgUnitIsNullOrderBySortIndexAscCreatedAtAsc())
        .thenReturn(List.of());

    OrgChartPosition command = pos(OrgChartPositionType.COMMAND_LEAD, squadron, null, null);
    command.setName("Alpha");
    command.setDisplayName("Max Mustermann");
    when(positionRepository.findAllByOrgUnitIdInOrderBySortIndexAscCreatedAtAsc(any()))
        .thenReturn(List.of(command));

    CommandChartDto dto = readService().getOrgChart().squadrons().getFirst().commands().getFirst();

    assertEquals("Alpha", dto.name());
    assertNull(dto.leaderUserId(), "a free-text leader has no account id");
    assertNull(dto.leaderUserName(), "a free-text leader has no account name");
    assertEquals(
        "Max Mustermann", dto.leaderDisplayName(), "the typed Kommandoleiter name is surfaced");
  }

  @Test
  void getOrgChart_groupLinkedCommand_projectsKommandoGroupId() {
    // A Kommando that mirrors a kommando_group (epic #800, REQ-ROLE-006): buildCommand must surface
    // the link so the chart editor can render the whole subtree read-only (managed under Leitung).
    // A
    // legacy chart-only Kommando carries a null link.
    Squadron squadron = squadron(UUID.randomUUID(), "IRIDIUM", "IRI");
    when(orgUnitRepository.findActiveSquadronsAndSpecialCommands()).thenReturn(List.of(squadron));
    when(positionRepository.findAllByOrgUnitIsNullOrderBySortIndexAscCreatedAtAsc())
        .thenReturn(List.of());

    KommandoGroup linked = group(squadron, "Alpha", 0);
    OrgChartPosition grouped =
        pos(OrgChartPositionType.COMMAND_LEAD, squadron, null, user(UUID.randomUUID(), "kl"));
    grouped.setKommandoGroup(linked);
    grouped.setSortIndex(0);
    OrgChartPosition legacy = pos(OrgChartPositionType.COMMAND_LEAD, squadron, null, null);
    legacy.setName("Legacy");
    legacy.setSortIndex(1);
    when(positionRepository.findAllByOrgUnitIdInOrderBySortIndexAscCreatedAtAsc(any()))
        .thenReturn(List.of(grouped, legacy));

    List<CommandChartDto> commands = readService().getOrgChart().squadrons().getFirst().commands();

    assertEquals(
        linked.getId(),
        commands.getFirst().kommandoGroupId(),
        "the group-linked Kommando projects its kommando_group id");
    assertNull(
        commands.get(1).kommandoGroupId(), "a legacy chart-only Kommando projects a null link");
  }

  @Test
  void getOrgChart_noActiveUnits_skipsUnitPositionQuery() {
    when(orgUnitRepository.findActiveSquadronsAndSpecialCommands()).thenReturn(List.of());
    when(positionRepository.findAllByOrgUnitIsNullOrderBySortIndexAscCreatedAtAsc())
        .thenReturn(List.of());

    OrgChartDto chart = readService().getOrgChart();

    assertNull(chart.areaLeadership().lead());
    assertTrue(chart.squadrons().isEmpty());
    assertTrue(chart.specialCommands().isEmpty());
    // The empty-IN guard must avoid the dialect-fragile IN () query entirely.
    verify(positionRepository, never()).findAllByOrgUnitIdInOrderBySortIndexAscCreatedAtAsc(any());
  }

  @Test
  void getOrgChart_fullSquadron_clearsCanAddFlags() {
    Squadron squadron = squadron(UUID.randomUUID(), "IRIDIUM", "IRI");
    when(orgUnitRepository.findActiveSquadronsAndSpecialCommands()).thenReturn(List.of(squadron));
    when(positionRepository.findAllByOrgUnitIsNullOrderBySortIndexAscCreatedAtAsc())
        .thenReturn(List.of());
    when(positionRepository.findAllByOrgUnitIdInOrderBySortIndexAscCreatedAtAsc(any()))
        .thenReturn(
            List.of(
                pos(OrgChartPositionType.COMMAND_LEAD, squadron, null),
                pos(OrgChartPositionType.COMMAND_LEAD, squadron, null),
                pos(OrgChartPositionType.COMMAND_LEAD, squadron, null),
                pos(OrgChartPositionType.COMMAND_LEAD, squadron, null),
                pos(OrgChartPositionType.ENSIGN, squadron, null),
                pos(OrgChartPositionType.ENSIGN, squadron, null),
                pos(OrgChartPositionType.ENSIGN, squadron, null),
                pos(OrgChartPositionType.ENSIGN, squadron, null)));

    SquadronChartDto squadronDto = readService().getOrgChart().squadrons().getFirst();

    assertEquals(4, squadronDto.commands().size());
    assertFalse(squadronDto.canAddCommand());
    assertFalse(squadronDto.canAddEnsign());
  }

  @Test
  void getOrgChart_groupsUnitsUnderBereichTierAndExposesOl() {
    UUID bereichId = UUID.randomUUID();
    Bereich bereich = bereich(bereichId, "Profit", "PRF");
    bereich.setDepartment(Department.PROFIT);
    Squadron grouped = squadron(UUID.randomUUID(), "Alpha", "ALF");
    grouped.setParent(bereich);
    Squadron ungrouped = squadron(UUID.randomUUID(), "Orphan", "ORP"); // parent == null
    UUID olId = UUID.randomUUID();
    Organisationsleitung ol = organisationsleitung(olId, "Organisationsleitung", "OL");

    when(orgUnitRepository.findActiveSquadronsAndSpecialCommands())
        .thenReturn(List.of(grouped, ungrouped));
    when(orgUnitRepository.findActiveBereiche()).thenReturn(List.of(bereich));
    when(orgUnitRepository.findActiveOrganisationsleitung()).thenReturn(List.of(ol));
    when(positionRepository.findAllByOrgUnitIsNullOrderBySortIndexAscCreatedAtAsc())
        .thenReturn(List.of());
    OrgChartPosition bereichsleiter = pos(OrgChartPositionType.BEREICHSLEITER, bereich, null);
    OrgChartPosition olMember = pos(OrgChartPositionType.OL_MEMBER, ol, null);
    when(positionRepository.findAllByOrgUnitIdInOrderBySortIndexAscCreatedAtAsc(any()))
        .thenReturn(List.of(bereichsleiter, olMember));

    OrgChartDto chart = readService().getOrgChart();

    // OL members surface at the top, carried by the OL tier (id + name + members).
    assertEquals(olId, chart.organisationsleitung().orgUnitId());
    assertEquals(1, chart.organisationsleitung().members().size());
    // One Bereich tier carrying its department, its Bereichsleiter and its grouped Staffel.
    assertEquals(1, chart.bereiche().size());
    BereichChartDto b = chart.bereiche().getFirst();
    assertEquals(Department.PROFIT, b.department());
    assertNotNull(b.leadership().lead());
    assertEquals(1, b.squadrons().size());
    assertEquals("Alpha", b.squadrons().getFirst().name());
    // The parentless Staffel stays in the ungrouped/legacy tier, NOT under the Bereich.
    assertEquals(1, chart.squadrons().size());
    assertEquals("Orphan", chart.squadrons().getFirst().name());
  }

  @Test
  void getOrgChart_includesNonProfitEligibleUnitsUnderBereich() {
    // ADR-0029: chart visibility is independent of is_profit_eligible. A non-Profit Staffel wired
    // under a Bereich still renders under that Bereich (previously it was filtered out entirely).
    UUID bereichId = UUID.randomUUID();
    Bereich bereich = bereich(bereichId, "Forschung", "FOR");
    bereich.setDepartment(Department.FORSCHUNG);
    Squadron orion = squadron(UUID.randomUUID(), "ORION", "ORI");
    orion.setProfitEligible(false);
    orion.setParent(bereich);

    when(orgUnitRepository.findActiveSquadronsAndSpecialCommands()).thenReturn(List.of(orion));
    when(orgUnitRepository.findActiveBereiche()).thenReturn(List.of(bereich));
    when(positionRepository.findAllByOrgUnitIsNullOrderBySortIndexAscCreatedAtAsc())
        .thenReturn(List.of());
    when(positionRepository.findAllByOrgUnitIdInOrderBySortIndexAscCreatedAtAsc(any()))
        .thenReturn(List.of());

    OrgChartDto chart = readService().getOrgChart();

    assertEquals(1, chart.bereiche().size());
    BereichChartDto b = chart.bereiche().getFirst();
    assertEquals(
        1, b.squadrons().size(), "a non-profit-eligible Staffel renders under its Bereich");
    assertEquals("ORION", b.squadrons().getFirst().name());
    assertTrue(
        chart.squadrons().isEmpty(), "it is grouped under the Bereich, not in the ungrouped tier");
  }

  // ----------------------------------------------------------------- create guards --

  @Test
  void createPosition_areaCoordinatorFreeText_persistsAndReturnsDto() {
    // Chart creates place a free-text holder; account holders are mirror-only now (REQ-ROLE-006).
    when(positionRepository.save(any()))
        .thenAnswer(
            inv -> {
              OrgChartPosition p = inv.getArgument(0);
              p.setId(UUID.randomUUID());
              return p;
            });

    OrgChartPositionDto dto =
        service()
            .createPosition(
                new OrgChartPositionCreateRequest(
                    OrgChartPositionType.AREA_COORDINATOR, null, null, null, null, null, "Max"));

    assertEquals(OrgChartPositionType.AREA_COORDINATOR, dto.positionType());
    assertEquals("Max", dto.displayName());
    assertNull(dto.orgUnitId());
  }

  @Test
  void createPosition_commandLeadWithoutUser_createsLeaderlessNamedKommando() {
    UUID unitId = UUID.randomUUID();
    when(orgUnitRepository.findById(unitId))
        .thenReturn(Optional.of(squadron(unitId, "IRIDIUM", "IRI")));
    when(positionRepository.countByOrgUnitIdAndPositionType(
            unitId, OrgChartPositionType.COMMAND_LEAD))
        .thenReturn(0L);
    when(positionRepository.save(any()))
        .thenAnswer(
            inv -> {
              OrgChartPosition p = inv.getArgument(0);
              p.setId(UUID.randomUUID());
              return p;
            });

    OrgChartPositionDto dto =
        service()
            .createPosition(
                new OrgChartPositionCreateRequest(
                    OrgChartPositionType.COMMAND_LEAD,
                    unitId,
                    null,
                    null,
                    "  Alpha  ",
                    null,
                    null));

    assertEquals(OrgChartPositionType.COMMAND_LEAD, dto.positionType());
    assertNull(dto.userId(), "a Kommando may be created with no Kommandoleiter");
    assertEquals("Alpha", dto.name(), "the name is trimmed");
    verify(userRepository, never()).findById(any());
  }

  @Test
  void createPosition_nonCommandRankWithoutUser_isRejected() {
    UUID unitId = UUID.randomUUID();

    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () ->
                service()
                    .createPosition(
                        new OrgChartPositionCreateRequest(
                            OrgChartPositionType.SQUADRON_LEAD,
                            unitId,
                            null,
                            null,
                            null,
                            null,
                            null)));

    assertTrue(ex.getMessage().contains("user_required"));
    verify(positionRepository, never()).save(any());
  }

  @Test
  void createPosition_nameOnNonCommandRank_isRejected() {
    UUID userId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();
    when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId, "lead")));
    when(orgUnitRepository.findById(unitId))
        .thenReturn(Optional.of(squadron(unitId, "IRIDIUM", "IRI")));

    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () ->
                service()
                    .createPosition(
                        new OrgChartPositionCreateRequest(
                            OrgChartPositionType.SQUADRON_LEAD,
                            unitId,
                            userId,
                            null,
                            "Nope",
                            null,
                            null)));

    assertTrue(ex.getMessage().contains("name_not_allowed"));
    verify(positionRepository, never()).save(any());
  }

  @Test
  void createPosition_deputyUnderLeaderlessKommando_persists() {
    UUID unitId = UUID.randomUUID();
    UUID parentId = UUID.randomUUID();
    Squadron squadron = squadron(unitId, "IRIDIUM", "IRI");
    OrgChartPosition leaderlessKommando =
        pos(OrgChartPositionType.COMMAND_LEAD, squadron, null, null);
    leaderlessKommando.setId(parentId);
    when(orgUnitRepository.findById(unitId)).thenReturn(Optional.of(squadron));
    when(positionRepository.findById(parentId)).thenReturn(Optional.of(leaderlessKommando));
    when(positionRepository.existsByParentIdAndPositionType(
            parentId, OrgChartPositionType.DEPUTY_COMMAND_LEAD))
        .thenReturn(false);
    when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    OrgChartPositionDto dto =
        service()
            .createPosition(
                new OrgChartPositionCreateRequest(
                    OrgChartPositionType.DEPUTY_COMMAND_LEAD,
                    unitId,
                    null,
                    parentId,
                    null,
                    null,
                    "Max"));

    assertEquals(OrgChartPositionType.DEPUTY_COMMAND_LEAD, dto.positionType());
    assertEquals("Max", dto.displayName());
  }

  @Test
  void createPosition_childUnderGroupLinkedKommando_isRejected() {
    // A kommando_group-linked Kommando mirrors a functional rank (epic #800, REQ-ROLE-006): its
    // Stv.
    // / Ensigns are appointed under Organisation -> Leitung, so the chart editor may not bolt a
    // child onto it — even a free-text one. The mirror itself writes through OrgChartService, not
    // this admin-facing create.
    UUID unitId = UUID.randomUUID();
    UUID parentId = UUID.randomUUID();
    Squadron squadron = squadron(unitId, "IRIDIUM", "IRI");
    OrgChartPosition groupedKommando =
        pos(OrgChartPositionType.COMMAND_LEAD, squadron, null, user(UUID.randomUUID(), "kl"));
    groupedKommando.setId(parentId);
    groupedKommando.setKommandoGroup(group(squadron, "Alpha", 0));
    when(orgUnitRepository.findById(unitId)).thenReturn(Optional.of(squadron));
    when(positionRepository.findById(parentId)).thenReturn(Optional.of(groupedKommando));

    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () ->
                service()
                    .createPosition(
                        new OrgChartPositionCreateRequest(
                            OrgChartPositionType.DEPUTY_COMMAND_LEAD,
                            unitId,
                            null,
                            parentId,
                            null,
                            null,
                            "Max")));

    assertTrue(ex.getMessage().contains("account_managed_in_leitung"), ex.getMessage());
    verify(positionRepository, never()).save(any());
  }

  @Test
  void createPosition_secondAreaLead_isRejected() {
    UUID userId = UUID.randomUUID();
    when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId, "lead")));
    when(positionRepository.existsByOrgUnitIsNullAndPositionType(OrgChartPositionType.AREA_LEAD))
        .thenReturn(true);

    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () ->
                service()
                    .createPosition(
                        new OrgChartPositionCreateRequest(
                            OrgChartPositionType.AREA_LEAD, null, userId, null, null, null, null)));

    assertTrue(ex.getMessage().contains("duplicate_lead"));
    verify(positionRepository, never()).save(any());
  }

  @Test
  void createPosition_squadronRankWithoutOrgUnit_isRejected() {
    UUID userId = UUID.randomUUID();
    when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId, "lead")));

    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () ->
                service()
                    .createPosition(
                        new OrgChartPositionCreateRequest(
                            OrgChartPositionType.SQUADRON_LEAD,
                            null,
                            userId,
                            null,
                            null,
                            null,
                            null)));

    assertTrue(ex.getMessage().contains("scope_mismatch"));
  }

  @Test
  void createPosition_areaRankWithOrgUnit_isRejected() {
    UUID userId = UUID.randomUUID();
    when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId, "lead")));

    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () ->
                service()
                    .createPosition(
                        new OrgChartPositionCreateRequest(
                            OrgChartPositionType.AREA_LEAD,
                            UUID.randomUUID(),
                            userId,
                            null,
                            null,
                            null,
                            null)));

    assertTrue(ex.getMessage().contains("scope_mismatch"));
  }

  @Test
  void createPosition_unitKindMismatch_isRejected() {
    UUID userId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();
    when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId, "lead")));
    // SQUADRON_LEAD pointed at an SK row.
    when(orgUnitRepository.findById(unitId))
        .thenReturn(Optional.of(specialCommand(unitId, "Alpha SK", "ASK")));

    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () ->
                service()
                    .createPosition(
                        new OrgChartPositionCreateRequest(
                            OrgChartPositionType.SQUADRON_LEAD,
                            unitId,
                            userId,
                            null,
                            null,
                            null,
                            null)));

    assertTrue(ex.getMessage().contains("scope_mismatch"));
  }

  @Test
  void createPosition_nonProfitEligibleUnit_isStaffed() {
    // Chart visibility is decoupled from is_profit_eligible (ADR-0029): a non-Profit but active
    // Staffel may hold functional ranks, so staffing it (free-text) succeeds rather than 400-ing.
    UUID unitId = UUID.randomUUID();
    Squadron squadron = squadron(unitId, "ORION", "ORI");
    squadron.setProfitEligible(false);
    when(orgUnitRepository.findById(unitId)).thenReturn(Optional.of(squadron));
    when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    OrgChartPositionDto dto =
        service()
            .createPosition(
                new OrgChartPositionCreateRequest(
                    OrgChartPositionType.SQUADRON_LEAD, unitId, null, null, null, null, "Max"));

    assertEquals(OrgChartPositionType.SQUADRON_LEAD, dto.positionType());
    assertEquals(unitId, dto.orgUnitId());
    assertEquals("Max", dto.displayName());
  }

  @Test
  void createPosition_inactiveUnit_isRejected() {
    // Active is still the gate (ADR-0029): a soft-deleted Staffel cannot be staffed.
    UUID userId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();
    Squadron squadron = squadron(unitId, "ORION", "ORI");
    squadron.setActive(false);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId, "lead")));
    when(orgUnitRepository.findById(unitId)).thenReturn(Optional.of(squadron));

    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () ->
                service()
                    .createPosition(
                        new OrgChartPositionCreateRequest(
                            OrgChartPositionType.SQUADRON_LEAD,
                            unitId,
                            userId,
                            null,
                            null,
                            null,
                            null)));

    assertTrue(ex.getMessage().contains("unit_inactive"));
  }

  @Test
  void createPosition_fifthCommandLead_isRejected() {
    UUID userId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();
    when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId, "cl")));
    when(orgUnitRepository.findById(unitId))
        .thenReturn(Optional.of(squadron(unitId, "IRIDIUM", "IRI")));
    when(positionRepository.countByOrgUnitIdAndPositionType(
            unitId, OrgChartPositionType.COMMAND_LEAD))
        .thenReturn(4L);

    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () ->
                service()
                    .createPosition(
                        new OrgChartPositionCreateRequest(
                            OrgChartPositionType.COMMAND_LEAD,
                            unitId,
                            userId,
                            null,
                            null,
                            null,
                            null)));

    assertTrue(ex.getMessage().contains("command_limit"));
  }

  @Test
  void createPosition_fifthEnsign_isRejected() {
    UUID userId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();
    when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId, "e")));
    when(orgUnitRepository.findById(unitId))
        .thenReturn(Optional.of(squadron(unitId, "IRIDIUM", "IRI")));
    when(positionRepository.countByOrgUnitIdAndPositionType(unitId, OrgChartPositionType.ENSIGN))
        .thenReturn(4L);

    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () ->
                service()
                    .createPosition(
                        new OrgChartPositionCreateRequest(
                            OrgChartPositionType.ENSIGN, unitId, userId, null, null, null, null)));

    assertTrue(ex.getMessage().contains("ensign_limit"));
  }

  @Test
  void createPosition_thirdSkCommander_isRejected() {
    UUID userId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();
    when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId, "c")));
    when(orgUnitRepository.findById(unitId))
        .thenReturn(Optional.of(specialCommand(unitId, "Alpha SK", "ASK")));
    when(positionRepository.countByOrgUnitIdAndPositionType(
            unitId, OrgChartPositionType.SK_COMMANDER))
        .thenReturn(2L);

    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () ->
                service()
                    .createPosition(
                        new OrgChartPositionCreateRequest(
                            OrgChartPositionType.SK_COMMANDER,
                            unitId,
                            userId,
                            null,
                            null,
                            null,
                            null)));

    assertTrue(ex.getMessage().contains("commander_limit"));
  }

  @Test
  void createPosition_deputyWithoutParent_isRejected() {
    UUID userId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();
    when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId, "d")));
    when(orgUnitRepository.findById(unitId))
        .thenReturn(Optional.of(squadron(unitId, "IRIDIUM", "IRI")));

    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () ->
                service()
                    .createPosition(
                        new OrgChartPositionCreateRequest(
                            OrgChartPositionType.DEPUTY_COMMAND_LEAD,
                            unitId,
                            userId,
                            null,
                            null,
                            null,
                            null)));

    assertTrue(ex.getMessage().contains("invalid_parent"));
  }

  @Test
  void createPosition_ensignWithNonCommandParent_isRejected() {
    UUID userId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();
    UUID parentId = UUID.randomUUID();
    Squadron squadron = squadron(unitId, "IRIDIUM", "IRI");
    OrgChartPosition squadronLead = pos(OrgChartPositionType.SQUADRON_LEAD, squadron, null);
    squadronLead.setId(parentId);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId, "e")));
    when(orgUnitRepository.findById(unitId)).thenReturn(Optional.of(squadron));
    when(positionRepository.findById(parentId)).thenReturn(Optional.of(squadronLead));

    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () ->
                service()
                    .createPosition(
                        new OrgChartPositionCreateRequest(
                            OrgChartPositionType.ENSIGN,
                            unitId,
                            userId,
                            parentId,
                            null,
                            null,
                            null)));

    assertTrue(ex.getMessage().contains("invalid_parent"));
  }

  @Test
  void createPosition_secondDeputyForSameCommand_isRejected() {
    UUID userId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();
    UUID parentId = UUID.randomUUID();
    Squadron squadron = squadron(unitId, "IRIDIUM", "IRI");
    OrgChartPosition commandLead = pos(OrgChartPositionType.COMMAND_LEAD, squadron, null);
    commandLead.setId(parentId);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId, "d")));
    when(orgUnitRepository.findById(unitId)).thenReturn(Optional.of(squadron));
    when(positionRepository.findById(parentId)).thenReturn(Optional.of(commandLead));
    when(positionRepository.existsByParentIdAndPositionType(
            parentId, OrgChartPositionType.DEPUTY_COMMAND_LEAD))
        .thenReturn(true);

    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () ->
                service()
                    .createPosition(
                        new OrgChartPositionCreateRequest(
                            OrgChartPositionType.DEPUTY_COMMAND_LEAD,
                            unitId,
                            userId,
                            parentId,
                            null,
                            null,
                            null)));

    assertTrue(ex.getMessage().contains("duplicate_deputy"));
  }

  @Test
  void createPosition_userAlreadyInScope_isRejected() {
    UUID userId = UUID.randomUUID();
    when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId, "c")));
    when(positionRepository.existsByOrgUnitIsNullAndUserId(userId)).thenReturn(true);

    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () ->
                service()
                    .createPosition(
                        new OrgChartPositionCreateRequest(
                            OrgChartPositionType.AREA_COORDINATOR,
                            null,
                            userId,
                            null,
                            null,
                            null,
                            null)));

    assertTrue(ex.getMessage().contains("user_already_assigned"));
    verify(positionRepository, never()).save(any());
  }

  @Test
  void createPosition_unknownUser_throwsNotFound() {
    UUID userId = UUID.randomUUID();
    when(userRepository.findById(userId)).thenReturn(Optional.empty());

    assertThrows(
        NotFoundException.class,
        () ->
            service()
                .createPosition(
                    new OrgChartPositionCreateRequest(
                        OrgChartPositionType.AREA_COORDINATOR,
                        null,
                        userId,
                        null,
                        null,
                        null,
                        null)));
  }

  @Test
  void createPosition_unknownOrgUnit_throwsNotFound() {
    UUID userId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();
    when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId, "l")));
    when(orgUnitRepository.findById(unitId)).thenReturn(Optional.empty());

    assertThrows(
        NotFoundException.class,
        () ->
            service()
                .createPosition(
                    new OrgChartPositionCreateRequest(
                        OrgChartPositionType.SQUADRON_LEAD,
                        unitId,
                        userId,
                        null,
                        null,
                        null,
                        null)));
  }

  // -------------------------------------------- Bereich / OL scopes (REQ-ORG-018) --

  @Test
  void createPosition_bereichsleiterFreeText_persists() {
    // Account-linked seats are mirror-only now (REQ-ROLE-006); the chart create only places a
    // free-text holder (a member without a Basetool account).
    UUID bereichId = UUID.randomUUID();
    when(orgUnitRepository.findById(bereichId))
        .thenReturn(Optional.of(bereich(bereichId, "Profit", "PRF")));
    when(positionRepository.countByOrgUnitIdAndPositionType(
            bereichId, OrgChartPositionType.BEREICHSLEITER))
        .thenReturn(0L);
    when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    OrgChartPositionDto dto =
        service()
            .createPosition(
                new OrgChartPositionCreateRequest(
                    OrgChartPositionType.BEREICHSLEITER,
                    bereichId,
                    null,
                    null,
                    null,
                    null,
                    "Max Mustermann"));

    assertEquals(OrgChartPositionType.BEREICHSLEITER, dto.positionType());
    assertEquals("Max Mustermann", dto.displayName());
  }

  @Test
  void createPosition_withAccount_isRejected() {
    // The chart editor may not place an account holder — appoint it under Leitung (REQ-ROLE-006).
    UUID userId = UUID.randomUUID();
    UUID bereichId = UUID.randomUUID();
    when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId, "blead")));
    when(orgUnitRepository.findById(bereichId))
        .thenReturn(Optional.of(bereich(bereichId, "Profit", "PRF")));
    when(positionRepository.countByOrgUnitIdAndPositionType(
            bereichId, OrgChartPositionType.BEREICHSLEITER))
        .thenReturn(0L);
    when(positionRepository.existsByOrgUnitIdAndUserId(bereichId, userId)).thenReturn(false);

    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () ->
                service()
                    .createPosition(
                        new OrgChartPositionCreateRequest(
                            OrgChartPositionType.BEREICHSLEITER,
                            bereichId,
                            userId,
                            null,
                            null,
                            null,
                            null)));

    assertTrue(ex.getMessage().contains("account_managed_in_leitung"), ex.getMessage());
    verify(positionRepository, never()).save(any());
  }

  @Test
  void createPosition_secondBereichsleiterSameBereich_isRejected() {
    UUID userId = UUID.randomUUID();
    UUID bereichId = UUID.randomUUID();
    when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId, "blead2")));
    when(orgUnitRepository.findById(bereichId))
        .thenReturn(Optional.of(bereich(bereichId, "Profit", "PRF")));
    when(positionRepository.countByOrgUnitIdAndPositionType(
            bereichId, OrgChartPositionType.BEREICHSLEITER))
        .thenReturn(1L);

    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () ->
                service()
                    .createPosition(
                        new OrgChartPositionCreateRequest(
                            OrgChartPositionType.BEREICHSLEITER,
                            bereichId,
                            userId,
                            null,
                            null,
                            null,
                            null)));

    assertTrue(ex.getMessage().contains("duplicate_lead"), ex.getMessage());
    verify(positionRepository, never()).save(any());
  }

  @Test
  void createPosition_bereichRankOnSquadron_isScopeMismatch() {
    UUID userId = UUID.randomUUID();
    UUID squadronId = UUID.randomUUID();
    when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId, "x")));
    when(orgUnitRepository.findById(squadronId))
        .thenReturn(Optional.of(squadron(squadronId, "IRIDIUM", "IRI")));

    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () ->
                service()
                    .createPosition(
                        new OrgChartPositionCreateRequest(
                            OrgChartPositionType.BEREICHSLEITER,
                            squadronId,
                            userId,
                            null,
                            null,
                            null,
                            null)));

    assertTrue(ex.getMessage().contains("scope_mismatch"), ex.getMessage());
  }

  @Test
  void createPosition_bereichDoesNotRequireProfitEligible() {
    // A Bereich is never profit-eligible; the org-chart create must NOT reject it for that
    // (unlike a Staffel/SK). Only its active flag is checked.
    UUID bereichId = UUID.randomUUID();
    Bereich notProfit = bereich(bereichId, "Sub-Radar", "SUB"); // isProfitEligible() == false
    when(orgUnitRepository.findById(bereichId)).thenReturn(Optional.of(notProfit));
    when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    OrgChartPositionDto dto =
        service()
            .createPosition(
                new OrgChartPositionCreateRequest(
                    OrgChartPositionType.BEREICHSKOORDINATOR,
                    bereichId,
                    null,
                    null,
                    null,
                    null,
                    "Max Mustermann"));

    assertEquals(OrgChartPositionType.BEREICHSKOORDINATOR, dto.positionType());
  }

  @Test
  void createPosition_inactiveBereich_throwsUnitInactive() {
    // An inactive Bereich is rejected, but with the precise "inactive" error rather than the
    // (irrelevant) "not profit-eligible" one a Bereich would never satisfy anyway.
    UUID userId = UUID.randomUUID();
    UUID bereichId = UUID.randomUUID();
    Bereich inactive = bereich(bereichId, "Sub-Radar", "SUB");
    inactive.setActive(false);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId, "k")));
    when(orgUnitRepository.findById(bereichId)).thenReturn(Optional.of(inactive));

    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () ->
                service()
                    .createPosition(
                        new OrgChartPositionCreateRequest(
                            OrgChartPositionType.BEREICHSKOORDINATOR,
                            bereichId,
                            userId,
                            null,
                            null,
                            null,
                            null)));

    assertTrue(ex.getMessage().contains("unit_inactive"), ex.getMessage());
    verify(positionRepository, never()).save(any());
  }

  @Test
  void createPosition_olMember_persists() {
    UUID olId = UUID.randomUUID();
    when(orgUnitRepository.findById(olId))
        .thenReturn(Optional.of(organisationsleitung(olId, "Organisationsleitung", "OL")));
    when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    OrgChartPositionDto dto =
        service()
            .createPosition(
                new OrgChartPositionCreateRequest(
                    OrgChartPositionType.OL_MEMBER,
                    olId,
                    null,
                    null,
                    null,
                    null,
                    "Max Mustermann"));

    assertEquals(OrgChartPositionType.OL_MEMBER, dto.positionType());
    assertEquals(olId, dto.orgUnitId());
  }

  // ----------------------------------------------------------------- update / delete --

  @Test
  void updatePosition_staleVersion_throwsOptimisticLock() {
    UUID id = UUID.randomUUID();
    OrgChartPosition position = pos(OrgChartPositionType.AREA_COORDINATOR, null, null);
    position.setId(id);
    position.setVersion(3L);
    when(positionRepository.findById(id)).thenReturn(Optional.of(position));

    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () ->
            service()
                .updatePosition(id, new OrgChartPositionUpdateRequest(null, null, null, 1L, null)));
    verify(positionRepository, never()).save(any());
  }

  @Test
  void updatePosition_reassignToAccount_isRejected() {
    // The chart editor may not assign an account holder — that moved to Leitung (REQ-ROLE-006).
    UUID id = UUID.randomUUID();
    UUID newUserId = UUID.randomUUID();
    OrgChartPosition position = pos(OrgChartPositionType.AREA_COORDINATOR, null, null, null);
    position.setDisplayName("Free Text");
    position.setId(id);
    position.setVersion(0L);
    when(positionRepository.findById(id)).thenReturn(Optional.of(position));

    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () ->
                service()
                    .updatePosition(
                        id, new OrgChartPositionUpdateRequest(newUserId, null, null, 0L, null)));

    assertTrue(ex.getMessage().contains("account_managed_in_leitung"), ex.getMessage());
    verify(positionRepository, never()).save(any());
  }

  @Test
  void updatePosition_reassignFreeTextHolder_persists() {
    // Renaming a free-text holder to another free-text name stays a chart function.
    UUID id = UUID.randomUUID();
    OrgChartPosition position = pos(OrgChartPositionType.AREA_COORDINATOR, null, null, null);
    position.setDisplayName("Old Name");
    position.setId(id);
    position.setVersion(0L);
    when(positionRepository.findById(id)).thenReturn(Optional.of(position));
    when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    OrgChartPositionDto dto =
        service()
            .updatePosition(
                id, new OrgChartPositionUpdateRequest(null, null, null, 0L, "New Name"));

    assertEquals("New Name", dto.displayName());
  }

  @Test
  void updatePosition_mirrorManagedAccountSeat_isRejected() {
    // An account-held seat reflects a functional rank and is read-only in the chart (REQ-ROLE-006).
    UUID id = UUID.randomUUID();
    OrgChartPosition position =
        pos(OrgChartPositionType.AREA_COORDINATOR, null, null, user(UUID.randomUUID(), "acct"));
    position.setId(id);
    position.setVersion(0L);
    when(positionRepository.findById(id)).thenReturn(Optional.of(position));

    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () ->
                service()
                    .updatePosition(
                        id, new OrgChartPositionUpdateRequest(null, null, null, 0L, "Max")));

    assertTrue(ex.getMessage().contains("account_managed_in_leitung"), ex.getMessage());
    verify(positionRepository, never()).save(any());
  }

  @Test
  void updatePosition_assignFreeTextLeaderToLeaderlessKommando_persists() {
    UUID id = UUID.randomUUID();
    Squadron squadron = squadron(UUID.randomUUID(), "IRIDIUM", "IRI");
    OrgChartPosition kommando = pos(OrgChartPositionType.COMMAND_LEAD, squadron, null, null);
    kommando.setId(id);
    kommando.setVersion(0L);
    when(positionRepository.findById(id)).thenReturn(Optional.of(kommando));
    when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    OrgChartPositionDto dto =
        service()
            .updatePosition(id, new OrgChartPositionUpdateRequest(null, null, null, 0L, "Max"));

    assertEquals("Max", dto.displayName());
  }

  @Test
  void updatePosition_renameLeaderlessCommand_persists() {
    UUID id = UUID.randomUUID();
    Squadron squadron = squadron(UUID.randomUUID(), "IRIDIUM", "IRI");
    OrgChartPosition kommando = pos(OrgChartPositionType.COMMAND_LEAD, squadron, null, null);
    kommando.setId(id);
    kommando.setVersion(0L);
    when(positionRepository.findById(id)).thenReturn(Optional.of(kommando));
    when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    OrgChartPositionDto dto =
        service()
            .updatePosition(id, new OrgChartPositionUpdateRequest(null, "Bravo", null, 0L, null));

    assertEquals("Bravo", dto.name());
  }

  @Test
  void updatePosition_nameOnNonCommandRank_isRejected() {
    UUID id = UUID.randomUUID();
    OrgChartPosition position = pos(OrgChartPositionType.AREA_COORDINATOR, null, null, null);
    position.setDisplayName("Free Text");
    position.setId(id);
    position.setVersion(0L);
    when(positionRepository.findById(id)).thenReturn(Optional.of(position));

    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () ->
                service()
                    .updatePosition(
                        id, new OrgChartPositionUpdateRequest(null, "Nope", null, 0L, null)));

    assertTrue(ex.getMessage().contains("name_not_allowed"));
    verify(positionRepository, never()).save(any());
  }

  @Test
  void updatePosition_unknownId_throwsNotFound() {
    UUID id = UUID.randomUUID();
    when(positionRepository.findById(id)).thenReturn(Optional.empty());

    assertThrows(
        NotFoundException.class,
        () ->
            service()
                .updatePosition(id, new OrgChartPositionUpdateRequest(null, null, null, 0L, null)));
  }

  // ------------------------------------------- free-text holder names (REQ-ORG-020) --

  @Test
  void createPosition_freeTextHolder_persistsDisplayNameAndNullUser() {
    UUID bereichId = UUID.randomUUID();
    when(orgUnitRepository.findById(bereichId))
        .thenReturn(Optional.of(bereich(bereichId, "Profit", "PRF")));
    when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    OrgChartPositionDto dto =
        service()
            .createPosition(
                new OrgChartPositionCreateRequest(
                    OrgChartPositionType.BEREICHSKOORDINATOR,
                    bereichId,
                    null,
                    null,
                    null,
                    null,
                    "  Max Mustermann  "));

    assertNull(dto.userId(), "a free-text holder has no account");
    assertNull(dto.userName(), "the mapper leaves userName null when there is no user");
    assertEquals("Max Mustermann", dto.displayName(), "the typed name is trimmed");
    verify(userRepository, never()).findById(any());
  }

  @Test
  void createPosition_bothAccountAndFreeText_isRejected() {
    UUID userId = UUID.randomUUID();
    UUID bereichId = UUID.randomUUID();

    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () ->
                service()
                    .createPosition(
                        new OrgChartPositionCreateRequest(
                            OrgChartPositionType.BEREICHSKOORDINATOR,
                            bereichId,
                            userId,
                            null,
                            null,
                            null,
                            "Max")));

    assertTrue(ex.getMessage().contains("holder_ambiguous"));
    verify(positionRepository, never()).save(any());
  }

  @Test
  void updatePosition_replaceFreeTextWithAccount_isRejected() {
    // The free-text -> account swap moved to Leitung (REQ-ROLE-006); the chart no longer assigns an
    // account, so a free-text holder is never swapped for an account here.
    UUID id = UUID.randomUUID();
    UUID newUserId = UUID.randomUUID();
    OrgChartPosition position = pos(OrgChartPositionType.AREA_COORDINATOR, null, null, null);
    position.setDisplayName("Max Mustermann");
    position.setId(id);
    position.setVersion(0L);
    position.setSortIndex(7);
    when(positionRepository.findById(id)).thenReturn(Optional.of(position));

    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () ->
                service()
                    .updatePosition(
                        id, new OrgChartPositionUpdateRequest(newUserId, null, null, 0L, null)));

    assertTrue(ex.getMessage().contains("account_managed_in_leitung"), ex.getMessage());
    verify(positionRepository, never()).save(any());
  }

  @Test
  void updatePosition_clearFreeTextLeavesNonCommandWithNoHolder_isRejected() {
    UUID id = UUID.randomUUID();
    OrgChartPosition position = pos(OrgChartPositionType.AREA_COORDINATOR, null, null, null);
    position.setDisplayName("Max");
    position.setId(id);
    position.setVersion(0L);
    when(positionRepository.findById(id)).thenReturn(Optional.of(position));

    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () ->
                service()
                    .updatePosition(
                        id, new OrgChartPositionUpdateRequest(null, null, null, 0L, "   ")));

    assertTrue(ex.getMessage().contains("user_required"));
    verify(positionRepository, never()).save(any());
  }

  @Test
  void vacateCommandLeader_clearsFreeTextLeaderName() {
    UUID id = UUID.randomUUID();
    Squadron squadron = squadron(UUID.randomUUID(), "IRIDIUM", "IRI");
    OrgChartPosition kommando = pos(OrgChartPositionType.COMMAND_LEAD, squadron, null, null);
    kommando.setDisplayName("Max");
    kommando.setName("Alpha");
    kommando.setId(id);
    kommando.setVersion(2L);
    when(positionRepository.findById(id)).thenReturn(Optional.of(kommando));
    when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    OrgChartPositionDto dto = service().vacateCommandLeader(id, 2L);

    assertNull(dto.userId());
    assertNull(dto.displayName(), "vacate clears a free-text leader name too");
    assertEquals("Alpha", dto.name(), "the Kommando name survives a vacate");
  }

  @Test
  void updatePosition_bothAccountAndFreeText_isRejected() {
    // Mirror of createPosition_bothAccountAndFreeText_isRejected on the update path: an account
    // and a free-text name are mutually exclusive, so a single edit may not set both at once. It
    // fails fast, before resolving the user or saving.
    UUID id = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    OrgChartPosition position = pos(OrgChartPositionType.AREA_COORDINATOR, null, null, null);
    position.setDisplayName("Max");
    position.setId(id);
    position.setVersion(0L);
    when(positionRepository.findById(id)).thenReturn(Optional.of(position));

    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () ->
                service()
                    .updatePosition(
                        id, new OrgChartPositionUpdateRequest(userId, null, null, 0L, "Max")));

    // A userId on a chart update is now rejected outright (account assignment moved to Leitung),
    // before the holder-ambiguity check is even reached.
    assertTrue(ex.getMessage().contains("account_managed_in_leitung"), ex.getMessage());
    verify(positionRepository, never()).save(any());
    verify(userRepository, never()).findById(any());
  }

  @Test
  void deletePosition_present_deletes() {
    // A leaderless / free-text position (no account, no group link) stays removable from the chart.
    UUID id = UUID.randomUUID();
    OrgChartPosition position = pos(OrgChartPositionType.COMMAND_LEAD, null, null, null);
    position.setId(id);
    when(positionRepository.findById(id)).thenReturn(Optional.of(position));

    service().deletePosition(id);

    verify(positionRepository).delete(position);
  }

  @Test
  void deletePosition_accountHeld_isRejected() {
    // A mirror-managed seat (account holder) is removed by clearing the rank under Leitung, not
    // here.
    UUID id = UUID.randomUUID();
    OrgChartPosition position =
        pos(OrgChartPositionType.BEREICHSKOORDINATOR, null, null, user(UUID.randomUUID(), "acct"));
    position.setId(id);
    when(positionRepository.findById(id)).thenReturn(Optional.of(position));

    BadRequestException ex =
        assertThrows(BadRequestException.class, () -> service().deletePosition(id));

    assertTrue(ex.getMessage().contains("account_managed_in_leitung"), ex.getMessage());
    verify(positionRepository, never()).delete(any());
  }

  @Test
  void deletePosition_unknownId_throwsNotFound() {
    UUID id = UUID.randomUUID();
    when(positionRepository.findById(id)).thenReturn(Optional.empty());

    assertThrows(NotFoundException.class, () -> service().deletePosition(id));
    verify(positionRepository, never()).delete(any());
  }

  // ----------------------------------------------------------------- vacate leader --

  @Test
  void vacateCommandLeader_clearsHolderButKeepsKommando() {
    UUID id = UUID.randomUUID();
    Squadron squadron = squadron(UUID.randomUUID(), "IRIDIUM", "IRI");
    OrgChartPosition kommando =
        pos(OrgChartPositionType.COMMAND_LEAD, squadron, null, user(UUID.randomUUID(), "lead"));
    kommando.setId(id);
    kommando.setVersion(2L);
    kommando.setName("Alpha");
    when(positionRepository.findById(id)).thenReturn(Optional.of(kommando));
    when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    OrgChartPositionDto dto = service().vacateCommandLeader(id, 2L);

    assertNull(dto.userId(), "the Kommandoleiter seat is now vacant");
    assertEquals("Alpha", dto.name(), "the Kommandogruppe itself stays — name preserved");
    assertNull(kommando.getUser(), "the holder is cleared on the managed entity");
    verify(positionRepository).save(kommando);
  }

  @Test
  void vacateCommandLeader_nonCommandRank_isRejected() {
    UUID id = UUID.randomUUID();
    OrgChartPosition position =
        pos(OrgChartPositionType.AREA_COORDINATOR, null, null, user(UUID.randomUUID(), "c"));
    position.setId(id);
    position.setVersion(0L);
    when(positionRepository.findById(id)).thenReturn(Optional.of(position));

    BadRequestException ex =
        assertThrows(BadRequestException.class, () -> service().vacateCommandLeader(id, 0L));

    assertTrue(ex.getMessage().contains("vacate_not_command"));
    verify(positionRepository, never()).save(any());
  }

  @Test
  void vacateCommandLeader_staleVersion_throwsOptimisticLock() {
    UUID id = UUID.randomUUID();
    Squadron squadron = squadron(UUID.randomUUID(), "IRIDIUM", "IRI");
    OrgChartPosition kommando =
        pos(OrgChartPositionType.COMMAND_LEAD, squadron, null, user(UUID.randomUUID(), "lead"));
    kommando.setId(id);
    kommando.setVersion(3L);
    when(positionRepository.findById(id)).thenReturn(Optional.of(kommando));

    assertThrows(
        ObjectOptimisticLockingFailureException.class, () -> service().vacateCommandLeader(id, 1L));
    verify(positionRepository, never()).save(any());
  }

  @Test
  void vacateCommandLeader_unknownId_throwsNotFound() {
    UUID id = UUID.randomUUID();
    when(positionRepository.findById(id)).thenReturn(Optional.empty());

    assertThrows(NotFoundException.class, () -> service().vacateCommandLeader(id, 0L));
    verify(positionRepository, never()).save(any());
  }

  @Test
  void vacateCommandLeader_groupLinked_isRejected() {
    // A kommando_group-linked Kommando mirrors a functional rank — its Kommandoleiter is vacated by
    // removing the rank under Leitung (REQ-ROLE-006), not from the chart.
    UUID id = UUID.randomUUID();
    Squadron squadron = squadron(UUID.randomUUID(), "IRIDIUM", "IRI");
    OrgChartPosition kommando =
        pos(OrgChartPositionType.COMMAND_LEAD, squadron, null, user(UUID.randomUUID(), "kl"));
    kommando.setKommandoGroup(group(squadron, "Alpha", 0));
    kommando.setId(id);
    kommando.setVersion(2L);
    when(positionRepository.findById(id)).thenReturn(Optional.of(kommando));

    BadRequestException ex =
        assertThrows(BadRequestException.class, () -> service().vacateCommandLeader(id, 2L));

    assertTrue(ex.getMessage().contains("account_managed_in_leitung"), ex.getMessage());
    verify(positionRepository, never()).save(any());
  }

  // ----------------------------------------------- role-model mirror (REQ-ROLE-006) --

  @Test
  void mirrorBereichRole_leiter_reassignsExistingSingletonWithoutSave() {
    UUID bereichId = UUID.randomUUID();
    UUID newUserId = UUID.randomUUID();
    User newUser = user(newUserId, "new-leiter");
    OrgChartPosition existing =
        pos(OrgChartPositionType.BEREICHSLEITER, bereich(bereichId, "Profit", "PRF"), null);
    existing.setDisplayName("Old Free Text");
    when(positionRepository.findByOrgUnitIdAndUserId(bereichId, newUserId)).thenReturn(List.of());
    when(positionRepository.findFirstByOrgUnitIdAndPositionTypeOrderBySortIndexAscCreatedAtAsc(
            bereichId, OrgChartPositionType.BEREICHSLEITER))
        .thenReturn(Optional.of(existing));
    when(userRepository.getReferenceById(newUserId)).thenReturn(newUser);

    service().mirrorBereichRole(bereichId, newUserId, BereichLeadershipRole.LEITER);

    assertSame(newUser, existing.getUser(), "the single Bereichsleiter seat is reassigned");
    assertNull(existing.getDisplayName(), "a free-text holder is cleared on reassign");
    // Reassign mutates the managed entity; no save() (so no second @Version bump).
    verify(positionRepository, never()).save(any());
  }

  @Test
  void mirrorBereichRole_koordinator_createsSeatAppendedAfterExisting() {
    UUID bereichId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    Bereich b = bereich(bereichId, "Profit", "PRF");
    User u = user(userId, "koord");
    when(positionRepository.findByOrgUnitIdAndUserId(bereichId, userId)).thenReturn(List.of());
    when(positionRepository.countByOrgUnitIdAndPositionType(
            bereichId, OrgChartPositionType.BEREICHSKOORDINATOR))
        .thenReturn(2L);
    when(orgUnitRepository.getReferenceById(bereichId)).thenReturn(b);
    when(userRepository.getReferenceById(userId)).thenReturn(u);
    when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service().mirrorBereichRole(bereichId, userId, BereichLeadershipRole.KOORDINATOR);

    OrgChartPosition saved = captureSaved();
    assertEquals(OrgChartPositionType.BEREICHSKOORDINATOR, saved.getPositionType());
    assertSame(u, saved.getUser());
    assertSame(b, saved.getOrgUnit());
    assertNull(saved.getParent());
    assertEquals(2, saved.getSortIndex(), "appended after the two existing coordinators");
  }

  @Test
  void mirrorBereichRole_changingRole_deletesPriorSeatThenCreatesNew() {
    UUID bereichId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    Bereich b = bereich(bereichId, "Profit", "PRF");
    OrgChartPosition priorKoord = pos(OrgChartPositionType.BEREICHSKOORDINATOR, b, null);
    when(positionRepository.findByOrgUnitIdAndUserId(bereichId, userId))
        .thenReturn(List.of(priorKoord));
    when(positionRepository.findFirstByOrgUnitIdAndPositionTypeOrderBySortIndexAscCreatedAtAsc(
            bereichId, OrgChartPositionType.BEREICHSLEITER))
        .thenReturn(Optional.empty());
    when(positionRepository.countByOrgUnitIdAndPositionType(
            bereichId, OrgChartPositionType.BEREICHSLEITER))
        .thenReturn(0L);
    when(orgUnitRepository.getReferenceById(bereichId)).thenReturn(b);
    when(userRepository.getReferenceById(userId)).thenReturn(user(userId, "u"));
    when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service().mirrorBereichRole(bereichId, userId, BereichLeadershipRole.LEITER);

    verify(positionRepository).delete(priorKoord);
    assertEquals(OrgChartPositionType.BEREICHSLEITER, captureSaved().getPositionType());
  }

  @Test
  void mirrorSquadronRank_staffelleiter_createsSquadronLeadWhenNone() {
    UUID squadronId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    Squadron s = squadron(squadronId, "IRIDIUM", "IRI");
    when(positionRepository.findByOrgUnitIdAndUserId(squadronId, userId)).thenReturn(List.of());
    when(positionRepository.findFirstByOrgUnitIdAndPositionTypeOrderBySortIndexAscCreatedAtAsc(
            squadronId, OrgChartPositionType.SQUADRON_LEAD))
        .thenReturn(Optional.empty());
    when(positionRepository.countByOrgUnitIdAndPositionType(
            squadronId, OrgChartPositionType.SQUADRON_LEAD))
        .thenReturn(0L);
    when(orgUnitRepository.getReferenceById(squadronId)).thenReturn(s);
    when(userRepository.getReferenceById(userId)).thenReturn(user(userId, "lead"));
    when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service().mirrorSquadronRank(squadronId, userId, MembershipRole.STAFFELLEITER, null);

    OrgChartPosition saved = captureSaved();
    assertEquals(OrgChartPositionType.SQUADRON_LEAD, saved.getPositionType());
    assertNull(saved.getParent());
  }

  @Test
  void mirrorSquadronRank_kommandoleiter_fillsExistingGroupNodeWithoutSave() {
    UUID squadronId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    Squadron s = squadron(squadronId, "IRIDIUM", "IRI");
    KommandoGroup group = group(s, "Alpha", 0);
    User u = user(userId, "kl");
    OrgChartPosition command = pos(OrgChartPositionType.COMMAND_LEAD, s, null, null);
    command.setKommandoGroup(group);
    when(positionRepository.findByOrgUnitIdAndUserId(squadronId, userId)).thenReturn(List.of());
    when(positionRepository.findByKommandoGroupId(group.getId())).thenReturn(Optional.of(command));
    when(userRepository.getReferenceById(userId)).thenReturn(u);

    service().mirrorSquadronRank(squadronId, userId, MembershipRole.KOMMANDOLEITER, group);

    assertSame(u, command.getUser(), "the group's Kommando node now carries the Kommandoleiter");
    assertNull(command.getDisplayName());
    verify(positionRepository, never()).save(any());
  }

  @Test
  void mirrorSquadronRank_kommandoleiter_createsGroupNodeWhenMissing() {
    UUID squadronId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    Squadron s = squadron(squadronId, "IRIDIUM", "IRI");
    KommandoGroup group = group(s, "Alpha", 1);
    User u = user(userId, "kl");
    when(positionRepository.findByOrgUnitIdAndUserId(squadronId, userId)).thenReturn(List.of());
    when(positionRepository.findByKommandoGroupId(group.getId())).thenReturn(Optional.empty());
    when(orgUnitRepository.getReferenceById(squadronId)).thenReturn(s);
    when(userRepository.getReferenceById(userId)).thenReturn(u);
    when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service().mirrorSquadronRank(squadronId, userId, MembershipRole.KOMMANDOLEITER, group);

    OrgChartPosition saved = captureSaved();
    assertEquals(OrgChartPositionType.COMMAND_LEAD, saved.getPositionType());
    assertSame(group, saved.getKommandoGroup());
    assertEquals("Alpha", saved.getName());
    assertSame(u, saved.getUser(), "the freshly-created node is filled with the Kommandoleiter");
  }

  @Test
  void mirrorSquadronRank_stellv_createsDeputyUnderGroupNode() {
    UUID squadronId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    Squadron s = squadron(squadronId, "IRIDIUM", "IRI");
    KommandoGroup group = group(s, "Alpha", 0);
    OrgChartPosition command = pos(OrgChartPositionType.COMMAND_LEAD, s, null, null);
    when(positionRepository.findByOrgUnitIdAndUserId(squadronId, userId)).thenReturn(List.of());
    when(positionRepository.findByKommandoGroupId(group.getId())).thenReturn(Optional.of(command));
    when(positionRepository.findByParentIdAndPositionType(
            command.getId(), OrgChartPositionType.DEPUTY_COMMAND_LEAD))
        .thenReturn(Optional.empty());
    when(orgUnitRepository.getReferenceById(squadronId)).thenReturn(s);
    when(userRepository.getReferenceById(userId)).thenReturn(user(userId, "stellv"));
    when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service().mirrorSquadronRank(squadronId, userId, MembershipRole.STELLV_KOMMANDOLEITER, group);

    OrgChartPosition saved = captureSaved();
    assertEquals(OrgChartPositionType.DEPUTY_COMMAND_LEAD, saved.getPositionType());
    assertSame(command, saved.getParent());
  }

  @Test
  void mirrorSquadronRank_ensignWithGroup_createsEnsignUnderGroupNode() {
    UUID squadronId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    Squadron s = squadron(squadronId, "IRIDIUM", "IRI");
    KommandoGroup group = group(s, "Alpha", 0);
    OrgChartPosition command = pos(OrgChartPositionType.COMMAND_LEAD, s, null, null);
    when(positionRepository.findByOrgUnitIdAndUserId(squadronId, userId)).thenReturn(List.of());
    when(positionRepository.findByKommandoGroupId(group.getId())).thenReturn(Optional.of(command));
    when(positionRepository.countByOrgUnitIdAndPositionType(
            squadronId, OrgChartPositionType.ENSIGN))
        .thenReturn(3L);
    when(orgUnitRepository.getReferenceById(squadronId)).thenReturn(s);
    when(userRepository.getReferenceById(userId)).thenReturn(user(userId, "ens"));
    when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service().mirrorSquadronRank(squadronId, userId, MembershipRole.ENSIGN, group);

    OrgChartPosition saved = captureSaved();
    assertEquals(OrgChartPositionType.ENSIGN, saved.getPositionType());
    assertSame(command, saved.getParent());
    assertEquals(3, saved.getSortIndex());
  }

  @Test
  void mirrorSquadronRank_generalEnsign_createsParentlessEnsign() {
    UUID squadronId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    Squadron s = squadron(squadronId, "IRIDIUM", "IRI");
    when(positionRepository.findByOrgUnitIdAndUserId(squadronId, userId)).thenReturn(List.of());
    when(positionRepository.countByOrgUnitIdAndPositionType(
            squadronId, OrgChartPositionType.ENSIGN))
        .thenReturn(0L);
    when(orgUnitRepository.getReferenceById(squadronId)).thenReturn(s);
    when(userRepository.getReferenceById(userId)).thenReturn(user(userId, "ens"));
    when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service().mirrorSquadronRank(squadronId, userId, MembershipRole.ENSIGN, null);

    OrgChartPosition saved = captureSaved();
    assertEquals(OrgChartPositionType.ENSIGN, saved.getPositionType());
    assertNull(saved.getParent(), "a group-less Ensign reports straight to the Staffelleiter");
  }

  @Test
  void mirrorSquadronRank_reassignFromKommandoleiter_vacatesPriorNodeNotDeletes() {
    UUID squadronId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    Squadron s = squadron(squadronId, "IRIDIUM", "IRI");
    OrgChartPosition priorCommand =
        pos(OrgChartPositionType.COMMAND_LEAD, s, null, user(userId, "kl"));
    when(positionRepository.findByOrgUnitIdAndUserId(squadronId, userId))
        .thenReturn(List.of(priorCommand));
    when(positionRepository.findFirstByOrgUnitIdAndPositionTypeOrderBySortIndexAscCreatedAtAsc(
            squadronId, OrgChartPositionType.SQUADRON_LEAD))
        .thenReturn(Optional.empty());
    when(positionRepository.countByOrgUnitIdAndPositionType(
            squadronId, OrgChartPositionType.SQUADRON_LEAD))
        .thenReturn(0L);
    when(orgUnitRepository.getReferenceById(squadronId)).thenReturn(s);
    when(userRepository.getReferenceById(userId)).thenReturn(user(userId, "lead"));
    when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service().mirrorSquadronRank(squadronId, userId, MembershipRole.STAFFELLEITER, null);

    assertNull(priorCommand.getUser(), "the former Kommando node is vacated, not removed");
    verify(positionRepository, never()).delete(priorCommand);
  }

  @Test
  void mirrorRemoveSquadronRank_deletesEnsignSeat() {
    UUID squadronId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    OrgChartPosition ensign =
        pos(OrgChartPositionType.ENSIGN, squadron(squadronId, "IRIDIUM", "IRI"), null);
    when(positionRepository.findByOrgUnitIdAndUserId(squadronId, userId))
        .thenReturn(List.of(ensign));

    service().mirrorRemoveSquadronRank(squadronId, userId);

    verify(positionRepository).delete(ensign);
  }

  @Test
  void mirrorOlMember_idempotentWhenSeatExists() {
    UUID olId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    OrgChartPosition existing =
        pos(OrgChartPositionType.OL_MEMBER, organisationsleitung(olId, "OL", "OL"), null);
    when(positionRepository.findByOrgUnitIdAndUserId(olId, userId)).thenReturn(List.of(existing));

    service().mirrorOlMember(olId, userId);

    verify(positionRepository, never()).save(any());
  }

  @Test
  void mirrorSkLead_true_createsCommanderSeat() {
    UUID skId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    SpecialCommand sk = specialCommand(skId, "Alpha SK", "ASK");
    when(positionRepository.findByOrgUnitIdAndUserId(skId, userId)).thenReturn(List.of());
    when(positionRepository.countByOrgUnitIdAndPositionType(
            skId, OrgChartPositionType.SK_COMMANDER))
        .thenReturn(1L);
    when(orgUnitRepository.getReferenceById(skId)).thenReturn(sk);
    when(userRepository.getReferenceById(userId)).thenReturn(user(userId, "skl"));
    when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service().mirrorSkLead(skId, userId, true);

    assertEquals(OrgChartPositionType.SK_COMMANDER, captureSaved().getPositionType());
  }

  @Test
  void mirrorSkLead_false_removesSeat() {
    UUID skId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    OrgChartPosition seat =
        pos(OrgChartPositionType.SK_COMMANDER, specialCommand(skId, "Alpha SK", "ASK"), null);
    when(positionRepository.findByOrgUnitIdAndUserId(skId, userId)).thenReturn(List.of(seat));

    service().mirrorSkLead(skId, userId, false);

    verify(positionRepository).delete(seat);
  }

  @Test
  void mirrorCreateKommandoGroup_addsLeaderlessKommandoNode() {
    Squadron s = squadron(UUID.randomUUID(), "IRIDIUM", "IRI");
    KommandoGroup group = group(s, "Alpha", 2);
    when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service().mirrorCreateKommandoGroup(group);

    OrgChartPosition saved = captureSaved();
    assertEquals(OrgChartPositionType.COMMAND_LEAD, saved.getPositionType());
    assertSame(s, saved.getOrgUnit());
    assertSame(group, saved.getKommandoGroup());
    assertEquals("Alpha", saved.getName());
    assertNull(saved.getUser(), "a freshly-mirrored Kommando node is leaderless");
    assertNull(saved.getDisplayName());
    assertEquals(2, saved.getSortIndex());
  }

  @Test
  void mirrorUpdateKommandoGroup_updatesNodeNameAndSortWithoutSave() {
    Squadron s = squadron(UUID.randomUUID(), "IRIDIUM", "IRI");
    KommandoGroup group = group(s, "Bravo", 3);
    OrgChartPosition node = pos(OrgChartPositionType.COMMAND_LEAD, s, null, null);
    node.setName("Alpha");
    node.setSortIndex(0);
    when(positionRepository.findByKommandoGroupId(group.getId())).thenReturn(Optional.of(node));

    service().mirrorUpdateKommandoGroup(group);

    assertEquals("Bravo", node.getName());
    assertEquals(3, node.getSortIndex());
    verify(positionRepository, never()).save(any());
  }

  @Test
  void mirrorDeleteKommandoGroup_removesNode() {
    UUID groupId = UUID.randomUUID();
    OrgChartPosition node =
        pos(OrgChartPositionType.COMMAND_LEAD, squadron(UUID.randomUUID(), "IRIDIUM", "IRI"), null);
    when(positionRepository.findByKommandoGroupId(groupId)).thenReturn(Optional.of(node));

    service().mirrorDeleteKommandoGroup(groupId);

    verify(positionRepository).delete(node);
  }

  /** Captures the single position handed to {@code positionRepository.save(...)}. */
  private OrgChartPosition captureSaved() {
    ArgumentCaptor<OrgChartPosition> captor = ArgumentCaptor.forClass(OrgChartPosition.class);
    verify(positionRepository).save(captor.capture());
    return captor.getValue();
  }

  // --------------------------------------------------------------------- fixtures --

  private static KommandoGroup group(Squadron squadron, String name, int sortIndex) {
    KommandoGroup group =
        KommandoGroup.builder().squadron(squadron).name(name).sortIndex(sortIndex).build();
    group.setId(UUID.randomUUID());
    return group;
  }

  private static User user(UUID id, String username) {
    User user = new User();
    user.setId(id);
    user.setUsername(username);
    return user;
  }

  private static Squadron squadron(UUID id, String name, String shorthand) {
    Squadron squadron = new Squadron();
    squadron.setId(id);
    squadron.setName(name);
    squadron.setShorthand(shorthand);
    squadron.setActive(true);
    squadron.setProfitEligible(true);
    return squadron;
  }

  private static SpecialCommand specialCommand(UUID id, String name, String shorthand) {
    SpecialCommand sk = new SpecialCommand();
    sk.setId(id);
    sk.setName(name);
    sk.setShorthand(shorthand);
    sk.setActive(true);
    sk.setProfitEligible(true);
    return sk;
  }

  private static Bereich bereich(UUID id, String name, String shorthand) {
    Bereich b = new Bereich();
    b.setId(id);
    b.setName(name);
    b.setShorthand(shorthand);
    b.setActive(true);
    return b;
  }

  private static Organisationsleitung organisationsleitung(UUID id, String name, String shorthand) {
    Organisationsleitung o = new Organisationsleitung();
    o.setId(id);
    o.setName(name);
    o.setShorthand(shorthand);
    o.setActive(true);
    return o;
  }

  private static OrgChartPosition pos(
      OrgChartPositionType type, OrgUnit unit, OrgChartPosition parent) {
    return pos(type, unit, parent, user(UUID.randomUUID(), "u-" + type.name()));
  }

  private static OrgChartPosition pos(
      OrgChartPositionType type, OrgUnit unit, OrgChartPosition parent, User user) {
    OrgChartPosition position = new OrgChartPosition();
    position.setId(UUID.randomUUID());
    position.setPositionType(type);
    position.setOrgUnit(unit);
    position.setParent(parent);
    position.setUser(user);
    return position;
  }
}
