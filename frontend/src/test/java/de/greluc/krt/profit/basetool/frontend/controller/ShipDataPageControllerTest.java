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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.ManufacturerDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.ShipTypeDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

class ShipDataPageControllerTest {

  @Test
  void testListData_Success() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    PageResponse<ManufacturerDto> emptyManufacturerPage =
        new PageResponse<>(Collections.emptyList(), 0, 1000, 0, 1, Collections.emptyList());
    PageResponse<ShipTypeDto> emptyShipTypePage =
        new PageResponse<>(Collections.emptyList(), 0, 1000, 0, 1, Collections.emptyList());

    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenReturn(emptyManufacturerPage)
        .thenReturn(emptyShipTypePage);

    ShipDataPageController controller = new ShipDataPageController(backendApiClient);
    Model model = new ConcurrentModel();

    String view = controller.listData(model);

    assertEquals("ship-data", view);
  }

  // covers REQ-ADMIN-001 — ship types beyond the first backend page stay visible and editable
  @Test
  void listData_concatenatesAllShipTypePages() {
    // Given — one manufacturer page, two ship-type pages
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    ShipDataPageController controller = new ShipDataPageController(backendApiClient);
    Model model = new ConcurrentModel();

    ManufacturerDto rsi = new ManufacturerDto(null, "RSI", "RSI", null, null, null, false);
    ShipTypeDto aurora = new ShipTypeDto(null, "Aurora", rsi, null, null, false);
    ShipTypeDto zeus = new ShipTypeDto(null, "Zeus", rsi, null, null, false);
    when(backendApiClient.get(
            org.mockito.ArgumentMatchers.eq(
                "/api/v1/manufacturers?size=1000&sort=name,asc&includeHidden=true&page=0"),
            anyTypeRef()))
        .thenReturn(new PageResponse<>(java.util.List.of(rsi), 0, 1000, 1, 1, null));
    String shipTypesBase = "/api/v1/ship-types?size=1000&sort=name,asc&includeHidden=true";
    when(backendApiClient.get(
            org.mockito.ArgumentMatchers.eq(shipTypesBase + "&page=0"), anyTypeRef()))
        .thenReturn(new PageResponse<>(java.util.List.of(zeus), 0, 1000, 2, 2, null));
    when(backendApiClient.get(
            org.mockito.ArgumentMatchers.eq(shipTypesBase + "&page=1"), anyTypeRef()))
        .thenReturn(new PageResponse<>(java.util.List.of(aurora), 1, 1000, 2, 2, null));

    // When
    controller.listData(model);

    // Then — both pages render, sorted, with no truncation flagged
    @SuppressWarnings("unchecked")
    java.util.List<ShipTypeDto> shipTypes =
        (java.util.List<ShipTypeDto>) model.getAttribute("shipTypes");
    assertEquals(2, shipTypes.size(), "the second backend page must not be dropped");
    assertEquals("Aurora", shipTypes.get(0).name());
    assertEquals("Zeus", shipTypes.get(1).name());
    assertEquals(Boolean.FALSE, model.getAttribute("catalogTruncated"));
  }

  @Test
  void testResetAllFitted_Success() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    ShipDataPageController controller = new ShipDataPageController(backendApiClient);
    RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

    String view = controller.resetAllFitted(redirectAttributes);

    verify(backendApiClient).post("/api/v1/hangar/ships/reset-fitted", null, Void.class);
    verify(redirectAttributes)
        .addFlashAttribute("successToast", "notification.success.ship_unfitted");
    assertEquals("redirect:/ship-data", view);
  }
}
