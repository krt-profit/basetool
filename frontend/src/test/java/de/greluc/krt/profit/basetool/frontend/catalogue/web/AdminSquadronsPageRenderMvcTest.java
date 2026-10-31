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

package de.greluc.krt.profit.basetool.frontend.catalogue.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.SquadronDto;
import java.util.List;
import java.util.UUID;
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
 * Renders the Staffeln tab of the organisation structure: the tab bar, the squadron rows with the
 * per-squadron switches for the promotion system and job-order processing, and the empty state
 * (REQ-UI-026).
 */
@SpringBootTest
class AdminSquadronsPageRenderMvcTest {

  private MockMvc mockMvc;

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private String render() throws Exception {
    return mockMvc
        .perform(get("/admin/org-structure/squadrons"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /** A squadron row carries both switches in their stored state and the row actions. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void rendersTheRowsWithBothSwitches() throws Exception {
    SquadronDto alpha =
        new SquadronDto(UUID.randomUUID(), "ALPHA", "ALP", "", true, true, false, 0L);
    when(backendApiClient.get(contains("/api/v1/squadrons"), anyTypeRef(), any(Object[].class)))
        .thenReturn(new PageResponse<>(List.of(alpha), 0, 1000, 1, 1, List.of()));

    String html = render();

    assertThat(html)
        .contains("data-testid=\"page-tabs\"")
        .containsPattern("class=\"tab active\"[^>]*data-entry-key=\"admin.squadrons\"")
        .contains("data-entry-key=\"admin.specialcommands\"")
        .containsPattern("class=\"squadron-promotion-toggle\"[^>]*checked")
        .containsPattern(
            "class=\"squadron-profit-toggle\"[^>]*aria-label=\"Auftragsbearbeitung: ALP\"")
        .doesNotContainPattern("class=\"squadron-profit-toggle\"[^>]*checked")
        .contains("edit-squadron-btn")
        .contains("id=\"squadron-modal\"")
        .contains("/js/catalogue/admin-squadrons.js");
  }

  /** Without squadrons the table gives way to the empty state. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void rendersTheEmptyStateWithoutSquadrons() throws Exception {
    when(backendApiClient.get(contains("/api/v1/squadrons"), anyTypeRef(), any(Object[].class)))
        .thenReturn(new PageResponse<>(List.of(), 0, 1000, 0, 0, List.of()));

    String html = render();

    assertThat(html)
        .contains("data-testid=\"empty-state\"")
        .doesNotContain("squadron-promotion-toggle");
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void createSquadronAjax_withHeader_returns200() throws Exception {
    when(backendApiClient.post(contains("/squadrons"), any(), eq(Void.class))).thenReturn(null);

    mockMvc
        .perform(
            post("/admin/org-structure/squadrons")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .param("name", "Frag SQ")
                .param("shorthand", "FSQ")
                .param("description", "d")
                .param("version", "0"))
        .andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void createSquadronAjax_backendConflict_relays409() throws Exception {
    when(backendApiClient.post(contains("/squadrons"), any(), eq(Void.class)))
        .thenThrow(
            new BackendServiceException(
                "duplicate", null, 409, "DUPLICATE", null, java.util.List.of(), "duplicate"));

    mockMvc
        .perform(
            post("/admin/org-structure/squadrons")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .param("name", "Frag SQ")
                .param("shorthand", "FSQ")
                .param("description", "d")
                .param("version", "0"))
        .andExpect(status().isConflict());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void createSquadron_withoutHeader_redirects() throws Exception {
    when(backendApiClient.post(contains("/squadrons"), any(), eq(Void.class))).thenReturn(null);

    mockMvc
        .perform(
            post("/admin/org-structure/squadrons")
                .with(csrf())
                .param("name", "Frag SQ")
                .param("shorthand", "FSQ")
                .param("description", "d")
                .param("version", "0"))
        .andExpect(status().is3xxRedirection());
  }
}
