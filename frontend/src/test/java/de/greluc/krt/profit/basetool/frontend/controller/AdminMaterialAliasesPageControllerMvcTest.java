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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialExternalAliasDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.time.Instant;
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
 * MVC-level test for {@link AdminMaterialAliasesPageController}'s in-place AJAX twins (epic #571 /
 * #582). Proves the {@code X-Requested-With} header routing: the create/delete twins are
 * {@code @ResponseBody} (create binding the JSON body and returning the persisted {@link
 * MaterialExternalAliasDto}), while the same create URL POSTed without the header still hits the
 * classic redirect handler. Fails if the header gating breaks.
 */
@SpringBootTest
class AdminMaterialAliasesPageControllerMvcTest {

  private static final String BACKEND_BASE = "/api/v1/material-external-aliases";

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
   * Builds a persisted alias DTO in the backend wire shape so the create twin's JSON response body
   * carries the new id and external name.
   *
   * @param materialId the linked material id echoed into the DTO
   * @return a fully-populated {@link MaterialExternalAliasDto}
   */
  private MaterialExternalAliasDto persistedAlias(UUID materialId) {
    return new MaterialExternalAliasDto(
        UUID.randomUUID(),
        0L,
        materialId,
        "Aluminum",
        "UEX",
        "ALUM",
        null,
        null,
        null,
        null,
        "system",
        Instant.parse("2026-06-01T00:00:00Z"),
        Instant.parse("2026-06-01T00:00:00Z"));
  }

  // REQ-FE-016: the add-alias form's material select opts into the server-side-search combobox —
  // the marker with its remote-materials registry value must sit on the (statically attributed)
  // #newMaterialId select, and the page must no longer fetch the full material catalogue (the
  // remote picker queries /catalog/material-search instead). The list() handler swallows backend
  // failures, so the unstubbed alias fetch still renders the page.
  @Test
  @WithMockUser(roles = "ADMIN")
  void listPage_materialPickerCarriesComboboxMarker() throws Exception {
    mockMvc
        .perform(get("/admin/material-aliases"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    containsString(
                        "id=\"newMaterialId\" name=\"materialId\" required"
                            + " data-krt-combobox=\"remote-materials\"")));

    verify(backendApiClient, never()).get(eq("/api/v1/materials/lookup"), anyTypeRef());
  }

  // covers #582 — the create twin (X-Requested-With + JSON body) relays to the backend and returns
  // the persisted alias.
  @Test
  @WithMockUser(roles = "ADMIN")
  void createAjax_withHeader_returns200AndCreatedAlias() throws Exception {
    UUID materialId = UUID.randomUUID();
    when(backendApiClient.post(
            contains("/material-external-aliases"), any(), eq(MaterialExternalAliasDto.class)))
        .thenReturn(persistedAlias(materialId));

    mockMvc
        .perform(
            post("/admin/material-aliases")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"materialId\":\""
                        + materialId
                        + "\",\"sourceSystem\":\"UEX\",\"externalName\":\"ALUM\"}"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("ALUM")));
  }

  // covers #582 — the delete twin (X-Requested-With) returns 200 so the page removes the alias row
  // in place rather than reloading.
  @Test
  @WithMockUser(roles = "ADMIN")
  void deleteAjax_withHeader_returns200() throws Exception {
    UUID id = UUID.randomUUID();
    when(backendApiClient.delete(eq(BACKEND_BASE + "/" + id), eq(Void.class))).thenReturn(null);

    mockMvc
        .perform(
            post("/admin/material-aliases/" + id + "/delete")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf()))
        .andExpect(status().isOk());
  }

  // covers #582 — header routing: the same create URL WITHOUT the header still hits the classic
  // form handler and redirects (no-JS fallback preserved).
  @Test
  @WithMockUser(roles = "ADMIN")
  void create_withoutHeader_redirects() throws Exception {
    when(backendApiClient.post(
            contains("/material-external-aliases"), any(), eq(MaterialExternalAliasDto.class)))
        .thenReturn(persistedAlias(UUID.randomUUID()));

    mockMvc
        .perform(
            post("/admin/material-aliases")
                .with(csrf())
                .param("materialId", UUID.randomUUID().toString())
                .param("sourceSystem", "UEX")
                .param("externalName", "ALUM"))
        .andExpect(status().is3xxRedirection());
  }
}
