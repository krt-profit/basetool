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

import de.greluc.krt.profit.basetool.frontend.model.dto.DefaultBlueprintCreateRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.DefaultBlueprintDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.util.List;
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
 * MVC-level test for {@link AdminDefaultBlueprintsPageController}. Pins two things:
 *
 * <ul>
 *   <li>the type-ahead proxy's double-encoding fix: the free-text term must reach the backend as a
 *       WebClient URI-template variable (encoded exactly once), never {@code URLEncoder}-encoded
 *       into the URI string (which the frontend&rarr;backend hop re-encodes, mangling spaces and
 *       umlauts to a zero-match);
 *   <li>the in-place mutations (REQ-FE-001): the {@code X-Requested-With}-routed add and remove
 *       twins answer JSON / status instead of a redirect, the {@code fragment=rows} read renders
 *       only the swapped table, and the header-less requests keep the POST → redirect fallback.
 * </ul>
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

  // Regression guard for the frontend-proxy double-encoding sub-class: the default-blueprint
  // typeahead must forward a multi-word free-text term as a WebClient URI-template variable ({q}),
  // not URLEncoder it into the URI string, so the backend @RequestParam decodes the exact typed
  // term. URLEncoder form-encoding (space -> '+') double-encodes across the frontend->backend hop
  // and yields zero matches.
  @Test
  @WithMockUser(roles = "ADMIN")
  void search_passesMultiWordQueryAsUriVariable() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef(), any())).thenReturn(List.of());

    mockMvc
        .perform(get("/admin/default-blueprints/search").param("q", "Arclight Pistol"))
        .andExpect(status().isOk());

    ArgumentCaptor<String> uriCaptor = ArgumentCaptor.captor();
    ArgumentCaptor<Object> qCaptor = ArgumentCaptor.captor();
    verify(backendApiClient).get(uriCaptor.capture(), anyTypeRef(), qCaptor.capture());
    assertTrue(uriCaptor.getValue().contains("q={q}"), uriCaptor.getValue());
    assertEquals("Arclight Pistol", qCaptor.getValue());
  }

  // Same guard with an umlaut term: "Müller Röhre" encodes to M%C3%BCller… under URLEncoder, which
  // the hop would re-encode to a literal zero-match. As a URI variable the raw term reaches the
  // backend.
  @Test
  @WithMockUser(roles = "ADMIN")
  void search_passesUmlautQueryAsUriVariable_notFormEncoded() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef(), any())).thenReturn(List.of());

    String term = "Müller Röhre";
    mockMvc
        .perform(get("/admin/default-blueprints/search").param("q", term))
        .andExpect(status().isOk());

    ArgumentCaptor<String> uriCaptor = ArgumentCaptor.captor();
    ArgumentCaptor<Object> qCaptor = ArgumentCaptor.captor();
    verify(backendApiClient).get(uriCaptor.capture(), anyTypeRef(), qCaptor.capture());
    assertTrue(uriCaptor.getValue().contains("q={q}"), uriCaptor.getValue());
    assertEquals(term, qCaptor.getValue());
  }

  // REQ-FE-001: the in-place add answers with the per-key outcome instead of a redirect. One new
  // default, one already-default (the backend's 409, skipped) and one other failure produce
  // added=1, skipped=1 and the failed key alone — which is what the page keeps staged for a retry.
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

    // The blank entry never reaches the backend; the padded one is trimmed before it does.
    verify(backendApiClient, times(3)).post(eq(BACKEND_BASE), any(), any());
  }

  // Without the X-Requested-With header the same path is still the classic form handler, so a
  // browser where krtFetch did not load keeps its POST -> redirect fallback.
  @Test
  @WithMockUser(roles = "ADMIN")
  void add_withoutXhrHeader_keepsTheRedirectFallback() throws Exception {
    mockMvc
        .perform(post("/admin/default-blueprints/add").with(csrf()).param("productKeys", "new_key"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/admin/default-blueprints"))
        .andExpect(flash().attribute("successToast", "admin.defaultBlueprints.toast.added"));
  }

  // REQ-FE-001: the in-place remove answers 200 instead of a redirect.
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

    verify(backendApiClient).delete(BACKEND_BASE + "/" + id, Void.class);
  }

  // A backend failure on the in-place remove (another admin removed the entry first) is relayed
  // with its status, so krtFetch toasts it instead of the page pretending the remove worked.
  @Test
  @WithMockUser(roles = "ADMIN")
  void removeAjax_relaysBackendFailure() throws Exception {
    UUID id = UUID.randomUUID();
    when(backendApiClient.delete(BACKEND_BASE + "/" + id, Void.class))
        .thenThrow(new BackendServiceException("not found", null, 404));

    mockMvc
        .perform(
            post("/admin/default-blueprints/" + id + "/delete")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf()))
        .andExpect(status().isNotFound());
  }

  // The swap target: fragment=rows renders the table alone — no page chrome — with one keyed row
  // per default, which is what the page re-reads its "already a default" set from.
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

  // The fragment read must not paint "no defaults" over a set that still exists: a backend failure
  // is a non-2xx, so krtFetch.swap leaves the list on screen and toasts instead.
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
