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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.util.List;
import java.util.Map;
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
 * MVC-level render test for {@link AdminMissionDataPageController}: pins the three per-section AJAX
 * swap fragments (REQ-FE-002) of the include-inactive filters. The full page renders all three
 * swap-target wrappers; each {@code ?fragment=<section>-results} request renders only that
 * section's table (the other two sections, the modals and the toolbar buttons live outside it).
 * Fails if any section fragment selector breaks.
 */
@SpringBootTest
class AdminMissionDataPageControllerMvcTest {

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
   * Stubs the three backend catalogue calls (squadrons / job-types / frequency-types) with one row
   * each in the raw {@code Map} wire shape the controller parses, so every section table renders.
   */
  private void stubAllThree() {
    PageResponse<Map<String, Object>> squadrons =
        new PageResponse<>(
            List.of(
                Map.of(
                    "id",
                    UUID.randomUUID().toString(),
                    "name",
                    "Frag SQ",
                    "shorthand",
                    "FSQ",
                    "description",
                    "d",
                    "active",
                    true,
                    "isPromotionEnabled",
                    true,
                    "isProfitEligible",
                    false,
                    "version",
                    0)),
            0,
            1000,
            1L,
            1,
            List.of());
    PageResponse<Map<String, Object>> jobTypes =
        new PageResponse<>(
            List.of(
                Map.of(
                    "id",
                    UUID.randomUUID().toString(),
                    "name",
                    "Frag JT",
                    "description",
                    "d",
                    "archetype",
                    "MISSION",
                    "active",
                    true,
                    "isLeadershipRole",
                    false,
                    "version",
                    0)),
            0,
            1000,
            1L,
            1,
            List.of());
    PageResponse<Map<String, Object>> freqTypes =
        new PageResponse<>(
            List.of(
                Map.of(
                    "id",
                    UUID.randomUUID().toString(),
                    "name",
                    "Frag FT",
                    "description",
                    "d",
                    "active",
                    true,
                    "version",
                    0)),
            0,
            1000,
            1L,
            1,
            List.of());
    when(backendApiClient.get(contains("/api/v1/squadrons"), anyTypeRef())).thenReturn(squadrons);
    when(backendApiClient.get(contains("/api/v1/job-types"), anyTypeRef())).thenReturn(jobTypes);
    when(backendApiClient.get(contains("/api/v1/frequency-types"), anyTypeRef()))
        .thenReturn(freqTypes);
  }

  // covers REQ-FE-002 — the full page renders all three per-section swap-target wrappers.
  @Test
  @WithMockUser(roles = "ADMIN")
  void list_fullPage_rendersAllThreeSwapWrappers() throws Exception {
    stubAllThree();

    mockMvc
        .perform(get("/admin/mission-data"))
        .andExpect(status().isOk())
        .andExpect(view().name("admin/mission-data"))
        .andExpect(content().string(containsString("id=\"squadrons-results\"")))
        .andExpect(content().string(containsString("id=\"jobtypes-results\"")))
        .andExpect(content().string(containsString("id=\"freqtypes-results\"")));
  }

  // covers REQ-FE-002 — ?fragment=squadrons-results renders only the squadron table.
  @Test
  @WithMockUser(roles = "ADMIN")
  void list_fragmentSquadrons_rendersOnlySquadronTable() throws Exception {
    stubAllThree();

    mockMvc
        .perform(get("/admin/mission-data").param("fragment", "squadrons-results"))
        .andExpect(status().isOk())
        .andExpect(view().name("admin/mission-data :: squadrons-results"))
        .andExpect(content().string(containsString("Frag SQ")))
        .andExpect(content().string(not(containsString("id=\"squadrons-results\""))))
        .andExpect(content().string(not(containsString("id=\"jobtypes-results\""))))
        .andExpect(content().string(not(containsString("id=\"freqtypes-results\""))))
        .andExpect(content().string(not(containsString("id=\"squadron-modal\""))))
        .andExpect(content().string(not(containsString("id=\"add-squadron-btn\""))));
  }

