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

package de.greluc.krt.profit.basetool.frontend.blueprint.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.blueprint.client.BlueprintBackendClient;
import de.greluc.krt.profit.basetool.frontend.blueprint.model.BlueprintDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

/** Unit tests for {@link AdminBlueprintsPageController}. */
class AdminBlueprintsPageControllerTest {

  @Test
  void listBlueprints_populatesModelAndReturnsView() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    AdminBlueprintsPageController controller =
        new AdminBlueprintsPageController(new BlueprintBackendClient(backendApiClient));
    PageResponse<BlueprintDto> page =
        new PageResponse<>(List.of(minimalDto()), 0, 25, 1, 1, List.of());
    when(backendApiClient.get(anyString(), anyTypeRef(), eq(25), eq(0), eq("omni")))
        .thenReturn(page);
    Model model = new ConcurrentModel();

    String view = controller.listBlueprints("omni", 0, null, model);

    assertEquals("admin/blueprints", view);
    assertEquals(1, ((List<?>) model.getAttribute("blueprints")).size());
    assertEquals("omni", model.getAttribute("search"));
    assertEquals(1, model.getAttribute("totalPages"));
  }

  @Test
  void listBlueprints_fragmentResults_returnsResultsFragmentView() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    AdminBlueprintsPageController controller =
        new AdminBlueprintsPageController(new BlueprintBackendClient(backendApiClient));
    PageResponse<BlueprintDto> page =
        new PageResponse<>(List.of(minimalDto()), 0, 25, 1, 1, List.of());
    when(backendApiClient.get(anyString(), anyTypeRef(), eq(25), eq(0), eq("omni")))
        .thenReturn(page);
    Model model = new ConcurrentModel();

    String view = controller.listBlueprints("omni", 0, "results", model);

    assertEquals("admin/blueprints :: results", view);
    assertEquals(1, ((List<?>) model.getAttribute("blueprints")).size());
    assertEquals("omni", model.getAttribute("search"));
  }

  @Test
  void listBlueprints_backendFailure_setsErrorAndEmptyList() {
    BackendApiClient backendApiClient = mock(BackendApiClient.class);
    when(backendApiClient.get(anyString(), anyTypeRef(), any(Object[].class)))
        .thenThrow(new RuntimeException("backend down"));
    AdminBlueprintsPageController controller =
        new AdminBlueprintsPageController(new BlueprintBackendClient(backendApiClient));
    Model model = new ConcurrentModel();

    String view = controller.listBlueprints(null, 0, null, model);

    assertEquals("admin/blueprints", view);
    assertEquals("error.admin.blueprints.load", model.getAttribute("error"));
    assertEquals(0, ((List<?>) model.getAttribute("blueprints")).size());
  }

  private static BlueprintDto minimalDto() {
    return new BlueprintDto(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "BP",
        "Omnisky",
        540,
        false,
        2,
        1,
        "4.8",
        null,
        null,
        null,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        0L);
  }
}
