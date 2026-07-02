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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MVC-level render test for {@link AdminBlueprintsPageController}: proves the AJAX swap fragment
 * (REQ-FE-002) actually resolves and renders. A pure unit test only pins the {@code
 * admin/blueprints :: results} view-name string; this test fails if that fragment selector is
 * misspelled or the {@code <th:block th:fragment="results">} block is malformed, which a unit test
 * cannot catch.
 */
@SpringBootTest
class AdminBlueprintsPageControllerMvcTest {

  private MockMvc mockMvc;

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /**
   * Builds a one-row blueprint page envelope as the mocked backend answer.
   *
   * @param total total number of matching blueprints to report
   * @return a single-page envelope holding one minimal blueprint row
   */
  private static PageResponse<BlueprintDto> page(int total) {
    BlueprintDto dto =
        new BlueprintDto(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "BP_OMNI",
            "Omnisky",
            540,
            false,
            2,
            1,
            "4.8",
            null,
            null,
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            0L);
    int totalPages = total == 0 ? 0 : (int) Math.ceil(total / 25.0);
    return new PageResponse<>(List.of(dto), 0, 25, total, totalPages, List.of());
  }

  // covers REQ-FE-002 — the full page renders the swap-target wrapper and the toolbar.
  @Test
  @WithMockUser(roles = "ADMIN")
  void list_fullPage_rendersSwapWrapper() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page(1));

    mockMvc
        .perform(get("/admin/blueprints"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"admin-bp-results\"")))
        .andExpect(content().string(containsString("id=\"admin-bp-filter\"")));
  }

  // covers REQ-FE-002 — fragment=results renders only the inner toolbar + table block: the table
  // and the live total are present, but the swap-target wrapper (outside the fragment) is not.
  @Test
  @WithMockUser(roles = "ADMIN")
  void list_fragmentResults_rendersOnlyInnerFragment() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page(60));

    mockMvc
        .perform(get("/admin/blueprints").param("fragment", "results"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("class=\"bp-table\"")))
        .andExpect(content().string(containsString("class=\"bp-count\"")))
        .andExpect(content().string(containsString("class=\"pager\"")))
        .andExpect(content().string(not(containsString("id=\"admin-bp-results\""))));
  }
}
