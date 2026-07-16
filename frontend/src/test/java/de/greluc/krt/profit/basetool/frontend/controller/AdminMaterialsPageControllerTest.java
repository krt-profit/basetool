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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialCreateAjaxRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialUpdateAjaxRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.service.CacheDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

@SuppressWarnings("unchecked")
class AdminMaterialsPageControllerTest {

  @Test
  void updateMaterialAjax_ShouldUpdateAndReturnMaterial() {
    // Arrange
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    AdminMaterialsPageController controller = new AdminMaterialsPageController(backendApiClient);

    UUID matId = UUID.randomUUID();
    MaterialDto currentMaterial =
        new MaterialDto(
            matId, "Alpha", "RAW", "SCU", "Desc", null, null, false, false, false, false, false,
            false, true, 1L);
    MaterialDto updatedMaterial =
        new MaterialDto(
            matId, "Alpha", "RAW", "PIECE", "Desc", null, null, false, false, false, false, false,
            false, true, 2L);

    when(backendApiClient.get("/api/v1/materials/" + matId, MaterialDto.class))
        .thenReturn(currentMaterial)
        .thenReturn(updatedMaterial);

    MaterialUpdateAjaxRequest request =
        new MaterialUpdateAjaxRequest("QUANTITY_TYPE", null, null, "PIECE", null, null, null, 1L);

    // Act
    ResponseEntity<MaterialDto> response = controller.updateMaterialAjax(matId, request);

    // Assert
    assertEquals(200, response.getStatusCode().value());
    assertEquals(updatedMaterial, response.getBody());
    // A QUANTITY_TYPE edit formerly slipped past the updateType guard and never evicted (F5); the
    // fix evicts the MATERIAL domain unconditionally on every material edit.
    verify(backendApiClient).evict(CacheDomain.MATERIAL);
  }

  @Test
  void listMaterials_ShouldSortListsAscendingByName() {
    // Arrange
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    AdminMaterialsPageController controller = new AdminMaterialsPageController(backendApiClient);
    Model model = new ConcurrentModel();

    // Data for Materials
    List<MaterialDto> materials = new ArrayList<>();
    materials.add(
        new MaterialDto(
            UUID.randomUUID(),
            "Alpha",
            "RAW",
            "SCU",
            "Desc",
            null,
            null,
            false,
            false,
            false,
            false,
            false,
            false,
            true,
            0L));
    materials.add(
        new MaterialDto(
            UUID.randomUUID(),
            "Delta",
            "REFINED",
            "SCU",
            "Desc",
            null,
            null,
            false,
            false,
            false,
            false,
            false,
            false,
            true,
            0L));
    materials.add(
        new MaterialDto(
            UUID.randomUUID(),
            "Charlie",
            "RAW",
            "SCU",
            "Desc",
            null,
            null,
            false,
            false,
            false,
            false,
            false,
            false,
            true,
            0L));
    materials.add(
        new MaterialDto(
            UUID.randomUUID(),
            "Echo",
            "NO_REFINE",
            "SCU",
            "Desc",
            null,
            null,
            false,
            false,
            false,
            false,
            false,
            false,
            true,
            0L));
    materials.add(
        new MaterialDto(
            UUID.randomUUID(),
            "Bravo",
            "RAW",
            "SCU",
            "Desc",
            null,
            null,
            false,
            false,
            false,
            false,
            false,
            false,
            true,
            0L));

    PageResponse<MaterialDto> materialsPage =
        new PageResponse<>(materials, 0, 1000, materials.size(), 1, List.of("name,asc"));

    when(backendApiClient.get(
            eq("/api/v1/materials?size=1000&sort=name,asc&includeHidden=true&page=0"),
            anyTypeRef()))
        .thenReturn(materialsPage);

    // Act
    controller.listMaterials(model);

    // Assert
    List<MaterialDto> sortedMaterials = (List<MaterialDto>) model.getAttribute("materials");
    assertEquals(5, sortedMaterials.size());
    assertEquals("Alpha", sortedMaterials.get(0).name());
    assertEquals("Bravo", sortedMaterials.get(1).name());
    assertEquals("Charlie", sortedMaterials.get(2).name());
    assertEquals("Delta", sortedMaterials.get(3).name());
    assertEquals("Echo", sortedMaterials.get(4).name());

    List<MaterialDto> refinedMats = (List<MaterialDto>) model.getAttribute("refinedMaterials");
    assertEquals(5, refinedMats.size());
    assertEquals("Alpha", refinedMats.get(0).name());
    assertEquals("Bravo", refinedMats.get(1).name());
    assertEquals("Charlie", refinedMats.get(2).name());
    assertEquals("Delta", refinedMats.get(3).name());
    assertEquals("Echo", refinedMats.get(4).name());
    assertEquals(Boolean.FALSE, model.getAttribute("catalogTruncated"));
  }

