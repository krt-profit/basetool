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
import static org.hamcrest.Matchers.containsString;
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

import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialCategoryDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.support.PageStylesheets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
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
 * Regression test for the Thymeleaf JS-inline truncation on {@code /admin/materials}: the page
 * loads the {@code static/js/admin-materials.js} module (ADR-0069) and the response ends with the
 * closing {@code </html>} tag.
 */
@SpringBootTest
class AdminMaterialsPageControllerMvcTest {

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
   * Asserts the rendered page loads the extracted {@code static/js/admin-materials.js} module
   * (ADR-0069), whose tail carries the {@code 'admin-materials-update'} delegated-event
   * registration — proof that the bootstrap still renders fully and the Thymeleaf truncation does
   * not strike again.
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void listMaterials_ShouldRenderUpdateBinding_AfterDatalist() throws Exception {
    MaterialDto material =
        new MaterialDto(
            UUID.randomUUID(),
            "Aluminum",
            "RAW",
            "SCU",
            "Aluminum description",
            null,
            null,
            false,
            false,
            false,
            false,
            false,
            false,
            true,
            0L);
    PageResponse<MaterialDto> materialsPage =
        new PageResponse<>(List.of(material), 0, 1000, 1, 1, Collections.emptyList());

    when(backendApiClient.get(
            eq("/api/v1/materials?size=1000&sort=name,asc&includeHidden=true&page={page}"),
            anyTypeRef(),
            eq(0)))
        .thenReturn(materialsPage);
    when(backendApiClient.get(eq("/api/v1/material-categories"), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/admin/materials"))
        .andExpect(status().isOk())
        .andExpect(view().name("admin/materials"))
        .andExpect(content().string(containsString("id=\"materialNames-data\"")))
        .andExpect(content().string(containsString("value=\"Aluminum\"")))
        .andExpect(content().string(containsString("src=\"/js/admin-materials.js\"")))
        .andExpect(content().string(containsString("</html>")));
  }

  /**
   * Renders {@code /admin/materials} in German with the given materials and categories.
   *
   * @param materials the materials the catalogue returns
   * @param categories the material categories
   * @return the rendered HTML
   * @throws Exception if the request fails
   */
  private @NotNull String renderList(
      @NotNull List<MaterialDto> materials, @NotNull List<MaterialCategoryDto> categories)
      throws Exception {
    when(backendApiClient.get(
            eq("/api/v1/materials?size=1000&sort=name,asc&includeHidden=true&page={page}"),
            anyTypeRef(),
            eq(0)))
        .thenReturn(
            new PageResponse<>(materials, 0, 1000, materials.size(), 1, Collections.emptyList()));
    when(backendApiClient.get(eq("/api/v1/material-categories"), anyTypeRef()))
        .thenReturn(categories);
    return mockMvc
        .perform(get("/admin/materials").locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /**
   * The list renders on the list pattern: one primary action in the page head, the editable rows in
   * a stacked data table, the categories with their delete forms, no migrated one-off classes.
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void listMaterials_rendersTheListPattern() throws Exception {
    MaterialDto material =
        new MaterialDto(
            UUID.randomUUID(),
            "Aluminum",
            "RAW",
            "SCU",
            null,
            null,
            null,
            true,
            false,
            false,
            false,
            false,
            false,
            true,
            0L);
    String html =
        renderList(
            List.of(material), List.of(new MaterialCategoryDto(UUID.randomUUID(), "Metalle", 0L)));

    assertThat(html)
        .contains("data-testid=\"page-head\"")
        .containsPattern("class=\"page-eyebrow\"[^>]*>Stammdaten<")
        .containsPattern("data-testid=\"page-head-count\"[^>]*>1<")
        .contains("data-list-count-for=\"materials-results\"")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box");
    String main = html.substring(html.indexOf("<main"), html.indexOf("</main>"));
    assertThat(main.split("btn--cta", -1)).hasSize(2);
    assertThat(main)
        .containsPattern("class=\"page-actions\">\\s*<button[^>]*class=\"btn btn--cta\"")
        .doesNotContain("krtm-")
        .doesNotContain("colspan")
        .contains("data-testid=\"toolbar-search\"")
        .contains("class=\"data-table data-table--stack admin-materials-table\"")
        .containsPattern("class=\"cell-title\">Aluminum<")
        .contains("class=\"text-danger admin-material-flag\"")
        .contains(">Rohmaterial<")
        .contains("data-list-total=\"1\"")
        .contains("data-category-row")
        .containsPattern("data-category-empty hidden=\"hidden\"")
        .doesNotContain("data-testid=\"empty-state\" data-category-empty>");
  }

  /** Neither materials nor categories: both lists show their empty state. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void listMaterials_rendersTheEmptyStates() throws Exception {
    String html = renderList(List.of(), List.of());

    String main = html.substring(html.indexOf("<main"), html.indexOf("</main>"));
    assertThat(main)
        .containsPattern("data-materials-table[^>]*hidden=\"hidden\"")
        .containsPattern("data-category-table hidden=\"hidden\"")
        .contains("data-category-empty>");
    assertThat(main.split("data-testid=\"empty-state\"", -1)).hasSize(3);
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void listMaterials_ShouldExcludeCheckboxesFromFormGroupInputRule() throws Exception {
    when(backendApiClient.get(
            eq("/api/v1/materials?size=1000&sort=name,asc&includeHidden=true&page={page}"),
            anyTypeRef(),
            eq(0)))
        .thenReturn(
            new PageResponse<MaterialDto>(
                Collections.emptyList(), 0, 1000, 0, 0, Collections.emptyList()));
    when(backendApiClient.get(eq("/api/v1/material-categories"), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/admin/materials"))
        .andExpect(status().isOk())
        .andExpect(
            PageStylesheets.content(
                containsString(
                    ".form-group input:where(:not([type='checkbox'], [type='radio']))")));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void createCategoryAjax_withHeader_returns200WithCategory() throws Exception {
    when(backendApiClient.post(
            contains("/material-categories"), any(), eq(MaterialCategoryDto.class)))
        .thenReturn(new MaterialCategoryDto(UUID.randomUUID(), "X", 0L));

    mockMvc
        .perform(
            post("/admin/materials/categories")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X\"}"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("X")));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void createCategoryAjax_withHeaderBlankName_returns400() throws Exception {
    mockMvc
        .perform(
            post("/admin/materials/categories")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"name\":\"  \"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void deleteCategoryAjax_withHeader_returns200() throws Exception {
    UUID id = UUID.randomUUID();
    when(backendApiClient.delete(eq("/api/v1/material-categories/{id}"), eq(Void.class), eq(id)))
        .thenReturn(null);

    mockMvc
        .perform(
            post("/admin/materials/categories/" + id + "/delete")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf()))
        .andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void createCategory_withoutHeader_redirects() throws Exception {
    when(backendApiClient.post(
            contains("/material-categories"), any(), eq(MaterialCategoryDto.class)))
        .thenReturn(new MaterialCategoryDto(UUID.randomUUID(), "X", 0L));

    mockMvc
        .perform(post("/admin/materials/categories").with(csrf()).param("name", "X"))
        .andExpect(status().is3xxRedirection());
  }
}
