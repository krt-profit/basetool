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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.catalogue.client.CatalogueBackendClient;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialMatrixItemDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialPriceDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialPriceOverviewDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MatrixGridDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.TerminalDto;
import de.greluc.krt.profit.basetool.frontend.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

@SuppressWarnings("unchecked")
class MaterialsPageControllerTest {

  @Test
  void listMaterials_ShouldAddMaterialsToModel() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MaterialsPageController controller =
        new MaterialsPageController(new CatalogueBackendClient(backendApiClient));
    Model model = new ConcurrentModel();

    MaterialPriceOverviewDto dto =
        new MaterialPriceOverviewDto(
            UUID.randomUUID(),
            "Gold",
            new de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialCategoryDto(
                UUID.randomUUID(), "Mineral", 0L),
            false,
            false,
            false,
            new BigDecimal("5.0"),
            new BigDecimal("7.0"));
    PageResponse<MaterialPriceOverviewDto> pageResponse =
        new PageResponse<>(List.of(dto), 0, 10000, 1, 1, Collections.emptyList());

    when(backendApiClient.get(
            eq("/api/v1/materials/prices-overview?size=10000&sort=name,asc"), anyTypeRef()))
        .thenReturn(pageResponse);

    String viewName = controller.listMaterials(model);

