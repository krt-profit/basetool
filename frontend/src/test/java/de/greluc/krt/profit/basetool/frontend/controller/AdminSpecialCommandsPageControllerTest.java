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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.SpecialCommandDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.support.CatalogPages;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

/**
 * Unit tests for {@link AdminSpecialCommandsPageController}'s list rendering. Pins the
 * REQ-ADMIN-001 complete-catalogue page walk (multi-page responses are concatenated and sorted, so
 * an SK beyond the first backend page stays visible and editable) and the REQ-ADMIN-002
 * loud-truncation flag.
 */
@SuppressWarnings("unchecked")
class AdminSpecialCommandsPageControllerTest {

  // covers REQ-ADMIN-001 — SKs beyond the first backend page are still rendered
  @Test
  void listSpecialCommands_concatenatesAllPages_andSortsByName() {
    // Given — two backend pages: [Zulu, Alpha] + [Mike]
    BackendApiClient client = mock(BackendApiClient.class);
    AdminSpecialCommandsPageController controller = new AdminSpecialCommandsPageController(client);
    String base = "/api/v1/special-commands?size=1000&sort=name,asc&includeInactive=false";
    PageResponse<Map<String, Object>> firstPage =
        new PageResponse<>(
            List.of(Map.of("name", "Zulu"), Map.of("name", "Alpha")), 0, 1000, 3, 2, List.of());
    PageResponse<Map<String, Object>> secondPage =
        new PageResponse<>(List.of(Map.of("name", "Mike")), 1, 1000, 3, 2, List.of());
    when(client.get(eq(base + "&page=0"), anyTypeRef())).thenReturn(firstPage);
    when(client.get(eq(base + "&page=1"), anyTypeRef())).thenReturn(secondPage);
    Model model = new ConcurrentModel();

    // When
    String view = controller.listSpecialCommands(false, null, model);

    // Then
    assertEquals("admin/special-commands", view);
    List<SpecialCommandDto> commands =
        (List<SpecialCommandDto>) model.getAttribute("specialCommands");
    assertNotNull(commands);
    assertEquals(3, commands.size(), "the second backend page must not be dropped");
    assertEquals("Alpha", commands.get(0).name());
    assertEquals("Mike", commands.get(1).name());
    assertEquals("Zulu", commands.get(2).name());
    assertEquals(Boolean.FALSE, model.getAttribute("catalogTruncated"));
  }

  // covers REQ-ADMIN-002 — a capped walk raises the warning-banner flag instead of staying silent
  @Test
  void listSpecialCommands_capHit_setsCatalogTruncated() {
    // Given — a backend that reports more pages than the safety cap allows
    BackendApiClient client = mock(BackendApiClient.class);
    AdminSpecialCommandsPageController controller = new AdminSpecialCommandsPageController(client);
    int reportedPages = CatalogPages.MAX_CATALOG_PAGES + 1;
    PageResponse<Map<String, Object>> endlessPage =
        new PageResponse<>(
            List.of(Map.of("name", "SK")), 0, 1000, reportedPages, reportedPages, List.of());
    when(client.get(anyString(), anyTypeRef())).thenReturn(endlessPage);
    Model model = new ConcurrentModel();

    // When
    controller.listSpecialCommands(false, null, model);

    // Then
    assertEquals(Boolean.TRUE, model.getAttribute("catalogTruncated"));
  }
}
