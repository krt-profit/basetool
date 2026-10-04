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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.mapper.KommandoGroupMapper;
import de.greluc.krt.profit.basetool.backend.mapper.KommandoGroupMapperImpl;
import de.greluc.krt.profit.basetool.backend.model.Bereich;
import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.Organisationsleitung;
import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.LeitungUnitDto;
import de.greluc.krt.profit.basetool.backend.model.dto.LeitungViewDto;
import de.greluc.krt.profit.basetool.backend.orgunit.api.BereichLeadershipRole;
import de.greluc.krt.profit.basetool.backend.repository.KommandoGroupRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

/**
 * Unit tests for {@link LeitungViewService} (REQ-ROLE-004): the view shows the units the caller
 * leads and those below them, everything for an admin and nothing for a plain member, while the
 * capability flags follow the delegated verdicts.
 */
@ExtendWith(MockitoExtension.class)
class LeitungViewServiceTest {

  @Mock private AuthHelperService authHelperService;
  @Mock private OrgRoleManagementSecurityService roleSecurity;
  @Mock private SpecialCommandSecurityService specialCommandSecurity;
  @Mock private OrgUnitRepository orgUnitRepository;
  @Mock private OrgUnitMembershipRepository membershipRepository;
  @Mock private KommandoGroupRepository kommandoGroupRepository;
  @Mock private OrgUnitCascadeService cascadeService;

  @Spy private KommandoGroupMapper kommandoGroupMapper = new KommandoGroupMapperImpl();

  @InjectMocks private LeitungViewService service;

