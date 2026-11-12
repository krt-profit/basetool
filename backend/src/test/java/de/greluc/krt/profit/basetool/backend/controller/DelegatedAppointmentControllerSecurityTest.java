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

package de.greluc.krt.profit.basetool.backend.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.KommandoGroupDto;
import de.greluc.krt.profit.basetool.backend.orgunit.api.KommandoGroupService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipRole;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgRoleManagementSecurityService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipDto;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.scope.api.RequestScopeResolver;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MockMvc gate matrix for the delegated appointment surface (REQ-ROLE-004): verifies the
 * {@code @PreAuthorize} expressions bind to {@link OrgRoleManagementSecurityService}, forbidding a
 * non-leader and admitting an admin or an approved caller, and that a pin on another unit refuses
 * every write, an admin's included, while a pin on the target unit admits it (REQ-ORG-032).
 */
@SpringBootTest
class DelegatedAppointmentControllerSecurityTest {

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private OrgUnitMembershipRepository orgUnitMembershipRepository;
  private MockMvc mockMvc;

  @MockitoBean private OrgUnitMembershipService orgUnitMembershipService;
  @MockitoBean private KommandoGroupService kommandoGroupService;
  @MockitoBean private OrgRoleManagementSecurityService orgRoleManagementSecurityService;

  private final UUID squadronId = UUID.randomUUID();
  private final UUID bereichId = UUID.randomUUID();
  private final UUID targetUser = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /**
   * Seeds an active Staffel, which an admin may pin.
   *
   * @return the Staffel's id
   */
  private UUID seedStaffel() {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    Squadron staffel = new Squadron();
    staffel.setName("Pin-Staffel-" + tag);
    staffel.setShorthand("PS" + tag);
    return squadronRepository.save(staffel).getId();
  }

  /**
   * Seeds a Staffelleiter of the Staffel, whose pin on it is honoured while they hold {@code KRT
   * Member}.
   *
   * @param staffelId the Staffel
   * @return the Staffelleiter's account id
   */
  private UUID seedStaffelleiterOf(UUID staffelId) {
    User leader = new User();
    leader.setId(UUID.randomUUID());
    leader.setUsername("pin-leader-" + leader.getId());
    leader = userRepository.save(leader);
    OrgUnitMembership seat = new OrgUnitMembership();
    seat.setId(new OrgUnitMembershipId(leader.getId(), staffelId));
    seat.setUser(leader);
    seat.setRole(MembershipRole.STAFFELLEITER);
    seat.setJoinedAt(Instant.now());
    orgUnitMembershipRepository.save(seat);
    return leader.getId();
  }

  /**
   * A {@code KRT Member} caller with the given account id.
   *
   * @param userId the caller's account id
   * @return the request post-processor carrying the JWT
   */
  private static RequestPostProcessor krtMember(UUID userId) {
    return jwt()
        .jwt(j -> j.subject(userId.toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"));
  }

  private OrgUnitMembershipDto dtoStub() {
    return new OrgUnitMembershipDto(
        targetUser, null, squadronId, null, false, false, false, null, 1L);
  }

  private static RequestPostProcessor member(String... roles) {
    SimpleGrantedAuthority[] auths = new SimpleGrantedAuthority[roles.length];
    for (int i = 0; i < roles.length; i++) {
      auths[i] = new SimpleGrantedAuthority(roles[i]);
    }
    return jwt().jwt(j -> j.subject(UUID.randomUUID().toString())).authorities(auths);
  }

  @Test
  void assignSquadronRank_nonLeader_isForbidden() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/squadrons/{s}/ranks/{u}", squadronId, targetUser)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"KOMMANDOLEITER\",\"version\":0}")
                .with(member("ROLE_OFFICER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void assignSquadronRank_admin_isAllowed() throws Exception {
    when(orgUnitMembershipService.assignSquadronRankDto(any(), any(), any(), any(), any()))
        .thenReturn(dtoStub());
    mockMvc
        .perform(
            put("/api/v1/squadrons/{s}/ranks/{u}", squadronId, targetUser)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"KOMMANDOLEITER\",\"version\":0}")
                .with(member("ROLE_ADMIN")))
        .andExpect(status().isOk());
  }

  @Test
  void assignSquadronRank_delegatedStaffelleiter_isAllowed() throws Exception {
    when(orgRoleManagementSecurityService.canAssignSquadronRank(any(), any(), any()))
        .thenReturn(true);
    when(orgRoleManagementSecurityService.targetsAnotherUser(any(), any())).thenReturn(true);
    when(orgUnitMembershipService.assignSquadronRankDto(any(), any(), any(), any(), any()))
        .thenReturn(dtoStub());
    mockMvc
        .perform(
            put("/api/v1/squadrons/{s}/ranks/{u}", squadronId, targetUser)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"KOMMANDOLEITER\",\"version\":0}")
                .with(member("ROLE_OFFICER")))
        .andExpect(status().isOk());
  }

