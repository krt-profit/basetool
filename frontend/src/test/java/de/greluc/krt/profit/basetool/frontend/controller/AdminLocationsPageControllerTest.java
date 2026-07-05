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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.LocationDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CacheDomain;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
