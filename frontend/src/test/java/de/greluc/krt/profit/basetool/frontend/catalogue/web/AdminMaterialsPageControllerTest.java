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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.catalogue.client.CatalogueBackendClient;
import de.greluc.krt.profit.basetool.frontend.catalogue.client.CatalogueListBackendClient;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialCreateAjaxRequest;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialUpdateAjaxRequest;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CacheDomain;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CatalogueCacheEviction;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

@SuppressWarnings("unchecked")
class AdminMaterialsPageControllerTest {

  private static final String LIST_TEMPLATE =
      "/api/v1/materials?includeHidden=true&sort=name,asc&page={page}&size={size}";

  @Test
  void updateMaterialAjax_ShouldUpdateAndReturnMaterial() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    AdminMaterialsPageController controller =
        new AdminMaterialsPageController(
            new CatalogueBackendClient(backendApiClient),
            new CatalogueCacheEviction(backendApiClient),
            new CatalogueListBackendClient(backendApiClient));

    UUID matId = UUID.randomUUID();
    MaterialDto currentMaterial =
        new MaterialDto(
            matId, "Alpha", "RAW", "SCU", "Desc", null, null, false, false, false, false, false,
            false, true, 1L);
    MaterialDto updatedMaterial =
        new MaterialDto(
            matId, "Alpha", "RAW", "PIECE", "Desc", null, null, false, false, false, false, false,
            false, true, 2L);

    when(backendApiClient.get("/api/v1/materials/{id}", MaterialDto.class, matId))
        .thenReturn(currentMaterial)
        .thenReturn(updatedMaterial);

    MaterialUpdateAjaxRequest request =
        new MaterialUpdateAjaxRequest("QUANTITY_TYPE", null, null, "PIECE", null, null, null, 1L);

    ResponseEntity<MaterialDto> response = controller.updateMaterialAjax(matId, request);

