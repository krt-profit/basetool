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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.dto.request.CreateMissionRequest;
import de.greluc.krt.profit.basetool.backend.service.MissionSecurityService;
import de.greluc.krt.profit.basetool.backend.service.MissionService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifies that the mission write endpoints bind {@link CreateMissionRequest}, so server-managed
 * fields smuggled into the JSON never reach the service.
 */
@SpringBootTest
class MissionControllerCreatePathTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private MissionService missionService;
  @MockitoBean private MissionSecurityService missionSecurityService;
  @MockitoBean private JwtDecoder jwtDecoder;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private static SimpleGrantedAuthority member() {
    return new SimpleGrantedAuthority("ROLE_KRT_MEMBER");
  }

  @Test
  void createMission_dangerousFieldsInJson_areSilentlyDroppedByDtoBinding() throws Exception {
    when(missionService.createMission(any(CreateMissionRequest.class))).thenReturn(new Mission());

    UUID attackerTargetId = UUID.randomUUID();
    UUID attackerSquadronId = UUID.randomUUID();
    String body =
        "{"
            + "\"id\":\""
            + attackerTargetId
            + "\","
            + "\"version\":99,"
            + "\"coreVersion\":99,"
            + "\"scheduleVersion\":99,"
            + "\"flagsVersion\":99,"
            + "\"owningSquadron\":{\"id\":\""
            + attackerSquadronId
            + "\"},"
            + "\"owner\":{\"id\":\""
            + UUID.randomUUID()
            + "\"},"
            + "\"managers\":[],"
            + "\"participants\":[],"
            + "\"name\":\"Attacker Mission\","
            + "\"description\":\"benign field\","
            + "\"isInternal\":true"
            + "}";

    mockMvc
        .perform(
            post("/api/v1/missions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(
                    jwt().jwt(j -> j.subject(UUID.randomUUID().toString())).authorities(member())))
        .andExpect(status().isOk());

    ArgumentCaptor<CreateMissionRequest> captor =
        ArgumentCaptor.forClass(CreateMissionRequest.class);
    Mockito.verify(missionService).createMission(captor.capture());
    CreateMissionRequest forwarded = captor.getValue();

    org.junit.jupiter.api.Assertions.assertEquals("Attacker Mission", forwarded.name());
    org.junit.jupiter.api.Assertions.assertEquals("benign field", forwarded.description());
    org.junit.jupiter.api.Assertions.assertEquals(Boolean.TRUE, forwarded.isInternal());
  }

  @Test
  void createSubMission_dangerousFieldsInJson_areSilentlyDroppedByDtoBinding() throws Exception {
    UUID parentId = UUID.randomUUID();
    when(missionSecurityService.canManageMission(eq(parentId), any())).thenReturn(true);
    when(missionService.addSubMission(eq(parentId), any(CreateMissionRequest.class)))
        .thenReturn(new Mission());

    String body =
        "{"
            + "\"id\":\""
            + UUID.randomUUID()
            + "\","
            + "\"version\":42,"
            + "\"owningSquadron\":{\"id\":\""
            + UUID.randomUUID()
            + "\"},"
            + "\"parent\":{\"id\":\""
            + UUID.randomUUID()
            + "\"},"
            + "\"name\":\"Sub\""
            + "}";

    mockMvc
        .perform(
            post("/api/v1/missions/{id}/sub-missions", parentId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(
                    jwt().jwt(j -> j.subject(UUID.randomUUID().toString())).authorities(member())))
        .andExpect(status().isOk());

    ArgumentCaptor<CreateMissionRequest> captor =
        ArgumentCaptor.forClass(CreateMissionRequest.class);
    Mockito.verify(missionService).addSubMission(eq(parentId), captor.capture());
    org.junit.jupiter.api.Assertions.assertEquals("Sub", captor.getValue().name());
  }
}
