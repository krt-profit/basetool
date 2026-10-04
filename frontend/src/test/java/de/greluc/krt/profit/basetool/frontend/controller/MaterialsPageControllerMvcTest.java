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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialCategoryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialMatrixItemDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialPriceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialPriceOverviewDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.support.PageStylesheets;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
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
 * Render tests for the {@code /materials} listing and {@code /materials/{id}} detail pages: the
 * delegated filter and toggle bindings appear in the output and the page renders completely, which
 * fails if a Java list is inlined into the page script again.
 */
@SpringBootTest
class MaterialsPageControllerMvcTest {

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
  @WithMockUser
  void listMaterials_rendersCategoryToggleBindingAndCompletesScript() throws Exception {
    MaterialPriceOverviewDto dto =
        new MaterialPriceOverviewDto(
            UUID.randomUUID(),
            "Aluminum",
            new MaterialCategoryDto(UUID.randomUUID(), "Mineral", 0L),
            false,
            false,
            false,
            new BigDecimal("5.0"),
            new BigDecimal("7.0"));
    PageResponse<MaterialPriceOverviewDto> pageResponse =
        new PageResponse<>(List.of(dto), 0, 10000, 1, 1, List.of());

    when(backendApiClient.get(
            eq("/api/v1/materials/prices-overview?size=10000&sort=name,asc"), anyTypeRef()))
        .thenReturn(pageResponse);

    mockMvc
        .perform(get("/materials"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("src=\"/js/materials.js\"")))
        .andExpect(content().string(containsString("data-trigger=\"materials-toggle-grouping\"")))
        .andExpect(content().string(containsString("id=\"materialsGrouped\"")))
        .andExpect(content().string(containsString("id=\"materialsFlat\"")))
        .andExpect(content().string(containsString("</body>")))
        .andExpect(content().string(containsString("</html>")))
        .andExpect(content().string(containsString("<datalist id=\"materialNames-data\">")))
        .andExpect(content().string(containsString("<option value=\"Aluminum\">")))
        .andExpect(
            PageStylesheets.content(
                containsString(
                    ".form-group input:where(:not([type='checkbox']):not([type='radio']))")));
  }

