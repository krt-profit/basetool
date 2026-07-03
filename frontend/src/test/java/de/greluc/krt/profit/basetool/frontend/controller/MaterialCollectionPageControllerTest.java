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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.frontend.model.dto.LocationReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserReferenceDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

class MaterialCollectionPageControllerTest {

  @Test
  void viewMaterialCollection_shouldPopulateModelAndReturnTemplate() {
    // Given
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MaterialCollectionPageController controller =
        new MaterialCollectionPageController(backendApiClient);
    Model model = new ConcurrentModel();
    UUID jobOrderId = UUID.randomUUID();

    List<Map<String, Object>> entries = List.of(Map.of("materialName", "Laranite"));
    List<UserReferenceDto> users =
        List.of(new UserReferenceDto(UUID.randomUUID(), "user1", "User One", "User One", 1));
    List<LocationReferenceDto> locations =
        List.of(new LocationReferenceDto(UUID.randomUUID(), "Port Olisar"));

    when(backendApiClient.get(contains("/material-collection"), anyTypeRef())).thenReturn(entries);
    when(backendApiClient.get(contains("/users/lookup"), anyTypeRef())).thenReturn(users);
    when(backendApiClient.getCached(contains("/locations/lookup"), anyTypeRef()))
        .thenReturn(locations);

    // When
    String viewName = controller.viewMaterialCollection(jobOrderId, model);

    // Then
    assertEquals("material-collection", viewName);
    assertEquals(jobOrderId, model.getAttribute("jobOrderId"));
    assertEquals(entries, model.getAttribute("entries"));
    assertEquals(users, model.getAttribute("users"));
    assertEquals(locations, model.getAttribute("locations"));
  }

  @Test
  void viewMaterialCollection_shouldHandleBackendErrorForEntries() {
    // Given
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MaterialCollectionPageController controller =
        new MaterialCollectionPageController(backendApiClient);
    Model model = new ConcurrentModel();
    UUID jobOrderId = UUID.randomUUID();

    when(backendApiClient.get(contains("/material-collection"), anyTypeRef()))
        .thenThrow(new BackendServiceException("Backend error", null, 500));
    when(backendApiClient.get(contains("/users/lookup"), anyTypeRef())).thenReturn(List.of());
    when(backendApiClient.getCached(contains("/locations/lookup"), anyTypeRef()))
        .thenReturn(List.of());

    // When
    String viewName = controller.viewMaterialCollection(jobOrderId, model);

    // Then
    assertEquals("material-collection", viewName);
    List<?> entries = (List<?>) model.getAttribute("entries");
    assertNotNull(entries);
    assertTrue(entries.isEmpty());
  }

  @Test
  void viewMaterialCollection_shouldHandleBackendErrorForUsers() {
    // Given
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MaterialCollectionPageController controller =
        new MaterialCollectionPageController(backendApiClient);
    Model model = new ConcurrentModel();
    UUID jobOrderId = UUID.randomUUID();

    when(backendApiClient.get(contains("/material-collection"), anyTypeRef()))
        .thenReturn(List.of());
    when(backendApiClient.get(contains("/users/lookup"), anyTypeRef()))
        .thenThrow(new BackendServiceException("Backend error", null, 500));
    when(backendApiClient.getCached(contains("/locations/lookup"), anyTypeRef()))
        .thenReturn(List.of());

    // When
    String viewName = controller.viewMaterialCollection(jobOrderId, model);

    // Then
    assertEquals("material-collection", viewName);
    List<?> users = (List<?>) model.getAttribute("users");
    assertNotNull(users);
    assertTrue(users.isEmpty());
  }

  @Test
  void viewMaterialCollection_shouldHandleBackendErrorForLocations() {
    // Given
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    MaterialCollectionPageController controller =
        new MaterialCollectionPageController(backendApiClient);
    Model model = new ConcurrentModel();
    UUID jobOrderId = UUID.randomUUID();

    when(backendApiClient.get(contains("/material-collection"), anyTypeRef()))
        .thenReturn(List.of());
    when(backendApiClient.get(contains("/users/lookup"), anyTypeRef())).thenReturn(List.of());
    when(backendApiClient.getCached(contains("/locations/lookup"), anyTypeRef()))
        .thenThrow(new BackendServiceException("Backend error", null, 500));

    // When
    String viewName = controller.viewMaterialCollection(jobOrderId, model);

    // Then
    assertEquals("material-collection", viewName);
    List<?> locations = (List<?>) model.getAttribute("locations");
    assertNotNull(locations);
    assertTrue(locations.isEmpty());
  }
}