  // covers REQ-FE-002 — ?fragment=jobtypes-results renders only the job-type table.
  @Test
  @WithMockUser(roles = "ADMIN")
  void list_fragmentJobtypes_rendersOnlyJobTable() throws Exception {
    stubAllThree();

    mockMvc
        .perform(get("/admin/mission-data").param("fragment", "jobtypes-results"))
        .andExpect(status().isOk())
        .andExpect(view().name("admin/mission-data :: jobtypes-results"))
        .andExpect(content().string(containsString("Frag JT")))
        .andExpect(content().string(not(containsString("id=\"jobtypes-results\""))))
        .andExpect(content().string(not(containsString("id=\"squadrons-results\""))))
        .andExpect(content().string(not(containsString("id=\"jobtype-modal\""))))
        .andExpect(content().string(not(containsString("id=\"add-jobtype-btn\""))));
  }

  // covers REQ-FE-002 — ?fragment=freqtypes-results renders only the freq table (drag rows
  // present).
  @Test
  @WithMockUser(roles = "ADMIN")
  void list_fragmentFreqtypes_rendersOnlyFreqTable() throws Exception {
    stubAllThree();

    mockMvc
        .perform(get("/admin/mission-data").param("fragment", "freqtypes-results"))
        .andExpect(status().isOk())
        .andExpect(view().name("admin/mission-data :: freqtypes-results"))
        .andExpect(content().string(containsString("Frag FT")))
        .andExpect(content().string(containsString("class=\"draggable-row\"")))
        .andExpect(content().string(not(containsString("id=\"freqtypes-results\""))))
        .andExpect(content().string(not(containsString("id=\"squadrons-results\""))))
        .andExpect(content().string(not(containsString("id=\"frequency-type-modal\""))))
        .andExpect(content().string(not(containsString("id=\"add-freqtype-btn\""))));
  }

  // covers #582 — the squadron-create twin (X-Requested-With + form params) relays to the backend
  // and returns 200; the page re-swaps the squadron-results fragment.
  @Test
  @WithMockUser(roles = "ADMIN")
  void createSquadronAjax_withHeader_returns200() throws Exception {
    when(backendApiClient.post(contains("/squadrons"), any(), eq(Void.class))).thenReturn(null);

    mockMvc
        .perform(
            post("/admin/mission-data/squadrons")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .param("name", "Frag SQ")
                .param("shorthand", "FSQ")
                .param("description", "d")
                .param("version", "0"))
        .andExpect(status().isOk());
  }

  // covers #582 — the job-type-delete twin (X-Requested-With) returns 200 so the page re-swaps the
  // job-type fragment in place rather than reloading.
  @Test
  @WithMockUser(roles = "ADMIN")
  void deleteJobTypeAjax_withHeader_returns200() throws Exception {
    UUID id = UUID.randomUUID();
    when(backendApiClient.delete(eq("/api/v1/job-types/" + id), eq(Void.class))).thenReturn(null);

    mockMvc
        .perform(
            post("/admin/mission-data/job-types/" + id + "/delete")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf()))
        .andExpect(status().isOk());
  }

  // covers #582 — a backend duplicate-name conflict on the squadron create is relayed as the
  // backend
  // status (409) so krtFetch toasts the domain message.
  @Test
  @WithMockUser(roles = "ADMIN")
  void createSquadronAjax_backendConflict_relays409() throws Exception {
    when(backendApiClient.post(contains("/squadrons"), any(), eq(Void.class)))
        .thenThrow(
            new BackendServiceException(
                "duplicate", null, 409, "DUPLICATE", null, java.util.List.of(), "duplicate"));

    mockMvc
        .perform(
            post("/admin/mission-data/squadrons")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .param("name", "Frag SQ")
                .param("shorthand", "FSQ")
                .param("description", "d")
                .param("version", "0"))
        .andExpect(status().isConflict());
  }

  // covers #582 — header routing: the same create URL WITHOUT the header still hits the classic
  // form
  // handler and redirects (no-JS fallback preserved).
  @Test
  @WithMockUser(roles = "ADMIN")
  void createSquadron_withoutHeader_redirects() throws Exception {
    when(backendApiClient.post(contains("/squadrons"), any(), eq(Void.class))).thenReturn(null);

    mockMvc
        .perform(
            post("/admin/mission-data/squadrons")
                .with(csrf())
                .param("name", "Frag SQ")
                .param("shorthand", "FSQ")
                .param("description", "d")
                .param("version", "0"))
        .andExpect(status().is3xxRedirection());
  }
}