  /**
   * Renders {@code /materials} in German with the given materials from the backend.
   *
   * @param materials the materials the backend returns
   * @return the rendered HTML
   * @throws Exception if the request fails
   */
  private String renderListing(List<MaterialPriceOverviewDto> materials) throws Exception {
    when(backendApiClient.get(
            eq("/api/v1/materials/prices-overview?size=10000&sort=name,asc"), anyTypeRef()))
        .thenReturn(new PageResponse<>(materials, 0, 10000, materials.size(), 1, List.of()));
    return mockMvc
        .perform(get("/materials").locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /**
   * The listing renders on the list pattern (REQ-UI-027): page head with eyebrow and count, the
   * toolbar search, the grouping switch, one collapsed section per category and a hidden empty
   * state.
   */
  @Test
  @WithMockUser
  void listMaterials_rendersTheListPattern() throws Exception {
    String html =
        renderListing(
            List.of(
                new MaterialPriceOverviewDto(
                    UUID.randomUUID(),
                    "Aluminum",
                    new MaterialCategoryDto(UUID.randomUUID(), "Mineral", 0L),
                    false,
                    false,
                    false,
                    new BigDecimal("5.0"),
                    new BigDecimal("7.0"))));

    assertThat(html)
        .contains("class=\"page-head\"")
        .containsPattern("class=\"page-eyebrow\"[^>]*>Handel<")
        .containsPattern("<h1>Material-Übersicht</h1>")
        .containsPattern("data-testid=\"page-head-count\"[^>]*>1<")
        .doesNotContain("class=\"greeting")
        .doesNotContain("class=\"hud-box mt-2\"")
        .doesNotContain("btn--cta")
        .doesNotContain("krtm-display-none")
        .doesNotContain("kind-header");
    assertThat(html)
        .contains("class=\"toolbar__search\"")
        .containsPattern("type=\"search\" id=\"materialFilter\" data-trigger=\"materials-filter\"")
        .containsPattern(
            "<label class=\"switch\"[^>]*>\\s*<input type=\"checkbox\" id=\"groupByCategory\"")
        .containsPattern(
            "class=\"kind-toggle\"[^>]*aria-expanded=\"false\"[^>]*aria-controls=\"materials-kind-0\"")
        .contains(">Mineral<")
        .containsPattern("id=\"materials-kind-0\" hidden")
        .containsPattern("id=\"materialsFlat\" class=\"grid-auto-cards\" hidden")
        .containsPattern("id=\"noResultsMsg\"[^>]*hidden")
        .contains("data-testid=\"empty-state\"");
  }

  /** An empty catalogue shows the empty state right away. */
  @Test
  @WithMockUser
  void listMaterials_withoutMaterials_showsTheEmptyState() throws Exception {
    String html = renderListing(List.of());

    assertThat(html)
        .contains("id=\"noResultsMsg\" class=\"card card--flush\">")
        .contains("data-testid=\"empty-state\"")
        .contains("Keine Materialien gefunden.")
        .containsPattern("data-testid=\"page-head-count\"[^>]*>0<");
  }

  /**
   * The matrix-overview shell ({@code GET /materials/overview}) must render the category-grouping
   * toggle checkbox. The flat-vs-grouped switch itself is applied client-side by {@code
   * /js/materials-matrix.js}; this test only pins that the control the script binds to is present
   * in the shipped shell.
   */
  @Test
  @WithMockUser
  void getMatrixOverview_rendersGroupByCategoryToggle() throws Exception {
    PageResponse<MaterialMatrixItemDto> emptyPage =
        new PageResponse<>(List.of(), 0, 100000, 0, 0, List.of());
    when(backendApiClient.getCached(eq(CachedCatalog.MATERIALS_MATRIX), anyTypeRef()))
        .thenReturn(emptyPage);

    mockMvc
        .perform(get("/materials/overview"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"filterGroupByCategory\"")))
        .andExpect(content().string(containsString("</html>")));
  }

  /**
   * The detail page renders completely with its script, the terminal search and sort controls, and
   * the planet-stripe stylesheet.
   */
  @Test
  @WithMockUser
  void getMaterialDetail_ShouldRenderSearchAndSortControls() throws Exception {
    UUID id = UUID.randomUUID();
    stubDetail(
        material(id, null, false, false),
        List.of(price("Area18", new BigDecimal("5.0"), new BigDecimal("7.0"))));

    mockMvc
        .perform(get("/materials/" + id))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("src=\"/js/material-detail.js\"")))
        .andExpect(content().string(containsString("</body>")))
        .andExpect(content().string(containsString("</html>")))
        .andExpect(content().string(containsString("id=\"terminalFilter\"")))
        .andExpect(content().string(containsString("name=\"terminalSort\"")))
        .andExpect(content().string(containsString("data-terminal=\"Area18\"")))
        .andExpect(PageStylesheets.content(containsString(".md-terminal-row.planet-hurston")));
  }

  /**
   * The detail page sits on the detail pattern: back-link eyebrow, category and flag chips, four
   * price figures and the terminal table sorted by sale price, with a planet tint per row.
   */
  @Test
  @WithMockUser
  void getMaterialDetail_rendersHeadChipsFiguresAndSortedTable() throws Exception {
    UUID id = UUID.randomUUID();
    stubDetail(
        material(id, new MaterialCategoryDto(UUID.randomUUID(), "Mineral", 0L), true, true),
        List.of(
            price("Lorville CBD", null, new BigDecimal("88.2")),
            price("Area18 TDD", new BigDecimal("80"), new BigDecimal("89.6"))));
    when(backendApiClient.getCached(eq(CachedCatalog.MATERIALS_MATRIX), anyTypeRef()))
        .thenReturn(
            new PageResponse<>(
                List.of(matrixItem(id, "Area18 TDD", "ArcCorp", "Area18")),
                0,
                100000,
                1,
                1,
                List.of()));

    String html =
        mockMvc
            .perform(get("/materials/" + id).locale(Locale.GERMAN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains("class=\"page-head\"")
        .containsPattern("<a class=\"page-eyebrow\" href=\"/materials\"")
        .containsPattern("<h1>Aluminum</h1>")
        .containsPattern("class=\"chip\"\\s+data-testid=\"material-category\">Mineral<")
        .contains("class=\"chip chip--danger\" data-testid=\"material-flag-illegal\"")
        .contains("class=\"chip chip--warning\" data-testid=\"material-flag-volatile-qt\"")
        .doesNotContain("material-flag-volatile-time")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box")
        .doesNotContain("krtm-")
        .doesNotContain("colspan")
        .contains("data-testid=\"kpi-best-sell\"")
        .contains("data-testid=\"kpi-best-buy\"")
        .contains("data-testid=\"kpi-average-sell\"")
        .contains("data-testid=\"kpi-updated\"")
        .containsPattern("class=\"kpi-value\">89,6<")
        .containsPattern("class=\"kpi-value\">80<")
        .containsPattern("class=\"kpi-value\">88,9<")
        .contains("data-table data-table--stack")
        .containsPattern("md-terminal-row planet-arccorp\"\\s+data-terminal=\"Area18 TDD\"")
        .contains(">ArcCorp · Area18<");
    assertThat(html.indexOf("data-terminal=\"Area18 TDD\""))
        .isLessThan(html.indexOf("data-terminal=\"Lorville CBD\""));
  }

  /** A material without prices shows the empty state instead of a table. */
  @Test
  @WithMockUser
  void getMaterialDetail_withoutPrices_showsTheEmptyState() throws Exception {
    UUID id = UUID.randomUUID();
    stubDetail(material(id, null, false, false), List.of());

    String html =
        mockMvc
            .perform(get("/materials/" + id).locale(Locale.GERMAN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains("id=\"noDataRow\" class=\"card card--flush\"")
        .contains("data-testid=\"empty-state\"")
        .contains("Keine Preisdaten verfügbar.")
        .doesNotContain("id=\"priceTable\"");
  }

  /**
   * The price overview renders on the data-view pattern: page head with the UEX freshness chip, the
   * dropdown filters with their summaries, the add-filter popover with the boolean filters, the
   * grouping switch, the legend and the hidden matrix card.
   */
  @Test
  @WithMockUser
  void getMatrixOverview_rendersTheDataViewPattern() throws Exception {
    when(backendApiClient.getCached(eq(CachedCatalog.MATERIALS_MATRIX), anyTypeRef()))
        .thenReturn(
            new PageResponse<>(
                List.of(matrixItem(UUID.randomUUID(), "Area18 TDD", "ArcCorp", "Area18")),
                0,
                100000,
                1,
                1,
                List.of()));
    when(backendApiClient.getCached(eq(CachedCatalog.TERMINALS), anyTypeRef()))
        .thenReturn(
            new PageResponse<>(
                List.of(
                    Map.<String, Object>of(
                        "uexSyncedAt", Instant.now().minus(Duration.ofMinutes(14)).toString())),
                0,
                10000,
                1,
                1,
                List.of()));

    String html =
        mockMvc
            .perform(get("/materials/overview").locale(Locale.GERMAN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains("class=\"page-head\"")
        .containsPattern("class=\"page-eyebrow\"[^>]*>Handel<")
        .containsPattern("<h1>Preis-Übersicht</h1>")
        .containsPattern("data-testid=\"uex-age\"[^>]*>UEX · vor 1[34] min<")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box")
        .doesNotContain("krtm-")
        .doesNotContain("btn--cta")
        .containsPattern("id=\"materialHeader\"[^>]*aria-controls=\"materialOptions\"")
        .containsPattern("id=\"systemHeader\"[^>]*aria-controls=\"systemOptions\"")
        .containsPattern("id=\"materialOptions\"[^>]*data-filter-transient")
        .containsPattern(
            "class=\"filter-add filter-toggle\" data-testid=\"materials-filter-toggle\"")
        .containsPattern(
            "class=\"filter-panel filter-popover__panel\" id=\"materials-filter-panel\"")
        .contains("id=\"filterLoadingDock\"")
        .contains("id=\"filterAutoLoad\"")
        .containsPattern(
            "<label class=\"switch\"[^>]*>\\s*<input type=\"checkbox\""
                + " id=\"filterGroupByCategory\"")
        .contains("data-testid=\"materials-matrix-legend\"")
        .contains(">bester Verkauf<")
        .contains(">bester Einkauf<")
        .containsPattern("id=\"tableContainer\" hidden")
        .containsPattern("id=\"matrixEmpty\" class=\"card card--flush\" hidden")
        .contains("data-label-selection-of=\"{0} von {1}\"");
  }

  /**
   * Stubs the detail page's material and price list.
   *
   * @param material the material the backend returns
   * @param prices the price list the backend returns
   */
  private void stubDetail(MaterialDto material, List<MaterialPriceDto> prices) {
    when(backendApiClient.get(
            eq("/api/v1/materials/{id}"), eq(MaterialDto.class), eq(material.id())))
        .thenReturn(material);
    when(backendApiClient.get(
            eq("/api/v1/materials/{id}/prices?size=10000&sort=terminal.name,asc&page={page}"),
            anyTypeRef(),
            eq(material.id()),
            eq(0)))
        .thenReturn(new PageResponse<>(prices, 0, 10000, prices.size(), 1, List.of()));
  }

  /**
   * A catalogue material named Aluminum.
   *
   * @param id the material id
   * @param category the category, or {@code null}
   * @param illegal whether it is flagged illegal
   * @param volatileQt whether it is flagged volatile in quantum travel
   * @return the material
   */
  private static MaterialDto material(
      UUID id, MaterialCategoryDto category, boolean illegal, boolean volatileQt) {
    return new MaterialDto(
        id,
        "Aluminum",
        "RAW",
        "SCU",
        "Aluminum description",
        null,
        category,
        illegal,
        volatileQt,
        false,
        false,
        false,
        false,
        true,
        0L);
  }

  /**
   * One terminal price.
   *
   * @param terminal the terminal name
   * @param buy the purchase price, or {@code null}
   * @param sell the sale price, or {@code null}
   * @return the price
   */
  private static MaterialPriceDto price(String terminal, BigDecimal buy, BigDecimal sell) {
    return new MaterialPriceDto(UUID.randomUUID(), terminal, buy, sell, 100, 200, true, true);
  }

  /**
   * One price-matrix row placing a terminal of the material on a planet and in a city.
   *
   * @param materialId the material
   * @param terminal the terminal name
   * @param planet the planet
   * @param city the city
   * @return the matrix row
   */
  private static MaterialMatrixItemDto matrixItem(
      UUID materialId, String terminal, String planet, String city) {
    return new MaterialMatrixItemDto(
        materialId,
        "Aluminum",
        false,
        false,
        false,
        null,
        UUID.randomUUID(),
        terminal,
        terminal,
        "Stanton",
        new BigDecimal("80"),
        new BigDecimal("89.6"),
        city,
        null,
        null,
        planet,
        false,
        true,
        true);
  }
}
