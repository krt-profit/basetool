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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialMatrixItemDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialPriceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialPriceOverviewDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MatrixGridDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

@SuppressWarnings("unchecked")
class MaterialsPageControllerTest {

  @Test
  void listMaterials_ShouldAddMaterialsToModel() {
    // Arrange
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

    // Act
    String viewName = controller.listMaterials(model);

    // Assert
    assertEquals("materials", viewName);
    List<MaterialPriceOverviewDto> materials =
        (List<MaterialPriceOverviewDto>) model.getAttribute("materials");
    assertNotNull(materials);
    assertEquals(1, materials.size());
    assertEquals("Gold", materials.get(0).name());
  }

  @Test
  void listMaterials_ShouldHandleErrorAndAddEmptyListToModel() {
    // Arrange
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MaterialsPageController controller = new MaterialsPageController(backendApiClient);
    Model model = new ConcurrentModel();

    when(backendApiClient.get(
            eq("/api/v1/materials/prices-overview?size=10000&sort=name,asc"), anyTypeRef()))
        .thenThrow(new RuntimeException("API Error"));

    // Act
    String viewName = controller.listMaterials(model);

    // Assert
    assertEquals("materials", viewName);
    List<MaterialPriceOverviewDto> materials =
        (List<MaterialPriceOverviewDto>) model.getAttribute("materials");
    assertNotNull(materials);
    assertTrue(materials.isEmpty());
    assertEquals("error.materials.load", model.getAttribute("error"));
  }

  @Test
  void getMaterialDetail_ShouldAddMaterialAndPricesToModel() {
    // Arrange
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
        new PageResponse<>(List.of(priceDto), 0, 1000, 1, 1, Collections.emptyList());

    when(backendApiClient.get(
            eq("/api/v1/materials/" + id + "/prices?size=1000&sort=terminal.name,asc"),
            anyTypeRef()))
        .thenReturn(pageResponse);

    // Act
    String viewName = controller.getMaterialDetail(id, model);

    // Assert
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
    // Arrange
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MaterialsPageController controller = new MaterialsPageController(backendApiClient);
    Model model = new ConcurrentModel();
    UUID id = UUID.randomUUID();

    when(backendApiClient.get(eq("/api/v1/materials/" + id), eq(MaterialDto.class)))
        .thenThrow(new RuntimeException("API Detail Error"));

    // Act
    String viewName = controller.getMaterialDetail(id, model);

    // Assert
    assertEquals("material-detail", viewName);
    assertEquals("error.material.details.load", model.getAttribute("error"));
    assertTrue(((List<MaterialPriceDto>) model.getAttribute("prices")).isEmpty());
  }

  @Test
  void getMatrixOverview_ShouldPopulateFilterLists() {
    // Arrange
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MaterialsPageController controller = new MaterialsPageController(backendApiClient);
    Model model = new ConcurrentModel();

    when(backendApiClient.getCached(eq("/api/v1/materials/matrix?size=100000"), anyTypeRef()))
        .thenReturn(matrixPage());

    // Act — the shell endpoint only derives the filter source lists; the grid loads separately.
    String viewName = controller.getMatrixOverview(model);

    // Assert
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
    // Arrange — three terminals: Hurston (canonical), unknown planet (hash fallback),
    // no planet at all (Lagrange-style, must land in planet-unknown and sort to the tail).
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MaterialsPageController controller = new MaterialsPageController(backendApiClient);

    when(backendApiClient.getCached(eq("/api/v1/materials/matrix?size=100000"), anyTypeRef()))
        .thenReturn(matrixPage());

    // Act
    MatrixGridDto grid = controller.getMatrixData();

    // Assert
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

    // Sort assertion: terminals with a planet come before planet-less ones inside the same
    // star system. HUR-L1 and FAKE-1 may swap depending on planet-name alphabetical order, but
    // JP-Lagrange must be last.
    assertEquals("JP-Lagrange", grid.terminals().get(grid.terminals().size() - 1).name());
  }

  @Test
  void getMatrixData_ShouldReturnEmptyGridOnError() {
    // Arrange
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MaterialsPageController controller = new MaterialsPageController(backendApiClient);

    when(backendApiClient.getCached(startsWith("/api/v1/materials/matrix"), anyTypeRef()))
        .thenThrow(new RuntimeException("backend down"));

    // Act
    MatrixGridDto grid = controller.getMatrixData();

    // Assert — failures degrade to an empty grid so the client shows its no-results state.
    assertNotNull(grid);
    assertTrue(grid.terminals().isEmpty());
    assertTrue(grid.groups().isEmpty());
  }

  @Test
  void getMatrixOverview_ShouldHandleBackendError() {
    // Arrange
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MaterialsPageController controller = new MaterialsPageController(backendApiClient);
    Model model = new ConcurrentModel();

    when(backendApiClient.getCached(startsWith("/api/v1/materials/matrix"), anyTypeRef()))
        .thenThrow(new RuntimeException("backend down"));

    // Act
    String viewName = controller.getMatrixOverview(model);

    // Assert
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
