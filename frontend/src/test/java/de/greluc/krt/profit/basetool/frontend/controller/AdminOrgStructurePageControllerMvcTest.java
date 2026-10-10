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
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.BereichDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitNodeDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitParentResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrganisationsleitungDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CacheDomain;
import java.util.List;
import java.util.Locale;
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
 * MVC test for {@link AdminOrgStructurePageController} (REQ-ORG-014): ADMIN gating of the page and
 * the write twins, the page render, and the AJAX create/set-parent twins relaying to the backend.
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
    when(backendApiClient.get(eq("/api/v1/org-units"), anyTypeRef())).thenReturn(List.of());

    mockMvc.perform(get("/admin/org-structure")).andExpect(status().isOk());
  }

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
    when(backendApiClient.get(eq("/api/v1/org-units"), anyTypeRef())).thenReturn(nodes);

    mockMvc
        .perform(get("/admin/org-structure"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Bereich Profit")))
        .andExpect(content().string(containsString("Iridium")));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void page_admin_unitsFragment_rendersOnlyTheUnitTable() throws Exception {
    UUID olId = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    when(backendApiClient.get(eq("/api/v1/org-units"), anyTypeRef()))
        .thenReturn(
            List.of(
                new OrgUnitNodeDto(
                    olId, "Organisationsleitung KRT", "OL", "ORGANISATIONSLEITUNG", null, null, 0L),
                new OrgUnitNodeDto(
                    UUID.fromString("00000000-0000-0000-0000-0000000000a2"),
                    "Bereich Profit",
                    "PRF",
                    "BEREICH",
                    olId,
                    "PROFIT",
                    0L)));

    mockMvc
        .perform(get("/admin/org-structure").param("fragment", "units"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("org-units-table")))
        .andExpect(content().string(containsString("Bereich Profit")))
        .andExpect(content().string(org.hamcrest.Matchers.not(containsString("bereich-form"))))
        .andExpect(content().string(org.hamcrest.Matchers.not(containsString("<header"))));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void page_admin_formsFragment_rendersOnlyTheCreateForms() throws Exception {
    when(backendApiClient.get(eq("/api/v1/org-units"), anyTypeRef())).thenReturn(List.of());

    mockMvc
        .perform(get("/admin/org-structure").param("fragment", "forms"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("bereich-form")))
        .andExpect(content().string(containsString("os-create-ol")))
        .andExpect(content().string(containsString("os-create-bereich")))
        .andExpect(content().string(org.hamcrest.Matchers.not(containsString("org-units-table"))))
        .andExpect(content().string(org.hamcrest.Matchers.not(containsString("<header"))));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void page_admin_unknownFragment_fallsBackToTheFullPage() throws Exception {
    when(backendApiClient.get(eq("/api/v1/org-units"), anyTypeRef())).thenReturn(List.of());

    mockMvc
        .perform(get("/admin/org-structure").param("fragment", "bogus"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"org-structure-units\"")))
        .andExpect(content().string(containsString("data-testid=\"empty-state\"")))
        .andExpect(content().string(containsString("bereich-form")));
  }

  /**
   * The page renders on the list pattern: page head under the "Benutzer &amp; Inhalte" group with
   * the unit count, the units as a stacked data table with the kind translated, the create forms as
   * cards, no HUD box.
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void page_admin_rendersTheListPattern() throws Exception {
    UUID olId = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    when(backendApiClient.get(eq("/api/v1/org-units"), anyTypeRef()))
        .thenReturn(
            List.of(
                new OrgUnitNodeDto(
                    olId, "Organisationsleitung KRT", "OL", "ORGANISATIONSLEITUNG", null, null, 0L),
                new OrgUnitNodeDto(
                    UUID.fromString("00000000-0000-0000-0000-0000000000a2"),
                    "Bereich Profit",
                    "PRF",
                    "BEREICH",
                    olId,
                    "PROFIT",
                    0L)));

    String html =
        mockMvc
            .perform(get("/admin/org-structure").locale(Locale.GERMAN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains("data-testid=\"page-head\"")
        .containsPattern("class=\"page-eyebrow\"[^>]*>Benutzer &amp; Inhalte<")
        .containsPattern("data-testid=\"page-head-count\"[^>]*>2<")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box");
    String main = html.substring(html.indexOf("<main"), html.indexOf("</main>"));
    assertThat(main.split("btn--cta", -1)).hasSize(2);
    assertThat(main)
        .doesNotContain("krtm-")
        .doesNotContain("colspan")
        .contains("id=\"org-structure-units\" class=\"card card--flush\"")
        .contains("class=\"data-table data-table--stack\"")
        .containsPattern("class=\"cell-title\">Bereich Profit<")
        .containsPattern("data-label=\"Art\">Bereich<")
        .doesNotContain(">BEREICH<")
        .doesNotContain("data-testid=\"empty-state\"");
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void page_nonAdmin_returns403() throws Exception {
    mockMvc.perform(get("/admin/org-structure")).andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void createBereich_ajax_admin_returns200() throws Exception {
    when(backendApiClient.post(eq("/api/v1/org-units/bereiche"), any(), eq(BereichDto.class)))
        .thenReturn(
            new BereichDto(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "Profit",
                "PRF",
                null,
                true,
                null,
                null,
                0L));

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
    when(backendApiClient.patch(
            contains("/parent"), any(), eq(OrgUnitParentResponse.class), any(Object[].class)))
        .thenReturn(
            new OrgUnitParentResponse(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "SQUADRON",
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                1L));

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

  @Test
  @WithMockUser(roles = "ADMIN")
  void createBereich_ajax_evictsStaticDataCache() throws Exception {
    when(backendApiClient.post(any(String.class), any(), eq(BereichDto.class)))
        .thenReturn(
            new BereichDto(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "Profit",
                "PRF",
                null,
                true,
                null,
                null,
                0L));

    mockMvc
        .perform(
            post("/admin/org-structure/bereiche")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Profit\",\"shorthand\":\"PRF\"}"))
        .andExpect(status().isOk());

    verify(backendApiClient).evict(CacheDomain.ORG_UNIT);
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void createOrganisationsleitung_ajax_evictsStaticDataCache() throws Exception {
    when(backendApiClient.post(any(String.class), any(), eq(OrganisationsleitungDto.class)))
        .thenReturn(
            new OrganisationsleitungDto(
                UUID.fromString("00000000-0000-0000-0000-000000000004"),
                "Kartellleitung",
                "OL",
                null,
                true,
                0L));

    mockMvc
        .perform(
            post("/admin/org-structure/organisationsleitung")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Kartellleitung\",\"shorthand\":\"OL\"}"))
        .andExpect(status().isOk());

    verify(backendApiClient).evict(CacheDomain.ORG_UNIT);
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void setParent_ajax_evictsStaticDataCache() throws Exception {
    when(backendApiClient.patch(
            contains("/parent"), any(), eq(OrgUnitParentResponse.class), any(Object[].class)))
        .thenReturn(
            new OrgUnitParentResponse(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "SQUADRON",
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                1L));

    mockMvc
        .perform(
            patch("/admin/org-structure/org-units/00000000-0000-0000-0000-000000000002/parent")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"parentOrgUnitId\":\"00000000-0000-0000-0000-000000000003\",\"version\":0}"))
        .andExpect(status().isOk());

    verify(backendApiClient).evict(CacheDomain.ORG_UNIT);
  }
}
