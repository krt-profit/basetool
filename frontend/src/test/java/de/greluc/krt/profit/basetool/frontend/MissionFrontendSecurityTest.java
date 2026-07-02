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

package de.greluc.krt.profit.basetool.frontend;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@SpringBootTest
@ActiveProfiles("test")
@SuppressWarnings("unchecked")
class MissionFrontendSecurityTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private WebClient webClient;

  @MockitoBean private WebClient publicWebClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  @SuppressWarnings("rawtypes")
  private WebClient.RequestHeadersUriSpec requestHeadersUriSpec;

  @SuppressWarnings("rawtypes")
  private WebClient.RequestHeadersSpec requestHeadersSpec;

  private WebClient.ResponseSpec responseSpec;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

    requestHeadersUriSpec = mock(WebClient.RequestHeadersUriSpec.class);
    requestHeadersSpec = mock(WebClient.RequestHeadersSpec.class);
    responseSpec = mock(WebClient.ResponseSpec.class);

    // Standard mocking for publicWebClient used in listMissions
    when(publicWebClient.get()).thenReturn(requestHeadersUriSpec);
    when(webClient.get()).thenReturn(requestHeadersUriSpec);
    when(requestHeadersUriSpec.uri(any(String.class))).thenReturn(requestHeadersSpec);
    when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.bodyToMono(anyTypeRef()))
        .thenReturn(
            Mono.just(
                new de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse<>(
                    Collections.emptyList(), 0, 20, 0, 0, Collections.emptyList())));
  }

  @Test
  @WithAnonymousUser
  void testMissionsList_Anonymous_ShouldNotSeeCreateButton() throws Exception {
    mockMvc
        .perform(get("/missions"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("/missions/new"))))
        .andExpect(content().string(not(containsString("Create New"))))
        .andExpect(content().string(not(containsString("Neue Mission"))));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void testMissionsList_User_ShouldSeeCreateButton() throws Exception {
    mockMvc
        .perform(get("/missions"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("/missions/new")));
  }

  @Test
  @WithMockUser(roles = "MISSION_MANAGER")
  void testMissionsList_MissionManager_ShouldSeeCreateButton() throws Exception {
    mockMvc
        .perform(get("/missions"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("/missions/new")));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void testMissionsList_Officer_ShouldSeeCreateButton() throws Exception {
    mockMvc
        .perform(get("/missions"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("/missions/new")));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void testMissionsList_Admin_ShouldSeeCreateButton() throws Exception {
    mockMvc
        .perform(get("/missions"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("/missions/new")));
  }
}
