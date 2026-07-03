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
