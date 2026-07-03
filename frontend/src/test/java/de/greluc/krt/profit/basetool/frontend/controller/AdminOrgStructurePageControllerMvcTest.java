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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitNodeDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MVC-level test for {@link AdminOrgStructurePageController} (epic #692, REQ-ORG-014). Proves the
 * ADMIN gating (a plain member is forbidden on the page and the write twins), that the page
 * renders, and that the {@code X-Requested-With} AJAX create/set-parent twins relay to the backend
 * and return 200.
 */
@SpringBootTest
class AdminOrgStructurePageControllerMvcTest {

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

  @Test
  @WithMockUser(roles = "ADMIN")
  void page_admin_emptyHierarchy_returns200() throws Exception {
    when(backendApiClient.get(eq("/api/v1/org-hierarchy/org-units"), anyTypeRef()))
        .thenReturn(List.of());

    mockMvc.perform(get("/admin/org-structure")).andExpect(status().isOk());
  }

  // Renders a full OL -> Bereich -> Staffel chain so the table's th:each, the per-kind parent
  // selects (OL option for a Bereich, Bereich option for a Staffel) and the SpEL kind/UUID
  // comparisons are actually exercised — the empty-list case never enters the loop.
  @Test
  @WithMockUser(roles = "ADMIN")
  void page_admin_rendersHierarchyRows() throws Exception {
    UUID olId = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    UUID bereichId = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    UUID staffelId = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    List<OrgUnitNodeDto> nodes =
        List.of(
            new OrgUnitNodeDto(
                olId, "Organisationsleitung KRT", "OL", "ORGANISATIONSLEITUNG", null, null, 0L),
            new OrgUnitNodeDto(bereichId, "Bereich Profit", "PRF", "BEREICH", olId, "PROFIT", 0L),
            new OrgUnitNodeDto(staffelId, "Iridium", "IRI", "SQUADRON", bereichId, null, 0L));
    when(backendApiClient.get(eq("/api/v1/org-hierarchy/org-units"), anyTypeRef()))
        .thenReturn(nodes);

    mockMvc
        .perform(get("/admin/org-structure"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Bereich Profit")))
        .andExpect(content().string(containsString("Iridium")));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void page_nonAdmin_returns403() throws Exception {
    mockMvc.perform(get("/admin/org-structure")).andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void createBereich_ajax_admin_returns200() throws Exception {
    when(backendApiClient.post(eq("/api/v1/org-hierarchy/bereiche"), any(), eq(Object.class)))
        .thenReturn(Map.of("id", "00000000-0000-0000-0000-000000000001", "version", 0));

    mockMvc
        .perform(
            post("/admin/org-structure/bereiche")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Profit\",\"shorthand\":\"PRF\"}"))
        .andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void createBereich_ajax_nonAdmin_returns403() throws Exception {
    mockMvc
        .perform(
            post("/admin/org-structure/bereiche")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Profit\",\"shorthand\":\"PRF\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void setParent_ajax_admin_returns200() throws Exception {
    when(backendApiClient.patch(contains("/parent"), any(), eq(Object.class)))
        .thenReturn(Map.of("version", 1));

    mockMvc
        .perform(
            patch("/admin/org-structure/org-units/00000000-0000-0000-0000-000000000002/parent")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"parentOrgUnitId\":\"00000000-0000-0000-0000-000000000003\",\"version\":0}"))
        .andExpect(status().isOk());
  }
}
