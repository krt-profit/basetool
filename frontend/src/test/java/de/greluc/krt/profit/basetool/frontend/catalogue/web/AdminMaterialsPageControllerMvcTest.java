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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialCategoryDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
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
 * loads the {@code static/js/catalogue/admin-materials.js} module (ADR-0069) and the response ends
 * with the closing {@code </html>} tag.
 */
@SpringBootTest
class AdminMaterialsPageControllerMvcTest {

  private static final String LIST_TEMPLATE =
      "/api/v1/materials?includeHidden=true&sort=name,asc&page={page}&size={size}";

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
   * Asserts the rendered page loads the extracted {@code static/js/catalogue/admin-materials.js}
   * module (ADR-0069), whose tail carries the {@code 'admin-materials-update'} delegated-event
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
    stubDisplayedPage(List.of(material), 1);
    when(backendApiClient.get(eq("/api/v1/material-categories"), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/admin/materials"))
        .andExpect(status().isOk())
        .andExpect(view().name("catalogue/admin/materials"))
        .andExpect(content().string(containsString("id=\"materialNames-data\"")))
        .andExpect(content().string(containsString("value=\"Aluminum\"")))
        .andExpect(content().string(containsString("src=\"/js/catalogue/admin-materials.js\"")))
        .andExpect(content().string(containsString("</html>")));
  }

  /**
   * Stubs the displayed first page of 25 materials.
   *
   * @param content the rows of the page
   * @param total the total number of materials
   */
  private void stubDisplayedPage(@NotNull List<MaterialDto> content, long total) {
    when(backendApiClient.get(eq(LIST_TEMPLATE), anyTypeRef(), eq(0), eq(25)))
        .thenReturn(
            new PageResponse<>(
                content, 0, 25, total, (int) ((total + 24) / 25), Collections.emptyList()));
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
    stubDisplayedPage(materials, materials.size());
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
        .doesNotContain("data-category-row")
        .doesNotContain("/admin/materials/categories");
  }

  /** No materials: the list shows its empty state, and no category editor exists (REQ-P4K-025). */
  @Test
  @WithMockUser(roles = "ADMIN")
  void listMaterials_rendersTheEmptyStates() throws Exception {
    String html = renderList(List.of(), List.of());

    String main = html.substring(html.indexOf("<main"), html.indexOf("</main>"));
    assertThat(main)
        .containsPattern("data-materials-table[^>]*hidden=\"hidden\"")
        .doesNotContain("data-category-table");
    assertThat(main.split("data-testid=\"empty-state\"", -1)).hasSize(2);
  }

  /**
   * More materials than one page: the head counts all of them, the footer shows the range, the page
   * links and the size picker, and the search is a server form kept in every link.
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void listMaterials_paginatesServerSide_andKeepsTheSearchInThePageLinks() throws Exception {
    MaterialDto material =
        new MaterialDto(
            UUID.randomUUID(),
            "Aluminum",
            "RAW",
            "SCU",
            null,
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
    when(backendApiClient.get(
            eq(LIST_TEMPLATE + "&query={query}"), anyTypeRef(), eq(0), eq(25), eq("Al")))
        .thenReturn(new PageResponse<>(List.of(material), 0, 25, 213, 9, Collections.emptyList()));

    String html =
        mockMvc
            .perform(get("/admin/materials").param("query", "Al").locale(Locale.GERMAN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .containsPattern("data-testid=\"page-head-count\"[^>]*>213<")
        .contains("id=\"materials-filter\"")
        .containsPattern("id=\"filterMaterials\" name=\"query\" value=\"Al\"")
        .contains("data-list-total=\"213\"")
        .contains("class=\"pagination\"")
        .contains("href=\"/admin/materials?query=Al&amp;page=1&amp;size=25\"")
        .contains("href=\"/admin/materials?query=Al&amp;page=0&amp;size=100\"")
        .doesNotContain("data-trigger=\"filter-table\"");
  }

  /** The results fragment holds the table and the footer, without the page chrome. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void listMaterials_resultsFragment_rendersOnlyTheList() throws Exception {
    stubDisplayedPage(List.of(), 0);
    when(backendApiClient.get(eq("/api/v1/material-categories"), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    String html =
        mockMvc
            .perform(get("/admin/materials").param("fragment", "results"))
            .andExpect(status().isOk())
            .andExpect(view().name("catalogue/admin/materials :: materialsResults"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains("id=\"materialsTable\"")
        .contains("data-testid=\"empty-state\"")
        .doesNotContain("<html")
        .doesNotContain("id=\"materials-results\"")
        .doesNotContain("data-testid=\"page-head\"");
  }

  /**
   * Builds a raw material with the given override flags (REQ-P4K-024, REQ-P4K-025).
   *
   * @param id the material id
   * @param groupManual whether the resource group is an admin override
   * @param refinedManual whether the refined material is an admin override
   * @return the material
   */
  private static @NotNull MaterialDto overridden(
      @NotNull UUID id, boolean groupManual, boolean refinedManual) {
    return new MaterialDto(
        id,
        "Borase",
        "RAW",
        "SCU",
        null,
        null,
        null,
        false,
        false,
        false,
        false,
        false,
        false,
        true,
        3L,
        null,
        null,
        groupManual,
        refinedManual);
  }

  /**
   * Each override marker shows only for a field the material overrides, with its release button.
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void listMaterials_showsTheOverrideMarkerOfAnOverriddenFieldOnly() throws Exception {
    String html = renderList(List.of(overridden(UUID.randomUUID(), true, false)), List.of());

    assertThat(html)
        .containsPattern(
            "data-p4k-override=\"RESOURCE_GROUP\""
                + " data-testid=\"material-override-RESOURCE_GROUP\">")
        .containsPattern(
            "data-p4k-override=\"REFINED_MATERIAL\""
                + " data-testid=\"material-override-REFINED_MATERIAL\" hidden=\"hidden\">")
        .contains(
            "data-trigger=\"admin-materials-release-override\" data-p4k-field=\"RESOURCE_GROUP\"")
        .contains(">An den Import zurückgeben<")
        .contains(">von Hand<");
  }

  /** Releasing an override forwards the field and version and answers with the saved material. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void releaseP4kOverrideAjax_forwardsTheFieldAndVersion_andAnswersWithTheMaterial()
      throws Exception {
    UUID id = UUID.randomUUID();
    when(backendApiClient.delete(
            eq("/api/v1/materials/{id}/p4k-overrides/{field}?version={version}"),
            eq(MaterialDto.class),
            eq(id),
            eq("RESOURCE_GROUP"),
            eq(3L)))
        .thenReturn(overridden(id, false, false));

    mockMvc
        .perform(
            delete("/admin/materials/{id}/p4k-overrides/RESOURCE_GROUP/ajax", id)
                .param("version", "3")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.resourceGroupManual").value(false))
        .andExpect(jsonPath("$.version").value(3));
  }

  /** A stale version reaches the page as the backend's 409, which opens the conflict dialog. */
  @Test
  @WithMockUser(roles = "ADMIN")
  void releaseP4kOverrideAjax_passesTheBackendConflictThrough() throws Exception {
    UUID id = UUID.randomUUID();
    when(backendApiClient.delete(
            eq("/api/v1/materials/{id}/p4k-overrides/{field}?version={version}"),
            eq(MaterialDto.class),
            eq(id),
            eq("REFINED_MATERIAL"),
            eq(2L)))
        .thenThrow(new BackendServiceException("stale", null, 409));

    mockMvc
        .perform(
            delete("/admin/materials/{id}/p4k-overrides/REFINED_MATERIAL/ajax", id)
                .param("version", "2")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf()))
        .andExpect(status().isConflict());
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
}