  private Authentication auth;
  private final UUID callerId = UUID.randomUUID();
  private final UUID olId = UUID.randomUUID();
  private final UUID bereichId = UUID.randomUUID();
  private final UUID otherBereichId = UUID.randomUUID();
  private final UUID squadronId = UUID.randomUUID();
  private final UUID otherSquadronId = UUID.randomUUID();
  private final UUID skId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    auth = org.mockito.Mockito.mock(Authentication.class);
  }

  private OrgUnit ol() {
    Organisationsleitung o = new Organisationsleitung();
    o.setId(olId);
    o.setName("Organisationsleitung");
    o.setShorthand("OL");
    return o;
  }

  private OrgUnit bereich(UUID id, String name) {
    Bereich b = new Bereich();
    b.setId(id);
    b.setName(name);
    b.setShorthand(name.substring(0, 3).toUpperCase(java.util.Locale.ROOT));
    return b;
  }

  private OrgUnit squadron(UUID id, String name) {
    Squadron s = new Squadron();
    s.setId(id);
    s.setName(name);
    s.setShorthand(name.substring(0, 3).toUpperCase(java.util.Locale.ROOT));
    return s;
  }

  private OrgUnit specialCommand() {
    SpecialCommand sc = new SpecialCommand();
    sc.setId(skId);
    sc.setName("Alpha SK");
    sc.setShorthand("ASK");
    return sc;
  }

  private OrgUnitMembership member(UUID userId, String name, MembershipRole role) {
    User u = new User();
    u.setId(userId);
    u.setUsername(name);
    OrgUnitMembership m = new OrgUnitMembership();
    m.setId(new OrgUnitMembershipId(userId, squadronId));
    m.setUser(u);
    m.setRole(role);
    m.setVersion(0L);
    return m;
  }

  private OrgUnitMembership seat(UUID orgUnitId, MembershipRole role) {
    OrgUnitMembership m = new OrgUnitMembership();
    m.setId(new OrgUnitMembershipId(callerId, orgUnitId));
    m.setRole(role);
    m.setVersion(0L);
    return m;
  }

  private void allUnitsExist() {
    when(orgUnitRepository.findActiveOrganisationsleitung()).thenReturn(List.of(ol()));
    when(orgUnitRepository.findActiveBereiche())
        .thenReturn(List.of(bereich(bereichId, "Profit"), bereich(otherBereichId, "Security")));
    when(orgUnitRepository.findActiveSquadronsAndSpecialCommands())
        .thenReturn(
            List.of(
                squadron(squadronId, "Mamba"),
                squadron(otherSquadronId, "Viper"),
                specialCommand()));
  }

  private void callerHolds(Set<UUID> cascade, OrgUnitMembership... seats) {
    when(authHelperService.isAdmin()).thenReturn(false);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(callerId));
    when(membershipRepository.findAllByIdUserId(callerId)).thenReturn(List.of(seats));
    when(cascadeService.cascadedOfficerReach(anyList())).thenReturn(new LinkedHashSet<>(cascade));
  }

  @Test
  void admin_seesEveryTierWithoutConsultingTheDelegatedAuthoriser() {
    when(authHelperService.isAdmin()).thenReturn(true);
    allUnitsExist();
    when(membershipRepository.findAllByIdOrgUnitId(any())).thenReturn(List.of());
    when(kommandoGroupRepository.findBySquadronIdOrderBySortIndexAsc(any())).thenReturn(List.of());

    LeitungViewDto view = service.buildView(auth);

    assertTrue(view.admin());
    assertEquals(1, view.organisationsleitungen().size());
    assertTrue(view.organisationsleitungen().getFirst().canAppointLead());
    assertEquals(2, view.bereiche().size());
    assertTrue(view.bereiche().getFirst().canAppointLead());
    assertTrue(view.bereiche().getFirst().canManageRoster());
    assertEquals(2, view.squadrons().size());
    assertTrue(view.squadrons().getFirst().canManageRoster());
    assertEquals(1, view.specialCommands().size());
    assertTrue(view.specialCommands().getFirst().canAppointLead());
    assertTrue(view.specialCommands().getFirst().canManageRoster());
    verifyNoInteractions(roleSecurity);
    verifyNoInteractions(specialCommandSecurity);
    verifyNoInteractions(cascadeService);
  }

  @Test
  void olMember_seesEveryUnitButMayOnlyAppointBereichsleiter() {
    allUnitsExist();
    callerHolds(
        Set.of(olId, bereichId, otherBereichId, squadronId, otherSquadronId, skId),
        seat(olId, MembershipRole.OL_MEMBER));
    when(roleSecurity.canAppointBereichRole(any(), any(), any())).thenReturn(false);
    when(roleSecurity.canAppointBereichRole(bereichId, BereichLeadershipRole.LEITER, auth))
        .thenReturn(true);
    when(roleSecurity.canAppointBereichRole(otherBereichId, BereichLeadershipRole.LEITER, auth))
        .thenReturn(true);
    when(membershipRepository.findAllByIdOrgUnitId(any())).thenReturn(List.of());
    when(kommandoGroupRepository.findBySquadronIdOrderBySortIndexAsc(any())).thenReturn(List.of());

    LeitungViewDto view = service.buildView(auth);

    assertFalse(view.admin());
    assertEquals(1, view.organisationsleitungen().size());
    assertFalse(view.organisationsleitungen().getFirst().canAppointLead(), "OL roster is admin's");
    assertEquals(2, view.bereiche().size());
    LeitungUnitDto b = view.bereiche().getFirst();
    assertTrue(b.canAppointLead());
    assertFalse(b.canManageRoster());
    assertEquals(2, view.squadrons().size());
    assertTrue(
        view.squadrons().stream().noneMatch(s -> s.canAppointLead() || s.canManageRoster()),
        "an OL member sees every Staffel read-only");
    assertEquals(1, view.specialCommands().size());
    assertFalse(view.specialCommands().getFirst().canAppointLead());
    assertFalse(view.specialCommands().getFirst().canManageRoster());
  }

  @Test
  void staffelleiter_seesOnlyOwnSquadronWithRosterManagement() {
    allUnitsExist();
    callerHolds(Set.of(), seat(squadronId, MembershipRole.STAFFELLEITER));
    when(roleSecurity.canAssignSquadronRank(squadronId, MembershipRole.STAFFELLEITER, auth))
        .thenReturn(false);
    when(roleSecurity.canManageKommandoGroups(squadronId, auth)).thenReturn(true);
    UUID plainUser = UUID.randomUUID();
    when(membershipRepository.findAllByIdOrgUnitId(squadronId))
        .thenReturn(
            List.of(
                member(plainUser, "zulu", MembershipRole.MEMBER),
                member(callerId, "alpha", MembershipRole.STAFFELLEITER)));
    when(kommandoGroupRepository.findBySquadronIdOrderBySortIndexAsc(squadronId))
        .thenReturn(List.of());

    LeitungViewDto view = service.buildView(auth);

    assertTrue(view.organisationsleitungen().isEmpty());
    assertTrue(view.bereiche().isEmpty());
    assertTrue(view.specialCommands().isEmpty());
    assertEquals(1, view.squadrons().size());
    LeitungUnitDto sq = view.squadrons().getFirst();
    assertEquals(squadronId, sq.id());
    assertTrue(sq.canManageRoster());
    assertFalse(sq.canAppointLead());
    assertEquals(2, sq.members().size());
    assertEquals(MembershipRole.STAFFELLEITER, sq.members().getFirst().role());
    assertTrue(sq.members().getFirst().self(), "the caller's own seat is flagged");
    assertFalse(sq.members().get(1).self());
  }

  @Test
  void bereichsleiter_seesOwnBereichWithItsSquadronsAndSpecialCommands() {
    allUnitsExist();
    callerHolds(
        Set.of(bereichId, squadronId, skId), seat(bereichId, MembershipRole.BEREICHSLEITER));
    when(roleSecurity.canAppointBereichRole(bereichId, BereichLeadershipRole.LEITER, auth))
        .thenReturn(false);
    when(roleSecurity.canAppointBereichRole(bereichId, BereichLeadershipRole.KOORDINATOR, auth))
        .thenReturn(true);
    when(roleSecurity.canAssignSquadronRank(squadronId, MembershipRole.STAFFELLEITER, auth))
        .thenReturn(true);
    when(roleSecurity.canManageKommandoGroups(squadronId, auth)).thenReturn(false);
    when(roleSecurity.canAppointSkLead(skId, auth)).thenReturn(true);
    when(specialCommandSecurity.canManageMembers(skId, auth)).thenReturn(false);
    when(membershipRepository.findAllByIdOrgUnitId(any())).thenReturn(List.of());
    when(kommandoGroupRepository.findBySquadronIdOrderBySortIndexAsc(squadronId))
        .thenReturn(List.of());

    LeitungViewDto view = service.buildView(auth);

    assertTrue(view.organisationsleitungen().isEmpty());
    assertEquals(1, view.bereiche().size());
    assertEquals(bereichId, view.bereiche().getFirst().id());
    assertTrue(view.bereiche().getFirst().canManageRoster());
    assertFalse(view.bereiche().getFirst().canAppointLead());
    assertEquals(1, view.squadrons().size());
    assertEquals(squadronId, view.squadrons().getFirst().id());
    assertTrue(view.squadrons().getFirst().canAppointLead());
    assertEquals(1, view.specialCommands().size());
    LeitungUnitDto sk = view.specialCommands().getFirst();
    assertTrue(sk.canAppointLead());
    assertFalse(sk.canManageRoster(), "the roster cap follows canManageMembers, not the Bereich");
  }

  @Test
  void skLead_seesOwnSpecialCommandWithRosterManagementButNoLeadAppointment() {
    allUnitsExist();
    callerHolds(Set.of(), seat(skId, MembershipRole.SK_LEAD));
    when(roleSecurity.canAppointSkLead(skId, auth)).thenReturn(false);
    when(specialCommandSecurity.canManageMembers(skId, auth)).thenReturn(true);
    when(membershipRepository.findAllByIdOrgUnitId(skId)).thenReturn(List.of());

    LeitungViewDto view = service.buildView(auth);

    assertTrue(view.bereiche().isEmpty());
    assertTrue(view.squadrons().isEmpty());
    assertEquals(1, view.specialCommands().size());
    LeitungUnitDto sk = view.specialCommands().getFirst();
    assertEquals(skId, sk.id());
    assertFalse(sk.canAppointLead(), "an SK lead never appoints the SK lead");
    assertTrue(sk.canManageRoster(), "an SK lead manages their own SK's members");
  }

  @Test
  void plainMember_getsAnEmptyViewWithoutConsultingTheVerdicts() {
    allUnitsExist();
    callerHolds(
        Set.of(), seat(squadronId, MembershipRole.MEMBER), seat(skId, MembershipRole.MEMBER));

    LeitungViewDto view = service.buildView(auth);

    assertTrue(view.organisationsleitungen().isEmpty());
    assertTrue(view.bereiche().isEmpty());
    assertTrue(view.squadrons().isEmpty());
    assertTrue(view.specialCommands().isEmpty());
    verifyNoInteractions(roleSecurity);
    verifyNoInteractions(specialCommandSecurity);
  }

  @Test
  void unauthenticatedCaller_getsAnEmptyView() {
    allUnitsExist();
    when(authHelperService.isAdmin()).thenReturn(false);
    when(authHelperService.currentUserId()).thenReturn(Optional.empty());

    LeitungViewDto view = service.buildView(auth);

    assertTrue(view.organisationsleitungen().isEmpty());
    assertTrue(view.bereiche().isEmpty());
    assertTrue(view.squadrons().isEmpty());
    assertTrue(view.specialCommands().isEmpty());
  }
}