    assertEquals("materials", viewName);
    List<MaterialPriceOverviewDto> materials =
        (List<MaterialPriceOverviewDto>) model.getAttribute("materials");
    assertNotNull(materials);
    assertEquals(1, materials.size());
    assertEquals("Gold", materials.get(0).name());
  }

  @Test
  void listMaterials_ShouldHandleErrorAndAddEmptyListToModel() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MaterialsPageController controller =
        new MaterialsPageController(new CatalogueBackendClient(backendApiClient));
    Model model = new ConcurrentModel();

    when(backendApiClient.get(
            eq("/api/v1/materials/prices-overview?size=10000&sort=name,asc"), anyTypeRef()))
        .thenThrow(new RuntimeException("API Error"));

    String viewName = controller.listMaterials(model);

    assertEquals("materials", viewName);
    List<MaterialPriceOverviewDto> materials =
        (List<MaterialPriceOverviewDto>) model.getAttribute("materials");
    assertNotNull(materials);
    assertTrue(materials.isEmpty());
    assertEquals("error.materials.load", model.getAttribute("error"));
  }

  @Test
  void getMaterialDetail_ShouldAddMaterialAndPricesToModel() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MaterialsPageController controller =
        new MaterialsPageController(new CatalogueBackendClient(backendApiClient));
    Model model = new ConcurrentModel();
    UUID id = UUID.randomUUID();

    MaterialDto materialDto =
        new MaterialDto(
            id,
            "Gold",
            "Metal",
            "SCU",
            "Test description",
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
    when(backendApiClient.get(eq("/api/v1/materials/{id}"), eq(MaterialDto.class), eq(id)))
        .thenReturn(materialDto);

    MaterialPriceDto priceDto =
        new MaterialPriceDto(
            UUID.randomUUID(),
            "Area18",
            new BigDecimal("5.0"),
            new BigDecimal("7.0"),
            100,
            200,
            true,
            true);
    PageResponse<MaterialPriceDto> pageResponse =
        new PageResponse<>(List.of(priceDto), 0, 10000, 1, 1, Collections.emptyList());

    when(backendApiClient.get(
            eq("/api/v1/materials/{id}/prices?size=10000&sort=terminal.name,asc&page={page}"),
            anyTypeRef(),
            eq(id),
            eq(0)))
        .thenReturn(pageResponse);

    String viewName = controller.getMaterialDetail(id, model);

    assertEquals("material-detail", viewName);
    MaterialDto material = (MaterialDto) model.getAttribute("material");
    assertNotNull(material);
    assertEquals("Gold", material.name());

    List<MaterialPriceDto> prices = (List<MaterialPriceDto>) model.getAttribute("prices");
    assertNotNull(prices);
    assertEquals(1, prices.size());
    assertEquals("Area18", prices.get(0).terminalName());

    List<MaterialTerminalPrices.Row> rows =
        (List<MaterialTerminalPrices.Row>) model.getAttribute("terminalRows");
    assertNotNull(rows);
    assertEquals("Area18", rows.get(0).terminalName());
    MaterialTerminalPrices.Summary summary =
        (MaterialTerminalPrices.Summary) model.getAttribute("priceSummary");
    assertNotNull(summary);
    assertEquals(0, new BigDecimal("7.0").compareTo(summary.bestSell()));
    assertEquals(0, new BigDecimal("5.0").compareTo(summary.bestBuy()));
  }

  @Test
  void getMatrixOverview_addsUexAgeFromTheTerminalCatalogue() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MaterialsPageController controller =
        new MaterialsPageController(new CatalogueBackendClient(backendApiClient));
    Model model = new ConcurrentModel();
    when(backendApiClient.getCached(eq(CachedCatalog.MATERIALS_MATRIX), anyTypeRef()))
        .thenReturn(matrixPage());
    when(backendApiClient.getCached(eq(CachedCatalog.TERMINALS), anyTypeRef()))
        .thenReturn(
            new PageResponse<>(
                List.of(
                    new TerminalDto(
                        UUID.fromString("0d6b3e1a-7c42-4f58-9a1e-3b5c7d9e2f40"),
                        "Area 18 TDD",
                        null,
                        "Stanton",
                        "ArcCorp",
                        "Area18",
                        null,
                        true,
                        false,
                        false,
                        false,
                        true,
                        false,
                        Instant.parse("2020-01-01T00:00:00Z"),
                        false)),
                0,
                10000,
                1,
                1,
                Collections.emptyList()));

    controller.getMatrixOverview(model);

    UexAge age = (UexAge) model.getAttribute("uexAge");
    assertNotNull(age);
    assertEquals("days", age.unit());
  }

  @Test
  void getMatrixOverview_withoutTerminalCatalogue_rendersWithoutUexAge() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MaterialsPageController controller =
        new MaterialsPageController(new CatalogueBackendClient(backendApiClient));
    Model model = new ConcurrentModel();
    when(backendApiClient.getCached(eq(CachedCatalog.MATERIALS_MATRIX), anyTypeRef()))
        .thenReturn(matrixPage());
    when(backendApiClient.getCached(eq(CachedCatalog.TERMINALS), anyTypeRef()))
        .thenThrow(new RuntimeException("terminals down"));

    assertEquals("materials-overview", controller.getMatrixOverview(model));
    assertFalse(model.getAttribute("uexAge") instanceof UexAge);
    assertFalse(model.containsAttribute("error"));
  }

  @Test
  void getMaterialDetail_ShouldHandleErrorAndAddEmptyDataToModel() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MaterialsPageController controller =
        new MaterialsPageController(new CatalogueBackendClient(backendApiClient));
    Model model = new ConcurrentModel();
    UUID id = UUID.randomUUID();

    when(backendApiClient.get(eq("/api/v1/materials/{id}"), eq(MaterialDto.class), eq(id)))
        .thenThrow(new RuntimeException("API Detail Error"));

    String viewName = controller.getMaterialDetail(id, model);

    assertEquals("material-detail", viewName);
    assertEquals("error.material.details.load", model.getAttribute("error"));
    assertTrue(((List<MaterialPriceDto>) model.getAttribute("prices")).isEmpty());
    assertTrue(((List<MaterialTerminalPrices.Row>) model.getAttribute("terminalRows")).isEmpty());
  }

  @Test
  void getMatrixOverview_ShouldPopulateFilterLists() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MaterialsPageController controller =
        new MaterialsPageController(new CatalogueBackendClient(backendApiClient));
    Model model = new ConcurrentModel();

    when(backendApiClient.getCached(eq(CachedCatalog.MATERIALS_MATRIX), anyTypeRef()))
        .thenReturn(matrixPage());

    String viewName = controller.getMatrixOverview(model);

    assertEquals("materials-overview", viewName);
    Collection<String> systems = (Collection<String>) model.getAttribute("starSystems");
    Collection<String> materials = (Collection<String>) model.getAttribute("materialNames");
    assertNotNull(systems);
    assertTrue(systems.contains("Stanton"));
    assertNotNull(materials);
    assertTrue(materials.contains("Aluminum"));
  }

  @Test
  void getMatrixData_ShouldTagTerminalsWithPlanetCssClass() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MaterialsPageController controller =
        new MaterialsPageController(new CatalogueBackendClient(backendApiClient));

    when(backendApiClient.getCached(eq(CachedCatalog.MATERIALS_MATRIX), anyTypeRef()))
        .thenReturn(matrixPage());

    MatrixGridDto grid = controller.getMatrixData(null, null, false, false);

    assertNotNull(grid);
    Map<String, String> classByTerminal = new java.util.HashMap<>();
    for (MatrixGridDto.Column col : grid.terminals()) {
      classByTerminal.put(col.name(), col.planetCssClass());
    }
    assertEquals("planet-hurston", classByTerminal.get("HUR-L1"));
    assertTrue(
        classByTerminal.get("FAKE-1").startsWith("planet-hash-"),
        "expected hash fallback for unknown planet, got: " + classByTerminal.get("FAKE-1"));
    assertEquals(PlanetColorResolver.UNKNOWN_CLASS, classByTerminal.get("JP-Lagrange"));

    assertEquals("JP-Lagrange", grid.terminals().get(grid.terminals().size() - 1).name());
  }

  @Test
  void getMatrixData_ShouldReturnEmptyGridOnError() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MaterialsPageController controller =
        new MaterialsPageController(new CatalogueBackendClient(backendApiClient));

    when(backendApiClient.getCached(eq(CachedCatalog.MATERIALS_MATRIX), anyTypeRef()))
        .thenThrow(new RuntimeException("backend down"));

    MatrixGridDto grid = controller.getMatrixData(null, null, false, false);

    assertNotNull(grid);
    assertTrue(grid.terminals().isEmpty());
    assertTrue(grid.groups().isEmpty());
  }

  @Test
  void getMatrixData_withFilters_relaysFilterParamsToBackendPageWalk() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MaterialsPageController controller =
        new MaterialsPageController(new CatalogueBackendClient(backendApiClient));

    when(backendApiClient.<PageResponse<MaterialMatrixItemDto>>get(
            anyString(), anyTypeRef(), any(Object[].class)))
        .thenReturn(matrixPage());

    MatrixGridDto grid =
        controller.getMatrixData(List.of("Aluminum"), List.of("Stanton"), true, false);

    assertNotNull(grid);
    ArgumentCaptor<String> uriCaptor = ArgumentCaptor.captor();
    verify(backendApiClient)
        .get(uriCaptor.capture(), anyTypeRef(), eq("Aluminum"), eq("Stanton"), eq(0));
    String template = uriCaptor.getValue();
    assertTrue(template.contains("materialNames={materialName}"), template);
    assertTrue(template.contains("starSystems={starSystem}"), template);
    assertTrue(template.contains("hasLoadingDock=true"), template);
    assertTrue(template.contains("page={page}"), template);
    assertFalse(template.contains("isAutoLoad"), template);
    verify(backendApiClient, never()).getCached(eq(CachedCatalog.MATERIALS_MATRIX), anyTypeRef());
  }

  @Test
  void getMatrixOverview_ShouldHandleBackendError() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MaterialsPageController controller =
        new MaterialsPageController(new CatalogueBackendClient(backendApiClient));
    Model model = new ConcurrentModel();

    when(backendApiClient.getCached(eq(CachedCatalog.MATERIALS_MATRIX), anyTypeRef()))
        .thenThrow(new RuntimeException("backend down"));

    String viewName = controller.getMatrixOverview(model);

    assertEquals("materials-overview", viewName);
    assertEquals("error.materials.matrix.load", model.getAttribute("error"));
  }

  /**
   * Three single-material matrix rows across three Stanton terminals — Hurston (canonical planet),
   * an unknown planet (hash-fallback tint), and a planet-less Lagrange jump point (sorts last).
   *
   * @return a one-page matrix response carrying the three rows
   */
  private static PageResponse<MaterialMatrixItemDto> matrixPage() {
    UUID matId = UUID.randomUUID();
    MaterialMatrixItemDto onHurston =
        new MaterialMatrixItemDto(
            matId,
            "Aluminum",
            false,
            false,
            false,
            null,
            UUID.randomUUID(),
            "HUR-L1",
            "HUR-L1",
            "Stanton",
            null,
            new BigDecimal("100"),
            null,
            "HUR-L1 Green Glade Station",
            null,
            "Hurston",
            false,
            true,
            true);
    MaterialMatrixItemDto onUnknownPlanet =
        new MaterialMatrixItemDto(
            matId,
            "Aluminum",
            false,
            false,
            false,
            null,
            UUID.randomUUID(),
            "FAKE-1",
            null,
            "Stanton",
            null,
            new BigDecimal("100"),
            null,
            "Fictional Station",
            null,
            "FictionPlanet",
            false,
            true,
            true);
    MaterialMatrixItemDto noPlanet =
        new MaterialMatrixItemDto(
            matId,
            "Aluminum",
            false,
            false,
            false,
            null,
            UUID.randomUUID(),
            "JP-Lagrange",
            null,
            "Stanton",
            null,
            new BigDecimal("100"),
            null,
            "Lagrange Jump Point",
            null,
            null,
            true,
            false,
            false);
    return new PageResponse<>(
        List.of(onHurston, onUnknownPlanet, noPlanet), 0, 100000, 3, 1, Collections.emptyList());
  }
}
