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

package de.greluc.krt.profit.basetool.frontend.blueprint.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.blueprint.model.BlueprintDto;
import de.greluc.krt.profit.basetool.frontend.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MVC render test for {@link AdminBlueprintsPageController}: the {@code admin/blueprints ::
 * results} AJAX swap fragment resolves and renders (REQ-FE-002).
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

  @Test
  @WithMockUser(roles = "ADMIN")
  void list_fullPage_rendersSwapWrapper() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef(), any(Object[].class))).thenReturn(page(1));

    mockMvc
        .perform(get("/admin/blueprints"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"admin-bp-results\"")))
        .andExpect(content().string(containsString("id=\"admin-bp-filter\"")));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void list_fragmentResults_rendersOnlyInnerFragment() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef(), any(Object[].class))).thenReturn(page(60));

    mockMvc
        .perform(get("/admin/blueprints").param("fragment", "results"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("class=\"bp-table\"")))
        .andExpect(content().string(containsString("class=\"bp-count\"")))
        .andExpect(content().string(containsString("class=\"pager\"")))
        .andExpect(content().string(not(containsString("id=\"admin-bp-results\""))));
  }

  /**
   * The full page renders on the list pattern (REQ-UI-027): page head with the master-data eyebrow
   * and count, the live search outside the swapped fragment, and the table inside a flush card.
   *
   * @throws Exception if the request fails
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void list_fullPage_rendersTheListPattern() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef(), any(Object[].class))).thenReturn(page(1));

    String html =
        mockMvc
            .perform(get("/admin/blueprints").locale(Locale.GERMAN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains("class=\"page-head\"")
        .containsPattern("class=\"page-eyebrow\"[^>]*>Stammdaten<")
        .containsPattern("data-testid=\"page-head-count\"[^>]*>1<")
        .contains("data-list-count-for=\"admin-bp-results\"")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box")
        .doesNotContain("btn--cta")
        .contains("id=\"admin-bp-results\" class=\"card card--flush\"")
        .contains("data-list-total=\"1\"");
    String filter = html.substring(html.indexOf("id=\"admin-bp-filter\""));
    assertThat(filter.substring(0, filter.indexOf("</form>")))
        .contains("data-testid=\"toolbar-search\"")
        .contains("name=\"search\"")
        .doesNotContain("type=\"submit\"");
    assertThat(html.indexOf("id=\"admin-bp-filter\""))
        .isLessThan(html.indexOf("id=\"admin-bp-results\""));
  }

  /**
   * An empty result renders the empty state instead of a colspan row.
   *
   * @throws Exception if the request fails
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void list_emptyResult_rendersTheEmptyState() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef(), any(Object[].class)))
        .thenReturn(new PageResponse<>(List.<BlueprintDto>of(), 0, 25, 0, 0, List.of()));

    String html =
        mockMvc
            .perform(get("/admin/blueprints").param("fragment", "results"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains("data-testid=\"empty-state\"")
        .doesNotContain("class=\"bp-table\"")
        .doesNotContain("colspan")
        .doesNotContain("data-list-total");
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void list_passesMultiWordSearchAsUriVariable() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef(), any(Object[].class))).thenReturn(page(1));

    mockMvc
        .perform(get("/admin/blueprints").param("search", "Omni Sky").param("fragment", "results"))
        .andExpect(status().isOk());

    ArgumentCaptor<String> uriCaptor = ArgumentCaptor.captor();
    ArgumentCaptor<Object> termCaptor = ArgumentCaptor.captor();
    verify(backendApiClient)
        .get(uriCaptor.capture(), anyTypeRef(), eq(25), eq(0), termCaptor.capture());
    assertTrue(uriCaptor.getValue().contains("search={search}"), uriCaptor.getValue());
    assertEquals("Omni Sky", termCaptor.getValue());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void list_passesUmlautSearchAsUriVariable_notFormEncoded() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef(), any(Object[].class))).thenReturn(page(1));

    String term = "Größe Röhre";
    mockMvc
        .perform(get("/admin/blueprints").param("search", term).param("fragment", "results"))
        .andExpect(status().isOk());

    ArgumentCaptor<String> uriCaptor = ArgumentCaptor.captor();
    ArgumentCaptor<Object> termCaptor = ArgumentCaptor.captor();
    verify(backendApiClient)
        .get(uriCaptor.capture(), anyTypeRef(), eq(25), eq(0), termCaptor.capture());
    assertTrue(uriCaptor.getValue().contains("search={search}"), uriCaptor.getValue());
    assertEquals(term, termCaptor.getValue());
  }
}
