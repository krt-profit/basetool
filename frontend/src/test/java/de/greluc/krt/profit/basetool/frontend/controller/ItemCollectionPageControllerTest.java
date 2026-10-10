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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.joborder.client.JobOrderBackendClient;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderItemStockEntryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderItemStockGroupDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.LocationReferenceDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.view.CollectionProgress;
import de.greluc.krt.profit.basetool.frontend.view.ItemCollectionGroup;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

/** Unit tests for {@link ItemCollectionPageController} (the Itemsammelübersicht page). */
class ItemCollectionPageControllerTest {

  @Test
  void viewItemCollection_shouldPopulateModelAndReturnTemplate() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    ItemCollectionPageController controller =
        new ItemCollectionPageController(new JobOrderBackendClient(backendApiClient));
    Model model = new ConcurrentModel();
    UUID jobOrderId = UUID.randomUUID();

    List<JobOrderItemStockGroupDto> groups =
        List.of(
            new JobOrderItemStockGroupDto(
                new InventoryGameItemReferenceDto(UUID.randomUUID(), "Cirrus Scope", null, null),
                4,
                0,
                4L,
                List.of(
                    new JobOrderItemStockEntryDto(
                        UUID.randomUUID(), 0L, "Alice", null, null, null, 3L, 3L, true),
                    new JobOrderItemStockEntryDto(
                        UUID.randomUUID(), 0L, "Bob", null, null, null, 1L, 1L, false))));
    List<LocationReferenceDto> locations =
        List.of(new LocationReferenceDto(UUID.randomUUID(), "Port Olisar"));

    when(backendApiClient.get(eq("/api/v1/orders/{id}/item-stock"), anyTypeRef(), eq(jobOrderId)))
        .thenReturn(groups);
    when(backendApiClient.getCached(eq(CachedCatalog.LOCATIONS_LOOKUP), anyTypeRef()))
        .thenReturn(locations);

    String viewName = controller.viewItemCollection(jobOrderId, null, model);

    assertEquals("item-collection", viewName);
    assertEquals(jobOrderId, model.getAttribute("jobOrderId"));
    assertEquals(groups, model.getAttribute("itemStock"));
    List<?> itemGroups = (List<?>) model.getAttribute("itemGroups");
    assertNotNull(itemGroups);
    assertEquals(1, itemGroups.size());
    assertEquals(
        new CollectionProgress(3, 4), ((ItemCollectionGroup) itemGroups.getFirst()).progress());
    assertEquals(new CollectionProgress(3, 4), model.getAttribute("collectionProgress"));
    assertEquals(locations, model.getAttribute("locations"));
  }

  @Test
  void viewItemCollection_shouldReturnFragment_whenFragmentIsResults() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    ItemCollectionPageController controller =
        new ItemCollectionPageController(new JobOrderBackendClient(backendApiClient));
    Model model = new ConcurrentModel();
    UUID jobOrderId = UUID.randomUUID();

    when(backendApiClient.get(eq("/api/v1/orders/{id}/item-stock"), anyTypeRef(), eq(jobOrderId)))
        .thenReturn(List.of());
    when(backendApiClient.getCached(eq(CachedCatalog.LOCATIONS_LOOKUP), anyTypeRef()))
        .thenReturn(List.of());

    String viewName = controller.viewItemCollection(jobOrderId, "results", model);

    assertEquals("item-collection :: collectionResults", viewName);
    assertEquals(jobOrderId, model.getAttribute("jobOrderId"));
  }

  @Test
  void viewItemCollection_shouldHandleBackendErrorForItemStock() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    ItemCollectionPageController controller =
        new ItemCollectionPageController(new JobOrderBackendClient(backendApiClient));
    Model model = new ConcurrentModel();
    UUID jobOrderId = UUID.randomUUID();

    when(backendApiClient.get(eq("/api/v1/orders/{id}/item-stock"), anyTypeRef(), eq(jobOrderId)))
        .thenThrow(new BackendServiceException("Backend error", null, 500));
    when(backendApiClient.getCached(eq(CachedCatalog.LOCATIONS_LOOKUP), anyTypeRef()))
        .thenReturn(List.of());

    String viewName = controller.viewItemCollection(jobOrderId, null, model);

    assertEquals("item-collection", viewName);
    List<?> itemStock = (List<?>) model.getAttribute("itemStock");
    assertNotNull(itemStock);
    assertTrue(itemStock.isEmpty());
  }

  @Test
  void viewItemCollection_shouldHandleBackendErrorForLocations() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    ItemCollectionPageController controller =
        new ItemCollectionPageController(new JobOrderBackendClient(backendApiClient));
    Model model = new ConcurrentModel();
    UUID jobOrderId = UUID.randomUUID();

    when(backendApiClient.get(eq("/api/v1/orders/{id}/item-stock"), anyTypeRef(), eq(jobOrderId)))
        .thenReturn(List.of());
    when(backendApiClient.getCached(eq(CachedCatalog.LOCATIONS_LOOKUP), anyTypeRef()))
        .thenThrow(new BackendServiceException("Backend error", null, 500));

    String viewName = controller.viewItemCollection(jobOrderId, null, model);

    assertEquals("item-collection", viewName);
    List<?> locations = (List<?>) model.getAttribute("locations");
    assertNotNull(locations);
    assertTrue(locations.isEmpty());
  }
}
