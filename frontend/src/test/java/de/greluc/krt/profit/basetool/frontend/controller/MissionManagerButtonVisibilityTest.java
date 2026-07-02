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

package de.greluc.krt.profit.basetool.frontend.controller;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
class MissionManagerButtonVisibilityTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  @WithMockUser(roles = "MISSION_MANAGER")
  void listMissions_AsMissionManager_ShouldShowCreateButton() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef(), anyBoolean()))
        .thenReturn(
            new PageResponse<>(Collections.emptyList(), 0, 10, 0, 0, Collections.emptyList()));

    when(backendApiClient.getCached(anyString(), anyTypeRef(), anyBoolean()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/missions"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("href=\"/missions/new\"")));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void listMissions_AsMember_ShouldShowCreateButton() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef(), anyBoolean()))
        .thenReturn(
            new PageResponse<>(Collections.emptyList(), 0, 10, 0, 0, Collections.emptyList()));

    when(backendApiClient.getCached(anyString(), anyTypeRef(), anyBoolean()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/missions"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("href=\"/missions/new\"")));
  }

  @Test
  void listMissions_AsAnonymous_ShouldNotShowCreateButton() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef(), anyBoolean()))
        .thenReturn(
            new PageResponse<>(Collections.emptyList(), 0, 10, 0, 0, Collections.emptyList()));

    when(backendApiClient.getCached(anyString(), anyTypeRef(), anyBoolean()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/missions"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("href=\"/missions/new\""))));
  }
}
