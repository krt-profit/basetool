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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.blueprint.model.DefaultBlueprintCreateRequest;
import de.greluc.krt.profit.basetool.frontend.blueprint.model.DefaultBlueprintDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MVC test for {@link AdminDefaultBlueprintsPageController}: the type-ahead term reaches the
 * backend as a URI-template variable encoded exactly once, and the add/remove AJAX twins, the
 * {@code fragment=rows} read and the header-less redirect fallback behave (REQ-FE-001).
 */
@SpringBootTest
class AdminDefaultBlueprintsPageControllerMvcTest {

  /** Backend admin API the page relays to. */
  private static final String BACKEND_BASE = "/api/v1/admin/default-blueprints";

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
  void search_passesMultiWordQueryAsUriVariable() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef(), any(Object[].class)))
        .thenReturn(List.of());

    mockMvc
        .perform(get("/admin/default-blueprints/search").param("q", "Arclight Pistol"))
        .andExpect(status().isOk());

    ArgumentCaptor<String> uriCaptor = ArgumentCaptor.captor();
    ArgumentCaptor<Object> qCaptor = ArgumentCaptor.captor();
    verify(backendApiClient).get(uriCaptor.capture(), anyTypeRef(), qCaptor.capture(), eq(25));
    assertTrue(uriCaptor.getValue().contains("q={q}&limit={limit}"), uriCaptor.getValue());
    assertEquals("Arclight Pistol", qCaptor.getValue());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void search_passesUmlautQueryAsUriVariable_notFormEncoded() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef(), any(Object[].class)))
        .thenReturn(List.of());

    String term = "Müller Röhre";
    mockMvc
        .perform(get("/admin/default-blueprints/search").param("q", term))
        .andExpect(status().isOk());

    ArgumentCaptor<String> uriCaptor = ArgumentCaptor.captor();
    ArgumentCaptor<Object> qCaptor = ArgumentCaptor.captor();
    verify(backendApiClient).get(uriCaptor.capture(), anyTypeRef(), qCaptor.capture(), eq(25));
    assertTrue(uriCaptor.getValue().contains("q={q}&limit={limit}"), uriCaptor.getValue());
    assertEquals(term, qCaptor.getValue());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void addAjax_reportsAddedSkippedAndFailedKeys() throws Exception {
    when(backendApiClient.post(
            eq(BACKEND_BASE), eq(new DefaultBlueprintCreateRequest("new_key")), any()))
        .thenReturn(null);
    when(backendApiClient.post(
            eq(BACKEND_BASE), eq(new DefaultBlueprintCreateRequest("dup_key")), any()))
        .thenThrow(new BackendServiceException("already a default", null, 409));
    when(backendApiClient.post(
            eq(BACKEND_BASE), eq(new DefaultBlueprintCreateRequest("bad_key")), any()))
        .thenThrow(new BackendServiceException("no such product", null, 404));

    mockMvc
        .perform(
            post("/admin/default-blueprints/add")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productKeys\":[\"new_key\",\" dup_key \",\"\",\"bad_key\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.added").value(1))
        .andExpect(jsonPath("$.skipped").value(1))
        .andExpect(jsonPath("$.failedKeys.length()").value(1))
        .andExpect(jsonPath("$.failedKeys[0]").value("bad_key"));

    verify(backendApiClient, times(3)).post(eq(BACKEND_BASE), any(), any());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void add_withoutXhrHeader_keepsTheRedirectFallback() throws Exception {
    mockMvc
        .perform(post("/admin/default-blueprints/add").with(csrf()).param("productKeys", "new_key"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/admin/default-blueprints"))
        .andExpect(flash().attribute("successToast", "admin.defaultBlueprints.toast.added"));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void removeAjax_returnsOk() throws Exception {
    UUID id = UUID.randomUUID();

    mockMvc
        .perform(
            post("/admin/default-blueprints/" + id + "/delete")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf()))
        .andExpect(status().isOk());

    verify(backendApiClient).delete("/api/v1/admin/default-blueprints/{id}", Void.class, id);
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void removeAjax_relaysBackendFailure() throws Exception {
    UUID id = UUID.randomUUID();
    when(backendApiClient.delete("/api/v1/admin/default-blueprints/{id}", Void.class, id))
        .thenThrow(new BackendServiceException("not found", null, 404));

    mockMvc
        .perform(
            post("/admin/default-blueprints/" + id + "/delete")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf()))
        .andExpect(status().isNotFound());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void rowsFragment_rendersOnlyTheKeyedRows() throws Exception {
    DefaultBlueprintDto entry =
        new DefaultBlueprintDto(
            UUID.randomUUID(), "starter_pistol", "Starter Pistol", null, null, "system", null, 0L);
    when(backendApiClient.get(eq(BACKEND_BASE), anyTypeRef())).thenReturn(List.of(entry));

    mockMvc
        .perform(
            get("/admin/default-blueprints")
                .param("fragment", "rows")
                .header("X-Requested-With", "XMLHttpRequest"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-product-key=\"starter_pistol\"")))
        .andExpect(content().string(containsString("Starter Pistol")))
        .andExpect(content().string(not(containsString("<main"))))
        .andExpect(content().string(not(containsString("krt-dbp-list-host"))));
  }

  /**
   * The page renders on the list pattern (REQ-UI-027): page head with the master-data eyebrow and
   * count, no banner or hud-box, and the stacked table inside a flush card.
   *
   * @throws Exception if the request fails
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void page_rendersTheListPattern() throws Exception {
    DefaultBlueprintDto entry =
        new DefaultBlueprintDto(
            UUID.randomUUID(), "starter_pistol", "Starter Pistol", null, null, "system", null, 0L);
    when(backendApiClient.get(eq(BACKEND_BASE), anyTypeRef())).thenReturn(List.of(entry));

    String html =
        mockMvc
            .perform(get("/admin/default-blueprints").locale(Locale.GERMAN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains("class=\"page-head\"")
        .containsPattern("class=\"page-eyebrow\"[^>]*>Stammdaten<")
        .containsPattern("data-testid=\"page-head-count\"[^>]*>1<")
        .contains("data-list-count-for=\"krt-dbp-list-host\"")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box")
        .doesNotContain("krt-admin-banner")
        .contains("id=\"krt-dbp-list-host\" class=\"card card--flush\"")
        .contains("class=\"data-table data-table--stack\"")
        .contains("data-list-total=\"1\"")
        .doesNotContain("colspan");
    assertThat(html.split("btn--cta", -1)).hasSize(2);
  }

  /**
   * An empty default set renders the empty state instead of a colspan row.
   *
   * @throws Exception if the request fails
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void rowsFragment_emptySet_rendersTheEmptyState() throws Exception {
    when(backendApiClient.get(eq(BACKEND_BASE), anyTypeRef())).thenReturn(List.of());

    String html =
        mockMvc
            .perform(
                get("/admin/default-blueprints")
                    .param("fragment", "rows")
                    .header("X-Requested-With", "XMLHttpRequest"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains("data-testid=\"empty-state\"")
        .doesNotContain("data-table--stack")
        .doesNotContain("colspan")
        .doesNotContain("data-list-total");
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void rowsFragment_backendFailure_isNotAnEmptyList() throws Exception {
    when(backendApiClient.get(eq(BACKEND_BASE), anyTypeRef()))
        .thenThrow(new BackendServiceException("backend down", null, 503));

    int status =
        mockMvc
            .perform(
                get("/admin/default-blueprints")
                    .param("fragment", "rows")
                    .header("X-Requested-With", "XMLHttpRequest"))
            .andReturn()
            .getResponse()
            .getStatus();

    assertTrue(status >= 400, "fragment read answered " + status);
  }
}