    assertEquals(200, response.getStatusCode().value());
    assertEquals(updatedMaterial, response.getBody());
    verify(backendApiClient).evict(CacheDomain.MATERIAL);
  }

  @Test
  void listMaterials_ShouldSortListsAscendingByName() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    AdminMaterialsPageController controller =
        new AdminMaterialsPageController(
            new CatalogueBackendClient(backendApiClient),
            new CatalogueCacheEviction(backendApiClient),
            new CatalogueListBackendClient(backendApiClient));
    Model model = new ConcurrentModel();

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
            eq("/api/v1/materials?size=1000&sort=name,asc&includeHidden=true&page={page}"),
            anyTypeRef(),
            eq(0)))
        .thenReturn(materialsPage);
    PageResponse<MaterialDto> displayed =
        new PageResponse<>(materials.subList(0, 2), 0, 25, 213, 9, List.of("name,asc"));
    when(backendApiClient.get(eq(LIST_TEMPLATE), anyTypeRef(), eq(0), eq(25)))
        .thenReturn(displayed);

    String view = controller.listMaterials(null, null, null, null, model);

    assertEquals("catalogue/admin/materials", view);
    assertEquals(materials.subList(0, 2), model.getAttribute("materials"));
    assertEquals(displayed, model.getAttribute("materialsPage"));
    assertEquals(25, model.getAttribute("pageSize"));
    assertEquals(List.of(25, 50, 100), model.getAttribute("pageSizes"));
    assertEquals("/admin/materials", model.getAttribute("paginationBaseUrl"));

    List<MaterialDto> refinedMats = (List<MaterialDto>) model.getAttribute("refinedMaterials");
    assertEquals(5, refinedMats.size());
    assertEquals("Alpha", refinedMats.get(0).name());
    assertEquals("Bravo", refinedMats.get(1).name());
    assertEquals("Charlie", refinedMats.get(2).name());
    assertEquals("Delta", refinedMats.get(3).name());
    assertEquals("Echo", refinedMats.get(4).name());
    assertEquals(Boolean.FALSE, model.getAttribute("catalogTruncated"));
  }

  @Test
  void listMaterials_concatenatesAllBackendPages() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    AdminMaterialsPageController controller =
        new AdminMaterialsPageController(
            new CatalogueBackendClient(backendApiClient),
            new CatalogueCacheEviction(backendApiClient),
            new CatalogueListBackendClient(backendApiClient));
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
    String template = "/api/v1/materials?size=1000&sort=name,asc&includeHidden=true&page={page}";
    when(backendApiClient.get(eq(template), anyTypeRef(), eq(0)))
        .thenReturn(new PageResponse<>(List.of(first), 0, 1000, 2, 2, List.of("name,asc")));
    when(backendApiClient.get(eq(template), anyTypeRef(), eq(1)))
        .thenReturn(new PageResponse<>(List.of(second), 1, 1000, 2, 2, List.of("name,asc")));

    controller.listMaterials(null, null, null, null, model);

    List<MaterialDto> refined = (List<MaterialDto>) model.getAttribute("refinedMaterials");
    assertEquals(2, refined.size(), "the second backend page must not be dropped");
    assertEquals("Agricium", refined.get(0).name());
    assertEquals("Zeta-Prolanide", refined.get(1).name());
    assertEquals(Boolean.FALSE, model.getAttribute("catalogTruncated"));
  }

  @Test
  void listMaterials_forwardsSearchAndPage_snapsUnsupportedSize_andRendersTheFragment() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    AdminMaterialsPageController controller =
        new AdminMaterialsPageController(
            new CatalogueBackendClient(backendApiClient),
            new CatalogueCacheEviction(backendApiClient),
            new CatalogueListBackendClient(backendApiClient));
    Model model = new ConcurrentModel();
    when(backendApiClient.get(
            eq(LIST_TEMPLATE + "&query={query}"), anyTypeRef(), eq(2), eq(25), eq("Alu ore")))
        .thenReturn(new PageResponse<>(List.of(), 2, 25, 0, 0, List.of()));

    String view = controller.listMaterials("  Alu ore ", 2, 30, "results", model);

    assertEquals("catalogue/admin/materials :: materialsResults", view);
    assertEquals("Alu ore", model.getAttribute("query"));
    assertEquals(25, model.getAttribute("pageSize"));
    assertEquals("/admin/materials?query=Alu%20ore", model.getAttribute("paginationBaseUrl"));
  }

  @Test
  void listMaterials_withoutBackendBody_rendersAnEmptyPage() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    AdminMaterialsPageController controller =
        new AdminMaterialsPageController(
            new CatalogueBackendClient(backendApiClient),
            new CatalogueCacheEviction(backendApiClient),
            new CatalogueListBackendClient(backendApiClient));
    Model model = new ConcurrentModel();

    controller.listMaterials(null, -3, 100, null, model);

    PageResponse<MaterialDto> page =
        (PageResponse<MaterialDto>) model.getAttribute("materialsPage");
    assertEquals(0, page.page());
    assertEquals(100, page.size());
    assertEquals(0L, page.totalElements());
  }

  @Test
  void createMaterialAjax_success_returnsBackendDto() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    AdminMaterialsPageController controller =
        new AdminMaterialsPageController(
            new CatalogueBackendClient(backendApiClient),
            new CatalogueCacheEviction(backendApiClient),
            new CatalogueListBackendClient(backendApiClient));

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

    ResponseEntity<MaterialDto> response = controller.createMaterialAjax(req);

    assertEquals(200, response.getStatusCode().value());
    assertEquals(created, response.getBody());
    verify(backendApiClient).evict(CacheDomain.MATERIAL);
  }

  @Test
  void createMaterialAjax_backendValidationFailure_propagatesStatus() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    AdminMaterialsPageController controller =
        new AdminMaterialsPageController(
            new CatalogueBackendClient(backendApiClient),
            new CatalogueCacheEviction(backendApiClient),
            new CatalogueListBackendClient(backendApiClient));

    MaterialCreateAjaxRequest req =
        new MaterialCreateAjaxRequest(
            "X", "RAW", "SCU", null, null, null, true, false, false, false, false);

    when(backendApiClient.post(eq("/api/v1/materials"), eq(req), eq(MaterialDto.class)))
        .thenThrow(new BackendServiceException("backend rejected", null, 400));

    ResponseEntity<MaterialDto> response = controller.createMaterialAjax(req);

    assertEquals(400, response.getStatusCode().value());
    verify(backendApiClient, org.mockito.Mockito.never()).evict(CacheDomain.MATERIAL);
  }
}
