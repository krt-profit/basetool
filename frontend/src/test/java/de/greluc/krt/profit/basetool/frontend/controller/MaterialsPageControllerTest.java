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

import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialMatrixItemDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialPriceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialPriceOverviewDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MatrixGridDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import java.math.BigDecimal;
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
    MaterialsPageController controller = new MaterialsPageController(backendApiClient);
    Model model = new ConcurrentModel();

    MaterialPriceOverviewDto dto =
        new MaterialPriceOverviewDto(
            UUID.randomUUID(),
            "Gold",
            new de.greluc.krt.profit.basetool.frontend.model.dto.MaterialCategoryDto(
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
    MaterialsPageController controller = new MaterialsPageController(backendApiClient);
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
    MaterialsPageController controller = new MaterialsPageController(backendApiClient);
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
    when(backendApiClient.get(eq("/api/v1/materials/" + id), eq(MaterialDto.class)))
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
            eq("/api/v1/materials/" + id + "/prices?size=10000&sort=terminal.name,asc&page=0"),
            anyTypeRef()))
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
  }

  @Test
  void getMaterialDetail_ShouldHandleErrorAndAddEmptyDataToModel() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MaterialsPageController controller = new MaterialsPageController(backendApiClient);
    Model model = new ConcurrentModel();
    UUID id = UUID.randomUUID();

    when(backendApiClient.get(eq("/api/v1/materials/" + id), eq(MaterialDto.class)))
        .thenThrow(new RuntimeException("API Detail Error"));

    String viewName = controller.getMaterialDetail(id, model);

    assertEquals("material-detail", viewName);
    assertEquals("error.material.details.load", model.getAttribute("error"));
    assertTrue(((List<MaterialPriceDto>) model.getAttribute("prices")).isEmpty());
  }

  @Test
  void getMatrixOverview_ShouldPopulateFilterLists() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MaterialsPageController controller = new MaterialsPageController(backendApiClient);
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
    MaterialsPageController controller = new MaterialsPageController(backendApiClient);

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
    MaterialsPageController controller = new MaterialsPageController(backendApiClient);

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
    MaterialsPageController controller = new MaterialsPageController(backendApiClient);

    when(backendApiClient.<PageResponse<MaterialMatrixItemDto>>get(
            anyString(), anyTypeRef(), any(), any()))
        .thenReturn(matrixPage());

    MatrixGridDto grid =
        controller.getMatrixData(List.of("Aluminum"), List.of("Stanton"), true, false);

    assertNotNull(grid);
    ArgumentCaptor<String> uriCaptor = ArgumentCaptor.captor();
    verify(backendApiClient).get(uriCaptor.capture(), anyTypeRef(), any(), any());
    String template = uriCaptor.getValue();
    assertTrue(template.contains("materialNames={f0}"), template);
    assertTrue(template.contains("starSystems={f1}"), template);
    assertTrue(template.contains("hasLoadingDock=true"), template);
    assertTrue(template.contains("page=0"), template);
    assertFalse(template.contains("isAutoLoad"), template);
    verify(backendApiClient, never()).getCached(eq(CachedCatalog.MATERIALS_MATRIX), anyTypeRef());
  }

  @Test
  void getMatrixOverview_ShouldHandleBackendError() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MaterialsPageController controller = new MaterialsPageController(backendApiClient);
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
