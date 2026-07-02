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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.MissionListDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
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

/**
 * Verifies the live-filter behaviour of the mission overview page:
 *
 * <ul>
 *   <li>the legacy "Filtern" submit button is no longer rendered,
 *   <li>the reset control and the AJAX results container are present,
 *   <li>the {@code fragment=results} query parameter returns only the results fragment (no outer
 *       page chrome).
 * </ul>
 */
@SpringBootTest
class MissionsLiveFilterPageTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

    PageResponse<MissionListDto> emptyPage =
        new PageResponse<>(List.<MissionListDto>of(), 0, 20, 0L, 0, List.of());
    when(backendApiClient.get(anyString(), anyTypeRef(), anyBoolean())).thenReturn(emptyPage);
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void missionsPage_ShouldRenderWithoutFilterSubmitButton() throws Exception {
    mockMvc
        .perform(get("/missions"))
        .andExpect(status().isOk())
        // Legacy filter submit button must be gone. The generic "<button type=submit>" backstop was
        // removed because the global sidebar logout is now a CSRF-protected POST form/button (audit
        // L-3), so a submit button legitimately renders on every page.
        .andExpect(content().string(not(containsString("id=\"missions-filter-submit\""))))
        // AJAX results container must be present.
        .andExpect(content().string(containsString("id=\"missions-results\"")))
        // Live-filter JS must be wired in.
        .andExpect(content().string(containsString("/js/missions.js")))
        // KRT loading indicator present.
        .andExpect(content().string(containsString("missions-loading-indicator")))
        // Reset control replaces the old filter submit.
        .andExpect(content().string(containsString("id=\"missions-filter-reset\"")));
  }

  @Test
  @WithMockUser(roles = "OFFICER")
  void missionsPage_WithFragmentResults_ShouldReturnOnlyResultsFragment() throws Exception {
    mockMvc
        .perform(get("/missions").param("fragment", "results"))
        .andExpect(status().isOk())
        // The AJAX fragment must not include the outer page chrome.
        .andExpect(content().string(not(containsString("id=\"missions-filter-form\""))))
        .andExpect(content().string(not(containsString("id=\"missions-results\""))))
        .andExpect(content().string(not(containsString("<html"))));
  }
}