  // covers REQ-ADMIN-001 — materials beyond the first backend page stay visible and editable
  @Test
  void listMaterials_concatenatesAllBackendPages() {
    // Arrange — two backend pages of one material each
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    AdminMaterialsPageController controller = new AdminMaterialsPageController(backendApiClient);
    Model model = new ConcurrentModel();

    MaterialDto first =
        new MaterialDto(
            UUID.randomUUID(),
            "Agricium",
            "RAW",
            "SCU",
            "Desc",
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
    MaterialDto second =
        new MaterialDto(
            UUID.randomUUID(),
            "Zeta-Prolanide",
            "RAW",
            "SCU",
            "Desc",
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
    String base = "/api/v1/materials?size=1000&sort=name,asc&includeHidden=true";
    when(backendApiClient.get(eq(base + "&page=0"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(first), 0, 1000, 2, 2, List.of("name,asc")));
    when(backendApiClient.get(eq(base + "&page=1"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(second), 1, 1000, 2, 2, List.of("name,asc")));

    // Act
    controller.listMaterials(model);

    // Assert — both pages land in the rendered list
    List<MaterialDto> sortedMaterials = (List<MaterialDto>) model.getAttribute("materials");
    assertEquals(2, sortedMaterials.size(), "the second backend page must not be dropped");
    assertEquals("Agricium", sortedMaterials.get(0).name());
    assertEquals("Zeta-Prolanide", sortedMaterials.get(1).name());
    assertEquals(Boolean.FALSE, model.getAttribute("catalogTruncated"));
  }

  @Test
  void createMaterialAjax_success_returnsBackendDto() {
    // Arrange
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    AdminMaterialsPageController controller = new AdminMaterialsPageController(backendApiClient);

    MaterialCreateAjaxRequest req =
        new MaterialCreateAjaxRequest(
            "Raw Ouratite", "RAW", "SCU", null, null, null, true, false, false, false, false);

    MaterialDto created =
        new MaterialDto(
            UUID.randomUUID(),
            "Raw Ouratite",
            "RAW",
            "SCU",
            null,
            null,
            null,
            false,
            false,
            false,
            true,
            false,
            true,
            true,
            0L);

    when(backendApiClient.post(eq("/api/v1/materials"), eq(req), eq(MaterialDto.class)))
        .thenReturn(created);

    // Act
    ResponseEntity<MaterialDto> response = controller.createMaterialAjax(req);

    // Assert
    assertEquals(200, response.getStatusCode().value());
    assertEquals(created, response.getBody());
    verify(backendApiClient).evict(CacheDomain.MATERIAL);
  }

  @Test
  void createMaterialAjax_backendValidationFailure_propagatesStatus() {
    // Arrange — backend returns 400 (validation), client throws BackendServiceException
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    AdminMaterialsPageController controller = new AdminMaterialsPageController(backendApiClient);

    MaterialCreateAjaxRequest req =
        new MaterialCreateAjaxRequest(
            "X", "RAW", "SCU", null, null, null, true, false, false, false, false);

    when(backendApiClient.post(eq("/api/v1/materials"), eq(req), eq(MaterialDto.class)))
        .thenThrow(new BackendServiceException("backend rejected", null, 400));

    // Act
    ResponseEntity<MaterialDto> response = controller.createMaterialAjax(req);

    // Assert — 400 propagates so the JS layer can show a problem-detail toast
    assertEquals(400, response.getStatusCode().value());
    verify(backendApiClient, org.mockito.Mockito.never()).evict(CacheDomain.MATERIAL);
  }
}