  @Test
  void createKommandoGroup_nonLeader_isForbidden() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/squadrons/{s}/kommando-groups", squadronId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Alpha\"}")
                .with(member("ROLE_OFFICER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void createKommandoGroup_delegatedStaffelleiter_isAllowed() throws Exception {
    when(orgRoleManagementSecurityService.canManageKommandoGroups(any(), any())).thenReturn(true);
    when(kommandoGroupService.createGroup(any(), any()))
        .thenReturn(new KommandoGroupDto(UUID.randomUUID(), squadronId, "Alpha", 0, 0L));
    mockMvc
        .perform(
            post("/api/v1/squadrons/{s}/kommando-groups", squadronId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Alpha\"}")
                .with(member("ROLE_OFFICER")))
        .andExpect(status().isOk());
  }

  @Test
  void addBereichRole_nonLeader_isForbidden() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/org-units/bereiche/{id}/members", bereichId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + targetUser + "\",\"role\":\"KOORDINATOR\"}")
                .with(member("ROLE_OFFICER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void addBereichRole_delegatedOlMember_isAllowed() throws Exception {
    when(orgRoleManagementSecurityService.canAppointBereichRole(any(), any(), any(), any()))
        .thenReturn(true);
    when(orgRoleManagementSecurityService.targetsAnotherUser(any(), any())).thenReturn(true);
    OrgUnitMembership m = new OrgUnitMembership();
    m.setId(new OrgUnitMembershipId(targetUser, bereichId));
    m.setRole(MembershipRole.BEREICHSLEITER);
    m.setVersion(0L);
    when(orgUnitMembershipService.addBereichLeader(any(), any(), any())).thenReturn(m);
    mockMvc
        .perform(
            post("/api/v1/org-units/bereiche/{id}/members", bereichId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + targetUser + "\",\"role\":\"LEITER\"}")
                .with(member("ROLE_OFFICER")))
        .andExpect(status().isOk());
  }

  @Test
  void assignSquadronRank_delegatedLeaderOnOwnSeat_isForbidden() throws Exception {
    when(orgRoleManagementSecurityService.canAssignSquadronRank(any(), any(), any()))
        .thenReturn(true);
    when(orgRoleManagementSecurityService.targetsAnotherUser(any(), any())).thenReturn(false);
    mockMvc
        .perform(
            put("/api/v1/squadrons/{s}/ranks/{u}", squadronId, targetUser)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"STAFFELLEITER\",\"version\":0}")
                .with(member("ROLE_OFFICER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void removeSquadronRank_delegatedLeaderOnOwnSeat_isForbidden() throws Exception {
    when(orgRoleManagementSecurityService.canRemoveSquadronRank(any(), any(), any()))
        .thenReturn(true);
    when(orgRoleManagementSecurityService.targetsAnotherUser(any(), any())).thenReturn(false);
    mockMvc
        .perform(
            delete("/api/v1/squadrons/{s}/ranks/{u}", squadronId, targetUser)
                .with(member("ROLE_OFFICER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void addBereichRole_delegatedLeaderOnOwnSeat_isForbidden() throws Exception {
    when(orgRoleManagementSecurityService.canAppointBereichRole(any(), any(), any(), any()))
        .thenReturn(true);
    when(orgRoleManagementSecurityService.targetsAnotherUser(any(), any())).thenReturn(false);
    mockMvc
        .perform(
            post("/api/v1/org-units/bereiche/{id}/members", bereichId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + targetUser + "\",\"role\":\"LEITER\"}")
                .with(member("ROLE_OFFICER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void assignSquadronRank_adminOnOwnSeat_isAllowed() throws Exception {
    when(orgUnitMembershipService.assignSquadronRankDto(any(), any(), any(), any(), any()))
        .thenReturn(dtoStub());
    mockMvc
        .perform(
            put("/api/v1/squadrons/{s}/ranks/{u}", squadronId, targetUser)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"STAFFELLEITER\",\"version\":0}")
                .with(
                    jwt()
                        .jwt(j -> j.subject(targetUser.toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk());
  }

  @Test
  void assignSquadronRank_delegatedLeaderPinnedToAnotherUnit_isForbidden() throws Exception {
    UUID pinned = seedStaffel();
    UUID leader = seedStaffelleiterOf(pinned);
    when(orgRoleManagementSecurityService.canAssignSquadronRank(any(), any(), any()))
        .thenReturn(true);
    when(orgRoleManagementSecurityService.targetsAnotherUser(any(), any())).thenReturn(true);
    mockMvc
        .perform(
            put("/api/v1/squadrons/{s}/ranks/{u}", squadronId, targetUser)
                .header(RequestScopeResolver.ACTIVE_ORG_UNIT_HEADER, pinned.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"KOMMANDOLEITER\",\"version\":0}")
                .with(krtMember(leader)))
        .andExpect(status().isForbidden());
  }

  @Test
  void assignSquadronRank_delegatedLeaderPinnedToTheTargetSquadron_isAllowed() throws Exception {
    UUID pinned = seedStaffel();
    UUID leader = seedStaffelleiterOf(pinned);
    when(orgRoleManagementSecurityService.canAssignSquadronRank(any(), any(), any()))
        .thenReturn(true);
    when(orgRoleManagementSecurityService.targetsAnotherUser(any(), any())).thenReturn(true);
    when(orgUnitMembershipService.assignSquadronRankDto(any(), any(), any(), any(), any()))
        .thenReturn(dtoStub());
    mockMvc
        .perform(
            put("/api/v1/squadrons/{s}/ranks/{u}", pinned, targetUser)
                .header(RequestScopeResolver.ACTIVE_ORG_UNIT_HEADER, pinned.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"KOMMANDOLEITER\",\"version\":0}")
                .with(krtMember(leader)))
        .andExpect(status().isOk());
  }

  @Test
  void removeSquadronRank_adminPinnedToAnotherUnit_isForbidden() throws Exception {
    mockMvc
        .perform(
            delete("/api/v1/squadrons/{s}/ranks/{u}", squadronId, targetUser)
                .header(RequestScopeResolver.ACTIVE_ORG_UNIT_HEADER, seedStaffel().toString())
                .with(member("ROLE_ADMIN")))
        .andExpect(status().isForbidden());
  }

  @Test
  void updateKommandoGroup_delegatedLeaderPinnedToAnotherUnit_isForbidden() throws Exception {
    UUID pinned = seedStaffel();
    UUID leader = seedStaffelleiterOf(pinned);
    when(orgRoleManagementSecurityService.canManageKommandoGroup(any(), any())).thenReturn(true);
    mockMvc
        .perform(
            put("/api/v1/squadrons/{s}/kommando-groups/{g}", squadronId, UUID.randomUUID())
                .header(RequestScopeResolver.ACTIVE_ORG_UNIT_HEADER, pinned.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Alpha\",\"sortIndex\":0,\"version\":0}")
                .with(krtMember(leader)))
        .andExpect(status().isForbidden());
  }

  @Test
  void createKommandoGroup_adminPinnedToAnotherUnit_isForbidden() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/squadrons/{s}/kommando-groups", squadronId)
                .header(RequestScopeResolver.ACTIVE_ORG_UNIT_HEADER, seedStaffel().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Alpha\"}")
                .with(member("ROLE_ADMIN")))
        .andExpect(status().isForbidden());
  }

  @Test
  void addBereichRole_adminPinnedToAnotherUnit_isForbidden() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/org-units/bereiche/{id}/members", bereichId)
                .header(RequestScopeResolver.ACTIVE_ORG_UNIT_HEADER, seedStaffel().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + targetUser + "\",\"role\":\"KOORDINATOR\"}")
                .with(member("ROLE_ADMIN")))
        .andExpect(status().isForbidden());
  }

  @Test
  void toggleSkLead_adminPinnedToAnotherUnit_isForbidden() throws Exception {
    mockMvc
        .perform(
            patch("/api/v1/special-commands/{id}/members/{u}/lead", UUID.randomUUID(), targetUser)
                .header(RequestScopeResolver.ACTIVE_ORG_UNIT_HEADER, seedStaffel().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"isLead\":true,\"version\":0}")
                .with(member("ROLE_ADMIN")))
        .andExpect(status().isForbidden());
  }
}
