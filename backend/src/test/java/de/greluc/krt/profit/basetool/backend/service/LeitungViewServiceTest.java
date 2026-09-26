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
import static org.mockito.Mockito.lenient;
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
import de.greluc.krt.profit.basetool.backend.model.dto.BereichLeadershipRole;
import de.greluc.krt.profit.basetool.backend.model.dto.LeitungUnitDto;
import de.greluc.krt.profit.basetool.backend.model.dto.LeitungViewDto;
import de.greluc.krt.profit.basetool.backend.repository.KommandoGroupRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import java.util.List;
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
 * Unit tests for {@link LeitungViewService} (REQ-ROLE-004): the delegated view returns exactly the
 * units the caller's tier may appoint into, everything for an admin and nothing for a plain member.
 */
@ExtendWith(MockitoExtension.class)
class LeitungViewServiceTest {

  @Mock private AuthHelperService authHelperService;
  @Mock private OrgRoleManagementSecurityService roleSecurity;
  @Mock private SpecialCommandSecurityService specialCommandSecurity;
  @Mock private OrgUnitRepository orgUnitRepository;
  @Mock private OrgUnitMembershipRepository membershipRepository;
  @Mock private KommandoGroupRepository kommandoGroupRepository;

  @Spy private KommandoGroupMapper kommandoGroupMapper = new KommandoGroupMapperImpl();

  @InjectMocks private LeitungViewService service;

  private Authentication auth;
  private final UUID olId = UUID.randomUUID();
  private final UUID bereichId = UUID.randomUUID();
  private final UUID squadronId = UUID.randomUUID();
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

  private OrgUnit bereich() {
    Bereich b = new Bereich();
    b.setId(bereichId);
    b.setName("Profit");
    b.setShorthand("PRF");
    return b;
  }

