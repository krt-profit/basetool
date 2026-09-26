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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.exception.GlobalExceptionHandler;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionOwnership;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.service.MissionSecurityService;
import de.greluc.krt.profit.basetool.backend.service.MissionService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Tests the versioned owner change {@code PUT /api/v1/missions/{id}/owner}: a stale version is a
 * {@code 409} with code {@code OPTIMISTIC_LOCK}, success returns the new counter, and a missing
 * version is refused.
 */
@SpringBootTest
class MissionOwnerChangeControllerTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private MissionService missionService;
  @MockitoBean private MissionSecurityService missionSecurityService;
  @MockitoBean private JwtDecoder jwtDecoder;

  private final UUID missionId = UUID.randomUUID();
  private final UUID newOwnerId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    when(missionSecurityService.canChangeOwner(eq(missionId), any())).thenReturn(true);
  }

  private static SimpleGrantedAuthority officer() {
    return new SimpleGrantedAuthority("ROLE_OFFICER");
  }

  private String body(Long version) {
    return version == null
        ? "{\"userId\":\"" + newOwnerId + "\"}"
        : "{\"userId\":\"" + newOwnerId + "\",\"version\":" + version + "}";
  }

  @Test
  void staleOwnershipVersion_isA409WithTheOptimisticLockCode() throws Exception {
    when(missionService.updateMissionOwner(missionId, newOwnerId, 1L))
        .thenThrow(new ObjectOptimisticLockingFailureException(MissionOwnership.class, missionId));

    mockMvc
        .perform(
            put("/api/v1/missions/{id}/owner", missionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(1L))
                .with(jwt().authorities(officer())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(GlobalExceptionHandler.CODE_OPTIMISTIC_LOCK));
  }

  @Test
  void matchingOwnershipVersion_answersWithTheNewOwnerAndTheCounterToEchoNext() throws Exception {
    User owner = new User();
    owner.setId(newOwnerId);
    owner.setUsername("new.owner");
    Mission mission = new Mission();
    mission.setId(missionId);
    mission.setName("Einsatz");
    mission.setOwner(owner);
    mission.setOwnershipVersion(3L);
    when(missionService.updateMissionOwner(missionId, newOwnerId, 2L)).thenReturn(mission);

    mockMvc
        .perform(
            put("/api/v1/missions/{id}/owner", missionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(2L))
                .with(jwt().authorities(officer())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.owner.id").value(newOwnerId.toString()))
        .andExpect(jsonPath("$.ownershipVersion").value(3));
  }

  @Test
  void missingOwnershipVersion_isRefusedRatherThanTreatedAsNoCheck() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/missions/{id}/owner", missionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(null))
                .with(jwt().authorities(officer())))
        .andExpect(status().isBadRequest());
    verify(missionService, never()).updateMissionOwner(any(), any(), any());
  }
}
