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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.OrgUnitMembershipMapper;
import de.greluc.krt.profit.basetool.backend.model.Bereich;
import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.Organisationsleitung;
import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitMembershipDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.repository.SpecialCommandRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.support.StaffelMembershipResolver;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Mockito unit tests for {@link OrgUnitMembershipQueryService} — the read-only picker/option and
 * membership-accessor half split out of {@link OrgUnitMembershipService} (audit Thema 7, #14). Pins
 * the wire shapes and orderings the controllers and downstream services consume: the
 * active-org-unit and per-user option lists (Staffel-first / top-down-by-kind sorts, orphan-row
 * skips, profit-eligible flags), the descendant-expanding owning-org-unit picker, the SK roster
 * projection, the {@code …Dto} projections mapped through the real MapStruct mapper, and the
 * name-sorted Staffel accessors (REQ-ORG-017) that back the authorization gates.
 */
@ExtendWith(MockitoExtension.class)
class OrgUnitMembershipQueryServiceTest {

  @Mock private OrgUnitMembershipRepository membershipRepository;
  @Mock private SpecialCommandService specialCommandService;
  @Mock private SquadronRepository squadronRepository;
  @Mock private SpecialCommandRepository specialCommandRepository;
  @Mock private OrgUnitRepository orgUnitRepository;
  @Mock private OrgUnitCascadeService orgUnitCascadeService;
  @Mock private StaffelMembershipResolver staffelMembershipResolver;

  // Real MapStruct implementation (not a mock): the …Dto projection tests assert the actual
  // entity→DTO mapping the controllers ship, incl. the user.effectiveName read (L4, #923,
  // ADR-0067).
  @Spy
  private OrgUnitMembershipMapper orgUnitMembershipMapper =
      Mappers.getMapper(OrgUnitMembershipMapper.class);

  @InjectMocks private OrgUnitMembershipQueryService queryService;

  private SpecialCommand sc;
  private UUID scId;
  private User user;
  private UUID userId;
  private OrgUnitMembershipId id;

  @BeforeEach
  void setUp() {
    scId = UUID.randomUUID();
    sc = new SpecialCommand();
    sc.setId(scId);
    sc.setName("Alpha");
    sc.setShorthand("ALF");

    userId = UUID.randomUUID();
    user = new User();
    user.setId(userId);
    user.setUsername("alice");
    user.setDisplayName("Alice");

    id = new OrgUnitMembershipId(userId, scId);

    // findStaffelMembershipOrgUnitIds delegates the "name-sorted primary" rule to
    // StaffelMembershipResolver (tested independently in StaffelMembershipResolverTest). Delegate
    // the mock to a real instance backed by the squadron-repo mock so the single-Staffel fast path
    // stays load-free and the two-Staffel name-sort runs through the real resolver.
    StaffelMembershipResolver realResolver = new StaffelMembershipResolver(squadronRepository);
    lenient()
        .when(staffelMembershipResolver.resolveNameSortedStaffelIds(any()))
        .thenAnswer(
            invocation -> realResolver.resolveNameSortedStaffelIds(invocation.getArgument(0)));
  }

  // --- findStaffelMembershipOrgUnitIds (name-sorted primary, REQ-ORG-017) -------------------

  @Test
  void findStaffelMembershipOrgUnitIds_noStaffel_returnsEmpty() {
    when(membershipRepository.findAllByIdUserIdAndKind(userId, OrgUnitKind.SQUADRON))
        .thenReturn(List.of());

    assertTrue(queryService.findStaffelMembershipOrgUnitIds(userId).isEmpty());
  }

  @Test
  void findStaffelMembershipOrgUnitIds_singleStaffel_returnsItWithoutFullSquadronLoad() {
    UUID squadronId = UUID.randomUUID();
    when(membershipRepository.findAllByIdUserIdAndKind(userId, OrgUnitKind.SQUADRON))
        .thenReturn(List.of(staffelRow(userId, squadronId)));
    when(squadronRepository.existsById(squadronId)).thenReturn(true);

    assertEquals(List.of(squadronId), queryService.findStaffelMembershipOrgUnitIds(userId));
    // The single-Staffel fast path does only a cheap existsById, never a full entity load/sort.
    verify(squadronRepository, never()).findAllById(any());
  }

  @Test
  void findStaffelMembershipOrgUnitIds_twoStaffeln_returnsNameSortedPrimaryFirst() {
    UUID alphaId = UUID.randomUUID();
    UUID bravoId = UUID.randomUUID();
    when(membershipRepository.findAllByIdUserIdAndKind(userId, OrgUnitKind.SQUADRON))
        .thenReturn(List.of(staffelRow(userId, bravoId), staffelRow(userId, alphaId)));
    when(squadronRepository.findAllById(any()))
        .thenReturn(List.of(squadron(bravoId, "Bravo"), squadron(alphaId, "Alpha")));

    assertEquals(List.of(alphaId, bravoId), queryService.findStaffelMembershipOrgUnitIds(userId));
  }

  /** Builds a {@code SQUADRON}-kind membership row pointing the user at the given squadron. */
  private static OrgUnitMembership staffelRow(UUID userId, UUID squadronId) {
    OrgUnitMembership m = new OrgUnitMembership();
    m.setId(new OrgUnitMembershipId(userId, squadronId));
    m.setKind(OrgUnitKind.SQUADRON);
    return m;
  }

  @Test
  void findDirectMembershipOrgUnitIds_returnsEveryKindWithoutCascade() {
    UUID staffelId = UUID.randomUUID();
    UUID skId = UUID.randomUUID();
    UUID bereichId = UUID.randomUUID();
    UUID olId = UUID.randomUUID();
    when(membershipRepository.findAllByIdUserId(userId))
        .thenReturn(
            List.of(
                membershipRow(userId, staffelId, OrgUnitKind.SQUADRON),
                membershipRow(userId, skId, OrgUnitKind.SPECIAL_COMMAND),
                membershipRow(userId, bereichId, OrgUnitKind.BEREICH),
                membershipRow(userId, olId, OrgUnitKind.ORGANISATIONSLEITUNG)));

    Set<UUID> ids = queryService.findDirectMembershipOrgUnitIds(userId);

    // Kind-agnostic: Bereich and OL ids are included, and no cascade expansion is applied.
    assertEquals(Set.of(staffelId, skId, bereichId, olId), ids);
  }

  @Test
  void findDirectMembershipOrgUnitIds_noMemberships_returnsEmpty() {
    when(membershipRepository.findAllByIdUserId(userId)).thenReturn(List.of());

    assertTrue(queryService.findDirectMembershipOrgUnitIds(userId).isEmpty());
  }

  /** Builds a membership row of the given kind pointing the user at the given org unit. */
  private static OrgUnitMembership membershipRow(UUID userId, UUID orgUnitId, OrgUnitKind kind) {
    OrgUnitMembership m = new OrgUnitMembership();
    m.setId(new OrgUnitMembershipId(userId, orgUnitId));
    m.setKind(kind);
    return m;
  }

  /** Builds a squadron fixture with the given id and name. */
  private static Squadron squadron(UUID id, String name) {
    Squadron s = new Squadron();
    s.setId(id);
    s.setName(name);
    return s;
  }

  // --- listMembers ----------------------------------------------------------

  @Test
  void listMembers_existingSc_returnsMembers() {
    OrgUnitMembership m = new OrgUnitMembership();
    when(specialCommandService.getSpecialCommandById(scId)).thenReturn(sc);
    when(membershipRepository.findAllByIdOrgUnitId(scId)).thenReturn(List.of(m));

    List<OrgUnitMembership> result = queryService.listMembers(scId);

    assertEquals(1, result.size());
    assertSame(m, result.get(0));
  }

  @Test
  void listMembers_unknownSc_throwsNotFound() {
    when(specialCommandService.getSpecialCommandById(scId))
        .thenThrow(new NotFoundException("SpecialCommand not found"));

    assertThrows(NotFoundException.class, () -> queryService.listMembers(scId));
    verify(membershipRepository, never()).findAllByIdOrgUnitId(any());
  }

  @Test
  void listMemberDtos_mapsRowsToWireShape() {
    OrgUnitMembership m = new OrgUnitMembership();
    m.setId(id);
    m.setKind(OrgUnitKind.SPECIAL_COMMAND);
    m.setUser(user);
    m.setVersion(4L);
    when(specialCommandService.getSpecialCommandById(scId)).thenReturn(sc);
    when(membershipRepository.findAllByIdOrgUnitId(scId)).thenReturn(List.of(m));

    List<OrgUnitMembershipDto> dtos = queryService.listMemberDtos(scId);

    assertEquals(1, dtos.size());
    OrgUnitMembershipDto dto = dtos.get(0);
    assertEquals(userId, dto.userId());
    assertEquals(scId, dto.orgUnitId());
    assertEquals("Alice", dto.userDisplayName());
    assertEquals(4L, dto.version().longValue());
  }

  @Test
  void findAllMembershipDtosForUser_mapsEveryMembershipStaffelFirst() {
    UUID squadronId = UUID.randomUUID();
    OrgUnitMembership staffel = squadronMember(squadronId, MembershipRole.MEMBER);
    staffel.setUser(user);
    OrgUnitMembership sk = new OrgUnitMembership();
    sk.setId(id);
    sk.setKind(OrgUnitKind.SPECIAL_COMMAND);
    sk.setUser(user);
    when(membershipRepository.findAllByIdUserId(userId)).thenReturn(List.of(sk, staffel));

    List<OrgUnitMembershipDto> dtos = queryService.findAllMembershipDtosForUser(userId);

    assertEquals(2, dtos.size());
    assertEquals(OrgUnitKind.SQUADRON, dtos.get(0).kind(), "Staffel sorts before the SK");
    assertEquals(OrgUnitKind.SPECIAL_COMMAND, dtos.get(1).kind());
    assertEquals("Alice", dtos.get(0).userDisplayName());
  }

  // --- listOptionsForUser ---------------------------------------------------

  @Test
  void listOptionsForUser_noMemberships_returnsEmptyListWithoutOrgUnitLookup() {
    when(membershipRepository.findAllByIdUserId(userId)).thenReturn(List.of());

    List<OrgUnitMembershipOptionDto> options = queryService.listOptionsForUser(userId);

    assertTrue(options.isEmpty(), "no memberships → empty option list");
    verify(squadronRepository, never()).findById(any());
    verify(specialCommandRepository, never()).findById(any());
  }

  @Test
  void listOptionsForUser_singleStaffel_returnsOneOption() {
    UUID squadronId = UUID.randomUUID();
    OrgUnitMembership row = new OrgUnitMembership();
    row.setId(new OrgUnitMembershipId(userId, squadronId));
    row.setKind(OrgUnitKind.SQUADRON);
    when(membershipRepository.findAllByIdUserId(userId)).thenReturn(List.of(row));
    Squadron staffel = new Squadron();
    staffel.setId(squadronId);
    staffel.setName("IRIDIUM");
    staffel.setShorthand("IRI");
    when(squadronRepository.findById(squadronId)).thenReturn(Optional.of(staffel));

    List<OrgUnitMembershipOptionDto> options = queryService.listOptionsForUser(userId);

    assertEquals(1, options.size());
    OrgUnitMembershipOptionDto only = options.getFirst();
    assertEquals(squadronId, only.orgUnitId());
    assertEquals("IRIDIUM", only.orgUnitName());
    assertEquals("IRI", only.orgUnitShorthand());
    assertEquals(OrgUnitKind.SQUADRON, only.kind());
  }

  @Test
  void listOptionsForUser_mixedKinds_sortsStaffelFirstThenSkAlphabetical() {
    UUID staffelId = UUID.randomUUID();
    UUID skBravoId = UUID.randomUUID();
    UUID skAlphaId = UUID.randomUUID();

    OrgUnitMembership rowSkBravo = new OrgUnitMembership();
    rowSkBravo.setId(new OrgUnitMembershipId(userId, skBravoId));
    rowSkBravo.setKind(OrgUnitKind.SPECIAL_COMMAND);
    OrgUnitMembership rowStaffel = new OrgUnitMembership();
    rowStaffel.setId(new OrgUnitMembershipId(userId, staffelId));
    rowStaffel.setKind(OrgUnitKind.SQUADRON);
    OrgUnitMembership rowSkAlpha = new OrgUnitMembership();
    rowSkAlpha.setId(new OrgUnitMembershipId(userId, skAlphaId));
    rowSkAlpha.setKind(OrgUnitKind.SPECIAL_COMMAND);
    when(membershipRepository.findAllByIdUserId(userId))
        .thenReturn(List.of(rowSkBravo, rowStaffel, rowSkAlpha));

    Squadron staffel = new Squadron();
    staffel.setId(staffelId);
    staffel.setName("IRIDIUM");
    staffel.setShorthand("IRI");
    when(squadronRepository.findById(staffelId)).thenReturn(Optional.of(staffel));

    SpecialCommand skBravo = new SpecialCommand();
    skBravo.setId(skBravoId);
    skBravo.setName("Bravo");
    skBravo.setShorthand("BRV");
    when(specialCommandRepository.findById(skBravoId)).thenReturn(Optional.of(skBravo));

    SpecialCommand skAlpha = new SpecialCommand();
    skAlpha.setId(skAlphaId);
    skAlpha.setName("Alpha");
    skAlpha.setShorthand("ALF");
    when(specialCommandRepository.findById(skAlphaId)).thenReturn(Optional.of(skAlpha));

    List<OrgUnitMembershipOptionDto> options = queryService.listOptionsForUser(userId);

    assertEquals(3, options.size());
    assertEquals(OrgUnitKind.SQUADRON, options.get(0).kind());
    assertEquals("IRIDIUM", options.get(0).orgUnitName());
    assertEquals(OrgUnitKind.SPECIAL_COMMAND, options.get(1).kind());
    assertEquals("Alpha", options.get(1).orgUnitName());
    assertEquals(OrgUnitKind.SPECIAL_COMMAND, options.get(2).kind());
    assertEquals("Bravo", options.get(2).orgUnitName());
  }

  @Test
  void listOptionsForUser_orphanedRow_silentlyDropsTheMembership() {
    UUID orgUnitId = UUID.randomUUID();
    OrgUnitMembership row = new OrgUnitMembership();
    row.setId(new OrgUnitMembershipId(userId, orgUnitId));
    row.setKind(OrgUnitKind.SQUADRON);
    when(membershipRepository.findAllByIdUserId(userId)).thenReturn(List.of(row));
    when(squadronRepository.findById(orgUnitId)).thenReturn(Optional.empty());

    List<OrgUnitMembershipOptionDto> options = queryService.listOptionsForUser(userId);

    assertTrue(
        options.isEmpty(),
        "membership row pointing at a deleted Squadron must not crash the picker");
  }

  // --- listPickerOptionsWithDescendants (epic #692 Phase 5 drill-down) -------

  @Test
  void listPickerOptionsWithDescendants_bereichLeader_includesBereichAndDescendantsTopDown() {
    UUID bereichId = UUID.randomUUID();
    UUID staffelId = UUID.randomUUID();
    OrgUnitMembership bereichRow = new OrgUnitMembership();
    bereichRow.setId(new OrgUnitMembershipId(userId, bereichId));
    bereichRow.setKind(OrgUnitKind.BEREICH);
    bereichRow.setRole(MembershipRole.BEREICHSLEITER);
    when(membershipRepository.findAllByIdUserId(userId)).thenReturn(List.of(bereichRow));
    when(orgUnitCascadeService.expandWithDescendants(any()))
        .thenReturn(new java.util.LinkedHashSet<>(List.of(bereichId, staffelId)));
    Bereich bereich = new Bereich();
    bereich.setId(bereichId);
    bereich.setName("Profit");
    bereich.setShorthand("PRF");
    Squadron staffel = new Squadron();
    staffel.setId(staffelId);
    staffel.setName("Alpha");
    staffel.setShorthand("ALF");
    // Return unordered to prove the service applies the top-down hierarchy sort itself.
    when(orgUnitRepository.findAllById(any())).thenReturn(List.of(staffel, bereich));

    List<OrgUnitMembershipOptionDto> options =
        queryService.listPickerOptionsWithDescendants(userId);

    assertEquals(2, options.size());
    // Top-down hierarchy order: Bereich (1) before Staffel (2).
    assertEquals(OrgUnitKind.BEREICH, options.get(0).kind());
    assertEquals(bereichId, options.get(0).orgUnitId());
    assertEquals(OrgUnitKind.SQUADRON, options.get(1).kind());
    assertEquals(staffelId, options.get(1).orgUnitId());
  }

  @Test
  void listPickerOptionsWithDescendants_noMemberships_returnsEmptyWithoutCascade() {
    when(membershipRepository.findAllByIdUserId(userId)).thenReturn(List.of());

    assertTrue(queryService.listPickerOptionsWithDescendants(userId).isEmpty());
    verify(orgUnitCascadeService, never()).expandWithDescendants(any());
  }

  // --- listAllActiveOptions (R5.d.c Job Order picker) -----------------------

  @Test
  void listAllActiveOptions_emptyCatalog_returnsEmptyList() {
    when(squadronRepository.findAllByActiveTrue()).thenReturn(List.of());
    when(specialCommandRepository.findAllByActiveTrue()).thenReturn(List.of());

    assertTrue(queryService.listAllActiveOptions().isEmpty());
  }

  @Test
  void listAllActiveOptions_mixed_sortsStaffelFirstThenSkAlphabetical() {
    Squadron iri = new Squadron();
    iri.setId(UUID.randomUUID());
    iri.setName("IRIDIUM");
    iri.setShorthand("IRI");
    Squadron khg = new Squadron();
    khg.setId(UUID.randomUUID());
    khg.setName("KHG");
    khg.setShorthand("KHG");
    when(squadronRepository.findAllByActiveTrue()).thenReturn(List.of(iri, khg));

    SpecialCommand bravo = new SpecialCommand();
    bravo.setId(UUID.randomUUID());
    bravo.setName("Bravo");
    bravo.setShorthand("BRV");
    SpecialCommand alpha = new SpecialCommand();
    alpha.setId(UUID.randomUUID());
    alpha.setName("Alpha");
    alpha.setShorthand("ALF");
    when(specialCommandRepository.findAllByActiveTrue()).thenReturn(List.of(bravo, alpha));

    List<OrgUnitMembershipOptionDto> result = queryService.listAllActiveOptions();

    assertEquals(4, result.size());
    // Staffeln first (alphabetical), then SKs (alphabetical).
    assertEquals(OrgUnitKind.SQUADRON, result.get(0).kind());
    assertEquals("IRIDIUM", result.get(0).orgUnitName());
    assertEquals(OrgUnitKind.SQUADRON, result.get(1).kind());
    assertEquals("KHG", result.get(1).orgUnitName());
    assertEquals(OrgUnitKind.SPECIAL_COMMAND, result.get(2).kind());
    assertEquals("Alpha", result.get(2).orgUnitName());
    assertEquals(OrgUnitKind.SPECIAL_COMMAND, result.get(3).kind());
    assertEquals("Bravo", result.get(3).orgUnitName());
  }

  @Test
  void listAllActiveOptions_carriesProfitEligibleFlagPerOrgUnit() {
    // The flag lets the anonymous-reachable create form fill both pickers from one fetch:
    // requesting
    // = all options, responsible = the profit-eligible subset. Pin that each option mirrors its own
    // org unit's flag (a profit Squadron and a non-profit SK here).
    Squadron profitSquadron = new Squadron();
    profitSquadron.setId(UUID.randomUUID());
    profitSquadron.setName("IRIDIUM");
    profitSquadron.setShorthand("IRI");
    profitSquadron.setProfitEligible(true);
    when(squadronRepository.findAllByActiveTrue()).thenReturn(List.of(profitSquadron));

    SpecialCommand nonProfitSk = new SpecialCommand();
    nonProfitSk.setId(UUID.randomUUID());
    nonProfitSk.setName("Alpha");
    nonProfitSk.setShorthand("ALF");
    nonProfitSk.setProfitEligible(false);
    when(specialCommandRepository.findAllByActiveTrue()).thenReturn(List.of(nonProfitSk));

    List<OrgUnitMembershipOptionDto> result = queryService.listAllActiveOptions();

    assertEquals(2, result.size());
    assertEquals(OrgUnitKind.SQUADRON, result.get(0).kind());
    assertEquals(Boolean.TRUE, result.get(0).isProfitEligible());
    assertEquals(OrgUnitKind.SPECIAL_COMMAND, result.get(1).kind());
    assertEquals(Boolean.FALSE, result.get(1).isProfitEligible());
  }

  @Test
  void listDirectMembershipOptions_includesBereichNotJustStaffelAndSk() {
    // REQ-BANK-044: unlike listOptionsForUser (Staffel/SK only), the bank counterparty picker must
    // surface a direct Bereich (or OL) membership too. Ordered top-down by kind (Bereich before
    // Staffel), so the first option is the user's primary unit.
    UUID squadronId = UUID.randomUUID();
    UUID bereichId = UUID.randomUUID();
    OrgUnitMembership squadronRow = new OrgUnitMembership();
    squadronRow.setId(new OrgUnitMembershipId(userId, squadronId));
    OrgUnitMembership bereichRow = new OrgUnitMembership();
    bereichRow.setId(new OrgUnitMembershipId(userId, bereichId));
    when(membershipRepository.findAllByIdUserId(userId))
        .thenReturn(List.of(squadronRow, bereichRow));
    Squadron squadron = new Squadron();
    squadron.setId(squadronId);
    squadron.setName("Staffel Rot");
    squadron.setShorthand("ROT");
    Bereich bereich = new Bereich();
    bereich.setId(bereichId);
    bereich.setName("Bereich Logistik");
    bereich.setShorthand("LOG");
    when(orgUnitRepository.findAllById(any())).thenReturn(List.of(squadron, bereich));

    List<OrgUnitMembershipOptionDto> options = queryService.listDirectMembershipOptions(userId);

    assertEquals(2, options.size());
    assertEquals(OrgUnitKind.BEREICH, options.get(0).kind(), "Bereich sorts before Staffel");
    assertEquals(bereichId, options.get(0).orgUnitId());
    assertEquals(OrgUnitKind.SQUADRON, options.get(1).kind());
  }

  @Test
  void listDirectMembershipOptions_surfacesAllFourKindsIncludingOl() {
    // #1328 (mirrors REQ-BANK-044): the inventory Umbuchen owning-org-unit picker requests
    // ?allKinds=true, which routes here. Assert the option/wire path surfaces a direct
    // Organisationsleitung (OL) membership — not just Bereich — and that all four kinds sort
    // top-down (OL -> Bereich -> Staffel -> SK), so the first option is the owner's primary unit.
    UUID olId = UUID.randomUUID();
    UUID bereichId = UUID.randomUUID();
    UUID squadronId = UUID.randomUUID();
    UUID skId = UUID.randomUUID();
    OrgUnitMembership olRow = new OrgUnitMembership();
    olRow.setId(new OrgUnitMembershipId(userId, olId));
    OrgUnitMembership bereichRow = new OrgUnitMembership();
    bereichRow.setId(new OrgUnitMembershipId(userId, bereichId));
    OrgUnitMembership squadronRow = new OrgUnitMembership();
    squadronRow.setId(new OrgUnitMembershipId(userId, squadronId));
    OrgUnitMembership skRow = new OrgUnitMembership();
    skRow.setId(new OrgUnitMembershipId(userId, skId));
    when(membershipRepository.findAllByIdUserId(userId))
        .thenReturn(List.of(olRow, bereichRow, squadronRow, skRow));
    Organisationsleitung ol = new Organisationsleitung();
    ol.setId(olId);
    ol.setName("Organisationsleitung");
    ol.setShorthand("OL");
    Bereich bereich = new Bereich();
    bereich.setId(bereichId);
    bereich.setName("Bereich Logistik");
    bereich.setShorthand("LOG");
    Squadron squadron = new Squadron();
    squadron.setId(squadronId);
    squadron.setName("Staffel Rot");
    squadron.setShorthand("ROT");
    SpecialCommand sk = new SpecialCommand();
    sk.setId(skId);
    sk.setName("Spezialkommando Alpha");
    sk.setShorthand("ALF");
    // Return in an unsorted order to prove the service imposes the top-down kind order.
    when(orgUnitRepository.findAllById(any())).thenReturn(List.of(squadron, sk, ol, bereich));

    List<OrgUnitMembershipOptionDto> options = queryService.listDirectMembershipOptions(userId);

    assertEquals(4, options.size());
    assertEquals(
        OrgUnitKind.ORGANISATIONSLEITUNG, options.get(0).kind(), "OL sorts first (top-down)");
    assertEquals(olId, options.get(0).orgUnitId());
    assertEquals(OrgUnitKind.BEREICH, options.get(1).kind());
    assertEquals(OrgUnitKind.SQUADRON, options.get(2).kind());
    assertEquals(OrgUnitKind.SPECIAL_COMMAND, options.get(3).kind());
  }

  @Test
  void findPrimaryDirectMembershipOrgUnitId_returnsTopOfKindOrder_orEmpty() {
    // REQ-BANK-044: the requester's recorded org unit at request confirmation is the deterministic
    // primary — the first by the top-down kind order (here the Bereich over the Staffel).
    UUID squadronId = UUID.randomUUID();
    UUID bereichId = UUID.randomUUID();
    OrgUnitMembership squadronRow = new OrgUnitMembership();
    squadronRow.setId(new OrgUnitMembershipId(userId, squadronId));
    OrgUnitMembership bereichRow = new OrgUnitMembership();
    bereichRow.setId(new OrgUnitMembershipId(userId, bereichId));
    when(membershipRepository.findAllByIdUserId(userId))
        .thenReturn(List.of(squadronRow, bereichRow));
    Squadron squadron = new Squadron();
    squadron.setId(squadronId);
    squadron.setName("Staffel Rot");
    Bereich bereich = new Bereich();
    bereich.setId(bereichId);
    bereich.setName("Bereich Logistik");
    when(orgUnitRepository.findAllById(any())).thenReturn(List.of(squadron, bereich));

    assertEquals(Optional.of(bereichId), queryService.findPrimaryDirectMembershipOrgUnitId(userId));
  }

  @Test
  void findPrimaryDirectMembershipOrgUnitId_noMembership_returnsEmpty() {
    when(membershipRepository.findAllByIdUserId(userId)).thenReturn(List.of());

    assertEquals(Optional.empty(), queryService.findPrimaryDirectMembershipOrgUnitId(userId));
  }

  // --- assign/remove squadron rank (epic #800 Phase 3) ----------------------

  /** A Staffel membership row for {@link #userId} on the given squadron with the given rank. */
  private OrgUnitMembership squadronMember(UUID squadronId, MembershipRole role) {
    OrgUnitMembership m = new OrgUnitMembership();
    m.setId(new OrgUnitMembershipId(userId, squadronId));
    m.setKind(OrgUnitKind.SQUADRON);
    m.setRole(role);
    m.setVersion(0L);
    return m;
  }
}
