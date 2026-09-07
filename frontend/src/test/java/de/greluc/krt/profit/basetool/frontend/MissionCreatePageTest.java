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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.reactive.function.client.WebClient;

@SpringBootTest
class MissionCreatePageTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private WebClient webClient;

  @MockitoBean private WebClient termsDocumentClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void testCreateMissionPage_ShouldHideActualTimeFields() throws Exception {
    mockMvc
        .perform(get("/missions/new"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("name=\"actualStartTime\""))))
        .andExpect(content().string(not(containsString("name=\"actualEndTime\""))));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void testCreateMissionPage_ShouldRenderDetailsCardFullWidth() throws Exception {
    // On the create page the Verwaltung grid's right column carries only edit-only editors
    // (th:if="${!isNew}"), so it collapses to a single full-width column and the empty right
    // column is not rendered — the Details card spans the full page width (REQ-MISSION-015).
    mockMvc
        .perform(get("/missions/new"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("pane-grid-2 pane-grid--single")))
        .andExpect(content().string(not(containsString("class=\"pane-col\""))));
  }
}
