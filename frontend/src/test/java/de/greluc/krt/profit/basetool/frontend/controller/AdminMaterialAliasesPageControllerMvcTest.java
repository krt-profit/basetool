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
import de.greluc.krt.profit.basetool.frontend.support.PageStylesheets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
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
 * MVC test for {@link AdminMaterialAliasesPageController}'s AJAX twins: with {@code
 * X-Requested-With} the create/delete twins answer as {@code @ResponseBody} (create returning the
 * persisted {@link MaterialExternalAliasDto}); without it the create URL still redirects.
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

  @Test
  @WithMockUser(roles = "ADMIN")
  void listPage_ShouldExcludeCheckboxesFromFormGroupInputRule() throws Exception {
    mockMvc
        .perform(get("/admin/material-aliases"))
        .andExpect(status().isOk())
        .andExpect(
            PageStylesheets.content(
                containsString(
                    ".form-group input:where(:not([type='checkbox'], [type='radio']))")));
  }

  /**
   * Renders {@code /admin/material-aliases} in German.
   *
   * @return the rendered HTML
   * @throws Exception if the request fails
   */
  private @NotNull String renderList() throws Exception {
    return mockMvc
        .perform(get("/admin/material-aliases").locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /**
   * The list renders on the list pattern with the source system translated: the raw backend code
   * never reaches the cell.
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void listPage_rendersTheListPatternWithATranslatedSource() throws Exception {
    MaterialExternalAliasDto alias =
        new MaterialExternalAliasDto(
            UUID.randomUUID(),
            0L,
            UUID.randomUUID(),
            "Silicon (Raw)",
            "REFINERY_SCREEN",
            "Raw Silicon",
            null,
            null,
            null,
            null,
            "system",
            Instant.parse("2026-06-01T00:00:00Z"),
            Instant.parse("2026-06-01T00:00:00Z"));
    when(backendApiClient.get(eq(BACKEND_BASE), anyTypeRef())).thenReturn(List.of(alias));

    String html = renderList();

    assertThat(html)
        .contains("data-testid=\"page-head\"")
        .containsPattern("class=\"page-eyebrow\"[^>]*>Stammdaten<")
        .containsPattern("data-testid=\"page-head-count\"[^>]*>1<")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box")
        .contains("class=\"data-table data-table--stack\"")
        .containsPattern("class=\"cell-title\"[^>]*>Raw Silicon<")
        .containsPattern("data-alias-field=\"sourceSystem\"[^>]*>Raffinerie-Bildschirm<")
        .doesNotContain(">REFINERY_SCREEN<");
    assertThat(html.substring(html.indexOf("<main"), html.indexOf("</main>")))
        .doesNotContain("krtm-")
        .doesNotContain("colspan");
    assertThat(html).containsPattern("data-alias-empty hidden=\"hidden\"");
  }

  /** An empty alias list shows the empty state and hides the table. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void listPage_rendersTheEmptyState() throws Exception {
    String html = renderList();

    assertThat(html)
        .contains("data-testid=\"empty-state\"")
        .containsPattern("data-alias-table hidden=\"hidden\"")
        .doesNotContainPattern("data-alias-empty hidden");
  }

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
