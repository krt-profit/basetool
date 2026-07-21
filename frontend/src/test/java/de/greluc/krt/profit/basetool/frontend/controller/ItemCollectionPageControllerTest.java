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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.LocationReferenceDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

/** Unit tests for {@link ItemCollectionPageController} (the Itemsammelübersicht page). */
class ItemCollectionPageControllerTest {

  @Test
  void viewItemCollection_shouldPopulateModelAndReturnTemplate() {
    // Given
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    ItemCollectionPageController controller = new ItemCollectionPageController(backendApiClient);
    Model model = new ConcurrentModel();
    UUID jobOrderId = UUID.randomUUID();

    List<Map<String, Object>> groups = List.of(Map.of("gameItem", Map.of("name", "Cirrus Scope")));
    List<LocationReferenceDto> locations =
        List.of(new LocationReferenceDto(UUID.randomUUID(), "Port Olisar"));

    when(backendApiClient.get(contains("/item-stock"), anyTypeRef())).thenReturn(groups);
    when(backendApiClient.getCached(eq(CachedCatalog.LOCATIONS_LOOKUP), anyTypeRef()))
        .thenReturn(locations);

    // When
    String viewName = controller.viewItemCollection(jobOrderId, null, model);

    // Then
    assertEquals("item-collection", viewName);
    assertEquals(jobOrderId, model.getAttribute("jobOrderId"));
    assertEquals(groups, model.getAttribute("itemStock"));
    assertEquals(locations, model.getAttribute("locations"));
  }

  @Test
  void viewItemCollection_shouldReturnFragment_whenFragmentIsResults() {
    // Given
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    ItemCollectionPageController controller = new ItemCollectionPageController(backendApiClient);
    Model model = new ConcurrentModel();
    UUID jobOrderId = UUID.randomUUID();

    when(backendApiClient.get(contains("/item-stock"), anyTypeRef())).thenReturn(List.of());
    when(backendApiClient.getCached(eq(CachedCatalog.LOCATIONS_LOOKUP), anyTypeRef()))
        .thenReturn(List.of());

    // When — the live-sync receiver (REQ-FE-010) re-fetches ?fragment=results to swap the table.
    String viewName = controller.viewItemCollection(jobOrderId, "results", model);

    // Then — only the collectionResults fragment is rendered, with the model still populated.
    assertEquals("item-collection :: collectionResults", viewName);
    assertEquals(jobOrderId, model.getAttribute("jobOrderId"));
  }

  @Test
  void viewItemCollection_shouldHandleBackendErrorForItemStock() {
    // Given
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    ItemCollectionPageController controller = new ItemCollectionPageController(backendApiClient);
    Model model = new ConcurrentModel();
    UUID jobOrderId = UUID.randomUUID();

    when(backendApiClient.get(contains("/item-stock"), anyTypeRef()))
        .thenThrow(new BackendServiceException("Backend error", null, 500));
    when(backendApiClient.getCached(eq(CachedCatalog.LOCATIONS_LOOKUP), anyTypeRef()))
        .thenReturn(List.of());

    // When
    String viewName = controller.viewItemCollection(jobOrderId, null, model);

    // Then — the page still renders with an empty stock list rather than failing outright.
    assertEquals("item-collection", viewName);
    List<?> itemStock = (List<?>) model.getAttribute("itemStock");
    assertNotNull(itemStock);
    assertTrue(itemStock.isEmpty());
  }

  @Test
  void viewItemCollection_shouldHandleBackendErrorForLocations() {
    // Given
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    ItemCollectionPageController controller = new ItemCollectionPageController(backendApiClient);
    Model model = new ConcurrentModel();
    UUID jobOrderId = UUID.randomUUID();

    when(backendApiClient.get(contains("/item-stock"), anyTypeRef())).thenReturn(List.of());
    when(backendApiClient.getCached(eq(CachedCatalog.LOCATIONS_LOOKUP), anyTypeRef()))
        .thenThrow(new BackendServiceException("Backend error", null, 500));

    // When
    String viewName = controller.viewItemCollection(jobOrderId, null, model);

    // Then
    assertEquals("item-collection", viewName);
    List<?> locations = (List<?>) model.getAttribute("locations");
    assertNotNull(locations);
    assertTrue(locations.isEmpty());
  }
}
