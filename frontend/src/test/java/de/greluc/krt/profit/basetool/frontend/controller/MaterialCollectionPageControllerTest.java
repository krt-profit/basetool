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
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.frontend.model.dto.LocationReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialCollectionEntryDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.view.CollectionProgress;
import de.greluc.krt.profit.basetool.frontend.view.MaterialCollectionGroup;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

class MaterialCollectionPageControllerTest {

  /**
   * A collection entry of the given material.
   *
   * @param material the material name
   * @param allocated the amount earmarked to the order
   * @param delivered whether the slice is marked delivered
   * @return the entry
   */
  private static MaterialCollectionEntryDto entry(
      String material, double allocated, boolean delivered) {
    return new MaterialCollectionEntryDto(
        UUID.randomUUID(),
        0L,
        "Alice",
        null,
        null,
        null,
        material,
        500.0,
        allocated,
        allocated,
        delivered);
  }

  @Test
  void viewMaterialCollection_shouldPopulateModelAndReturnTemplate() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MaterialCollectionPageController controller =
        new MaterialCollectionPageController(backendApiClient);
    Model model = new ConcurrentModel();
    UUID jobOrderId = UUID.randomUUID();

    List<MaterialCollectionEntryDto> entries =
        List.of(
            entry("Laranite", 2.0, true),
            entry("Agricium", 1.0, false),
            entry("Laranite", 6.0, false));
    List<LocationReferenceDto> locations =
        List.of(new LocationReferenceDto(UUID.randomUUID(), "Port Olisar"));

    when(backendApiClient.get(
            eq("/api/v1/orders/{id}/material-collection"), anyTypeRef(), eq(jobOrderId)))
        .thenReturn(entries);
    when(backendApiClient.getCached(eq(CachedCatalog.LOCATIONS_LOOKUP), anyTypeRef()))
        .thenReturn(locations);

    String viewName = controller.viewMaterialCollection(jobOrderId, null, model);

    assertEquals("material-collection", viewName);
    assertEquals(jobOrderId, model.getAttribute("jobOrderId"));
    assertEquals(entries, model.getAttribute("entries"));
    List<?> groups = (List<?>) model.getAttribute("materialGroups");
    assertNotNull(groups);
    assertEquals(2, groups.size());
    MaterialCollectionGroup laranite = (MaterialCollectionGroup) groups.getFirst();
    assertEquals("Laranite", laranite.materialName());
    assertEquals(2, laranite.entries().size());
    assertEquals(new CollectionProgress(2.0, 8.0), laranite.progress());
    assertEquals(new CollectionProgress(2.0, 9.0), model.getAttribute("collectionProgress"));
    assertNull(model.getAttribute("users"));
    assertEquals(locations, model.getAttribute("locations"));
  }

  @Test
  void viewMaterialCollection_shouldReturnFragment_whenFragmentIsResults() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MaterialCollectionPageController controller =
        new MaterialCollectionPageController(backendApiClient);
    Model model = new ConcurrentModel();
    UUID jobOrderId = UUID.randomUUID();

    when(backendApiClient.get(
            eq("/api/v1/orders/{id}/material-collection"), anyTypeRef(), eq(jobOrderId)))
        .thenReturn(List.of());
    when(backendApiClient.getCached(eq(CachedCatalog.LOCATIONS_LOOKUP), anyTypeRef()))
        .thenReturn(List.of());

    String viewName = controller.viewMaterialCollection(jobOrderId, "results", model);

    assertEquals("material-collection :: collectionResults", viewName);
    assertEquals(jobOrderId, model.getAttribute("jobOrderId"));
  }

  @Test
  void viewMaterialCollection_shouldHandleBackendErrorForEntries() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MaterialCollectionPageController controller =
        new MaterialCollectionPageController(backendApiClient);
    Model model = new ConcurrentModel();
    UUID jobOrderId = UUID.randomUUID();

    when(backendApiClient.get(
            eq("/api/v1/orders/{id}/material-collection"), anyTypeRef(), eq(jobOrderId)))
        .thenThrow(new BackendServiceException("Backend error", null, 500));
    when(backendApiClient.getCached(eq(CachedCatalog.LOCATIONS_LOOKUP), anyTypeRef()))
        .thenReturn(List.of());

    String viewName = controller.viewMaterialCollection(jobOrderId, null, model);

    assertEquals("material-collection", viewName);
    List<?> entries = (List<?>) model.getAttribute("entries");
    assertNotNull(entries);
    assertTrue(entries.isEmpty());
  }

  @Test
  void viewMaterialCollection_shouldHandleBackendErrorForLocations() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MaterialCollectionPageController controller =
        new MaterialCollectionPageController(backendApiClient);
    Model model = new ConcurrentModel();
    UUID jobOrderId = UUID.randomUUID();

    when(backendApiClient.get(
            eq("/api/v1/orders/{id}/material-collection"), anyTypeRef(), eq(jobOrderId)))
        .thenReturn(List.of());
    when(backendApiClient.getCached(eq(CachedCatalog.LOCATIONS_LOOKUP), anyTypeRef()))
        .thenThrow(new BackendServiceException("Backend error", null, 500));

    String viewName = controller.viewMaterialCollection(jobOrderId, null, model);

    assertEquals("material-collection", viewName);
    List<?> locations = (List<?>) model.getAttribute("locations");
    assertNotNull(locations);
    assertTrue(locations.isEmpty());
  }
}
