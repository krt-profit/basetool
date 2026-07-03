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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.dto.KommandoGroupDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitMembershipDto;
import de.greluc.krt.profit.basetool.backend.service.KommandoGroupService;
import de.greluc.krt.profit.basetool.backend.service.OrgRoleManagementSecurityService;
import de.greluc.krt.profit.basetool.backend.service.OrgUnitMembershipService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MockMvc gate matrix for the delegated appointment surface (epic #800, REQ-ROLE-004). Verifies
 * that the new {@code @PreAuthorize} SpEL expressions actually bind to {@link
 * OrgRoleManagementSecurityService} (a typo'd bean method would blow up the request, not return a
 * clean 403/200) and behave as the ladder requires: an authenticated non-leader is forbidden, while
 * both an admin and a caller the delegated authoriser approves are let through to the (mocked)
 * service. The verdict <em>logic</em> itself is unit-tested in {@code
 * OrgRoleManagementSecurityServiceTest}; here we only pin the wiring.
 */
@SpringBootTest
@ActiveProfiles("test")
class DelegatedAppointmentControllerSecurityTest {

  @Autowired private WebApplicationContext context;
  private MockMvc mockMvc;

  @MockitoBean private OrgUnitMembershipService orgUnitMembershipService;
  @MockitoBean private KommandoGroupService kommandoGroupService;
  @MockitoBean private OrgRoleManagementSecurityService orgRoleManagementSecurityService;
  @MockitoBean private JwtDecoder jwtDecoder;

  private final UUID squadronId = UUID.randomUUID();
  private final UUID bereichId = UUID.randomUUID();
  private final UUID targetUser = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private OrgUnitMembershipDto dtoStub() {
    // The squadron-rank endpoints now return the service's DTO projection (assignSquadronRankDto),
    // so the mocked service yields a DTO directly (L4, #923). This gate matrix only asserts the
    // status, so a minimal DTO with null display fields is sufficient.
    return new OrgUnitMembershipDto(
        targetUser, null, squadronId, null, false, false, false, null, 1L);
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor member(
      String... roles) {
    SimpleGrantedAuthority[] auths = new SimpleGrantedAuthority[roles.length];
    for (int i = 0; i < roles.length; i++) {
      auths[i] = new SimpleGrantedAuthority(roles[i]);
    }
    return jwt().jwt(j -> j.subject(UUID.randomUUID().toString())).authorities(auths);
  }

  // --- squadron rank (PUT /api/v1/squadrons/{squadronId}/ranks/{userId}) ----

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

  // --- Kommandogruppe create (POST /api/v1/squadrons/{squadronId}/kommando-groups) --

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

  // --- Bereich role (POST /api/v1/org-hierarchy/bereiche/{id}/members) ------

  @Test
  void addBereichRole_nonLeader_isForbidden() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/org-hierarchy/bereiche/{id}/members", bereichId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + targetUser + "\",\"role\":\"KOORDINATOR\"}")
                .with(member("ROLE_OFFICER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void addBereichRole_delegatedOlMember_isAllowed() throws Exception {
    when(orgRoleManagementSecurityService.canAppointBereichRole(any(), any(), any()))
        .thenReturn(true);
    OrgUnitMembership m = new OrgUnitMembership();
    m.setId(new OrgUnitMembershipId(targetUser, bereichId));
    m.setRole(MembershipRole.BEREICHSLEITER);
    m.setVersion(0L);
    when(orgUnitMembershipService.addBereichLeader(any(), any(), any())).thenReturn(m);
    mockMvc
        .perform(
            post("/api/v1/org-hierarchy/bereiche/{id}/members", bereichId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + targetUser + "\",\"role\":\"LEITER\"}")
                .with(member("ROLE_OFFICER")))
        .andExpect(status().isOk());
  }
}
