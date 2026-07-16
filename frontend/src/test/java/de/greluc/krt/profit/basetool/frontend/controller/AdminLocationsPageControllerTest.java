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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.LocationDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CacheDomain;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ConcurrentModel;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/**
 * Pure-Mockito unit tests for {@link AdminLocationsPageController}. Pins that the home-location
 * toggle reads the current record and re-PUTs a full {@link LocationDto} with only the
 * home-location flag changed (the backend expects a full DTO, not a JSON merge patch) — the same
 * shape as the established visibility toggle.
 */
@ExtendWith(MockitoExtension.class)
class AdminLocationsPageControllerTest {

  @Mock private BackendApiClient backendApiClient;

  @InjectMocks private AdminLocationsPageController controller;

  @Test
  void toggleHomeLocation_readsCurrentAndPutsFlippedFlag_preservingOtherFields() {
    UUID id = UUID.randomUUID();
    LocationDto current = new LocationDto(id, "Lorville", "Hurston city", false, false, 2L);
    when(backendApiClient.get("/api/v1/locations/" + id, LocationDto.class)).thenReturn(current);

    String view = controller.toggleHomeLocation(id, true, new RedirectAttributesModelMap());

    ArgumentCaptor<LocationDto> body = ArgumentCaptor.forClass(LocationDto.class);
    verify(backendApiClient).put(eq("/api/v1/locations/" + id), body.capture(), eq(Void.class));
    assertTrue(body.getValue().homeLocation(), "the new home-location flag must be persisted");
    assertEquals("Lorville", body.getValue().name(), "name must be preserved");
    assertEquals("Hurston city", body.getValue().description(), "description must be preserved");
    assertEquals(false, body.getValue().hidden(), "hidden flag must be preserved");
    assertEquals(2L, body.getValue().version(), "version must be echoed for optimistic locking");
    assertEquals("redirect:/admin/locations", view);
    verify(backendApiClient).evict(CacheDomain.LOCATION);
  }

  // covers REQ-ADMIN-001 — locations beyond the first backend page stay visible and editable
  @Test
  void listData_concatenatesAllBackendPages_andSorts() {
    // Given — two backend pages: [Port Olisar] + [Area18]
    LocationDto portOlisar =
        new LocationDto(UUID.randomUUID(), "Port Olisar", "Crusader station", false, false, 0L);
    LocationDto area18 =
        new LocationDto(UUID.randomUUID(), "Area18", "ArcCorp city", false, true, 0L);
    String base = "/api/v1/locations?size=1000&sort=name,asc&includeHidden=true";
    when(backendApiClient.get(eq(base + "&page=0"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(portOlisar), 0, 1000, 2, 2, List.of("name,asc")));
    when(backendApiClient.get(eq(base + "&page=1"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(area18), 1, 1000, 2, 2, List.of("name,asc")));
    ConcurrentModel model = new ConcurrentModel();

    // When
    String view = controller.listData(model);

    // Then — both pages render, sorted case-insensitively, with no truncation flagged
    assertEquals("admin/locations", view);
    @SuppressWarnings("unchecked")
    List<LocationDto> locations = (List<LocationDto>) model.getAttribute("locations");
    assertEquals(2, locations.size(), "the second backend page must not be dropped");
    assertEquals("Area18", locations.get(0).name());
    assertEquals("Port Olisar", locations.get(1).name());
    assertEquals(Boolean.FALSE, model.getAttribute("catalogTruncated"));
  }

  @Test
  void toggleLocationVisibilityAjax_evictsLocationDomainAfterWrite() {
    UUID id = UUID.randomUUID();
    LocationDto current = new LocationDto(id, "Area18", "ArcCorp city", false, false, 4L);
    when(backendApiClient.get("/api/v1/locations/" + id, LocationDto.class)).thenReturn(current);

    controller.toggleLocationVisibilityAjax(id);

    verify(backendApiClient)
        .put(eq("/api/v1/locations/" + id), any(LocationDto.class), eq(Void.class));
    verify(backendApiClient).evict(CacheDomain.LOCATION);
  }
}