  private OrgUnit squadron() {
    Squadron s = new Squadron();
    s.setId(squadronId);
    s.setName("IRIDIUM");
    s.setShorthand("IRI");
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

  @Test
  void admin_seesEveryTierWithoutConsultingTheDelegatedAuthoriser() {
    when(authHelperService.isAdmin()).thenReturn(true);
    when(orgUnitRepository.findActiveOrganisationsleitung()).thenReturn(List.of(ol()));
    when(orgUnitRepository.findActiveBereiche()).thenReturn(List.of(bereich()));
    when(orgUnitRepository.findActiveSquadronsAndSpecialCommands())
        .thenReturn(List.of(squadron(), specialCommand()));
    when(membershipRepository.findAllByIdOrgUnitId(org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of());
    when(kommandoGroupRepository.findBySquadronIdOrderBySortIndexAsc(squadronId))
        .thenReturn(List.of());

    LeitungViewDto view = service.buildView(auth);

    assertTrue(view.admin());
    assertEquals(1, view.organisationsleitungen().size());
    assertTrue(view.organisationsleitungen().getFirst().canAppointLead());
    assertEquals(1, view.bereiche().size());
    assertTrue(view.bereiche().getFirst().canAppointLead());
    assertTrue(view.bereiche().getFirst().canManageRoster());
    assertEquals(1, view.squadrons().size());
    assertTrue(view.squadrons().getFirst().canManageRoster());
    assertEquals(1, view.specialCommands().size());
    assertTrue(view.specialCommands().getFirst().canAppointLead());
    assertTrue(view.specialCommands().getFirst().canManageRoster());
    verifyNoInteractions(roleSecurity);
    verifyNoInteractions(specialCommandSecurity);
  }

  @Test
  void pureOlMember_seesEveryBereichForLeadAppointmentOnly() {
    when(authHelperService.isAdmin()).thenReturn(false);
    when(orgUnitRepository.findActiveOrganisationsleitung()).thenReturn(List.of(ol()));
    when(orgUnitRepository.findActiveBereiche()).thenReturn(List.of(bereich()));
    when(orgUnitRepository.findActiveSquadronsAndSpecialCommands())
        .thenReturn(List.of(squadron(), specialCommand()));
    when(roleSecurity.canAppointBereichRole(bereichId, BereichLeadershipRole.LEITER, auth))
        .thenReturn(true);
    when(roleSecurity.canAppointBereichRole(bereichId, BereichLeadershipRole.KOORDINATOR, auth))
        .thenReturn(false);
    when(roleSecurity.canAssignSquadronRank(squadronId, MembershipRole.STAFFELLEITER, auth))
        .thenReturn(false);
    when(roleSecurity.canManageKommandoGroups(squadronId, auth)).thenReturn(false);
    when(roleSecurity.canAppointSkLead(skId, auth)).thenReturn(false);
    when(membershipRepository.findAllByIdOrgUnitId(bereichId)).thenReturn(List.of());

    LeitungViewDto view = service.buildView(auth);

    assertFalse(view.admin());
    assertTrue(view.organisationsleitungen().isEmpty(), "OL roster is admin-only");
    assertEquals(1, view.bereiche().size());
    LeitungUnitDto b = view.bereiche().getFirst();
    assertTrue(b.canAppointLead());
    assertFalse(b.canManageRoster());
    assertTrue(view.squadrons().isEmpty());
    assertTrue(view.specialCommands().isEmpty());
  }

  @Test
  void staffelleiter_seesOwnSquadronWithRosterManagement() {
    when(authHelperService.isAdmin()).thenReturn(false);
    when(orgUnitRepository.findActiveOrganisationsleitung()).thenReturn(List.of());
    when(orgUnitRepository.findActiveBereiche()).thenReturn(List.of());
    when(orgUnitRepository.findActiveSquadronsAndSpecialCommands()).thenReturn(List.of(squadron()));
    when(roleSecurity.canAssignSquadronRank(squadronId, MembershipRole.STAFFELLEITER, auth))
        .thenReturn(false);
    when(roleSecurity.canManageKommandoGroups(squadronId, auth)).thenReturn(true);
    UUID leadUser = UUID.randomUUID();
    UUID plainUser = UUID.randomUUID();
    when(membershipRepository.findAllByIdOrgUnitId(squadronId))
        .thenReturn(
            List.of(
                member(plainUser, "zulu", MembershipRole.MEMBER),
                member(leadUser, "alpha", MembershipRole.STAFFELLEITER)));
    when(kommandoGroupRepository.findBySquadronIdOrderBySortIndexAsc(squadronId))
        .thenReturn(List.of());

    LeitungViewDto view = service.buildView(auth);

    assertEquals(1, view.squadrons().size());
    LeitungUnitDto sq = view.squadrons().getFirst();
    assertTrue(sq.canManageRoster());
    assertFalse(sq.canAppointLead());
    assertEquals(2, sq.members().size());
    assertEquals(MembershipRole.STAFFELLEITER, sq.members().getFirst().role());
  }

  @Test
  void plainMember_getsAnEmptyView() {
    when(authHelperService.isAdmin()).thenReturn(false);
    when(orgUnitRepository.findActiveOrganisationsleitung()).thenReturn(List.of(ol()));
    when(orgUnitRepository.findActiveBereiche()).thenReturn(List.of(bereich()));
    when(orgUnitRepository.findActiveSquadronsAndSpecialCommands())
        .thenReturn(List.of(squadron(), specialCommand()));
    lenient()
        .when(roleSecurity.canAppointBereichRole(bereichId, BereichLeadershipRole.LEITER, auth))
        .thenReturn(false);
    lenient()
        .when(
            roleSecurity.canAppointBereichRole(bereichId, BereichLeadershipRole.KOORDINATOR, auth))
        .thenReturn(false);
    lenient()
        .when(roleSecurity.canAssignSquadronRank(squadronId, MembershipRole.STAFFELLEITER, auth))
        .thenReturn(false);
    lenient().when(roleSecurity.canManageKommandoGroups(squadronId, auth)).thenReturn(false);
    lenient().when(roleSecurity.canAppointSkLead(skId, auth)).thenReturn(false);

    LeitungViewDto view = service.buildView(auth);

    assertTrue(view.organisationsleitungen().isEmpty());
    assertTrue(view.bereiche().isEmpty());
    assertTrue(view.squadrons().isEmpty());
    assertTrue(view.specialCommands().isEmpty());
  }

  @Test
  void skLead_seesOwnSpecialCommandWithRosterManagementButNoLeadAppointment() {
    when(authHelperService.isAdmin()).thenReturn(false);
    when(orgUnitRepository.findActiveOrganisationsleitung()).thenReturn(List.of());
    when(orgUnitRepository.findActiveBereiche()).thenReturn(List.of());
    when(orgUnitRepository.findActiveSquadronsAndSpecialCommands())
        .thenReturn(List.of(specialCommand()));
    when(roleSecurity.canAppointSkLead(skId, auth)).thenReturn(false);
    when(specialCommandSecurity.canManageMembers(skId, auth)).thenReturn(true);
    when(membershipRepository.findAllByIdOrgUnitId(skId)).thenReturn(List.of());

    LeitungViewDto view = service.buildView(auth);

    assertEquals(1, view.specialCommands().size());
    LeitungUnitDto sk = view.specialCommands().getFirst();
    assertEquals(skId, sk.id());
    assertFalse(sk.canAppointLead(), "an SK lead never appoints the SK lead");
    assertTrue(sk.canManageRoster(), "an SK lead manages their own SK's members");
  }

  @Test
  void bereichsleiter_seesSpecialCommandForLeadAppointmentWithoutRosterManagement() {
    when(authHelperService.isAdmin()).thenReturn(false);
    when(orgUnitRepository.findActiveOrganisationsleitung()).thenReturn(List.of());
    when(orgUnitRepository.findActiveBereiche()).thenReturn(List.of());
    when(orgUnitRepository.findActiveSquadronsAndSpecialCommands())
        .thenReturn(List.of(specialCommand()));
    when(roleSecurity.canAppointSkLead(skId, auth)).thenReturn(true);
    when(specialCommandSecurity.canManageMembers(skId, auth)).thenReturn(false);
    when(membershipRepository.findAllByIdOrgUnitId(skId)).thenReturn(List.of());

    LeitungViewDto view = service.buildView(auth);

    assertEquals(1, view.specialCommands().size());
    LeitungUnitDto sk = view.specialCommands().getFirst();
    assertTrue(sk.canAppointLead());
    assertFalse(sk.canManageRoster(), "the roster cap follows canManageMembers, not the Bereich");
  }

  @Test
  void plainSkMember_doesNotSeeTheirSpecialCommand() {
    when(authHelperService.isAdmin()).thenReturn(false);
    when(orgUnitRepository.findActiveOrganisationsleitung()).thenReturn(List.of());
    when(orgUnitRepository.findActiveBereiche()).thenReturn(List.of());
    when(orgUnitRepository.findActiveSquadronsAndSpecialCommands())
        .thenReturn(List.of(specialCommand()));
    when(roleSecurity.canAppointSkLead(skId, auth)).thenReturn(false);
    when(specialCommandSecurity.canManageMembers(skId, auth)).thenReturn(false);

    LeitungViewDto view = service.buildView(auth);

    assertTrue(view.specialCommands().isEmpty());
  }
}
