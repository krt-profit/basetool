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

package de.greluc.krt.profit.basetool.frontend.orgunit.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CatalogPages;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CatalogueCacheEviction;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.orgunit.client.OrgUnitBackendClient;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.SpecialCommandDto;
import java.util.List;
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

  @Test
  void listSpecialCommands_concatenatesAllPages_andSortsByName() {
    BackendApiClient client = mock(BackendApiClient.class);
    AdminSpecialCommandsPageController controller =
        new AdminSpecialCommandsPageController(
            new OrgUnitBackendClient(client), new CatalogueCacheEviction(client));
    String template =
        "/api/v1/special-commands?size=1000&sort=name,asc"
            + "&includeInactive={includeInactive}&page={page}";
    PageResponse<SpecialCommandDto> firstPage =
        new PageResponse<>(List.of(named("Zulu"), named("Alpha")), 0, 1000, 3, 2, List.of());
    PageResponse<SpecialCommandDto> secondPage =
        new PageResponse<>(List.of(named("Mike")), 1, 1000, 3, 2, List.of());
    when(client.get(eq(template), anyTypeRef(), eq(false), eq(0))).thenReturn(firstPage);
    when(client.get(eq(template), anyTypeRef(), eq(false), eq(1))).thenReturn(secondPage);
    Model model = new ConcurrentModel();

    String view = controller.listSpecialCommands(false, null, model);

    assertEquals("admin/special-commands", view);
    List<SpecialCommandDto> commands =
        (List<SpecialCommandDto>) model.getAttribute("specialCommands");
    assertNotNull(commands);
    assertEquals(3, commands.size(), "the second backend page must not be dropped");
    assertEquals("Alpha", commands.get(0).name());
    assertEquals("Mike", commands.get(1).name());
    assertEquals("Zulu", commands.get(2).name());
    assertEquals(Boolean.FALSE, commands.get(0).active());
    assertEquals(0L, commands.get(0).version());
    assertEquals(Boolean.FALSE, model.getAttribute("catalogTruncated"));
  }

  @Test
  void listSpecialCommands_capHit_setsCatalogTruncated() {
    BackendApiClient client = mock(BackendApiClient.class);
    AdminSpecialCommandsPageController controller =
        new AdminSpecialCommandsPageController(
            new OrgUnitBackendClient(client), new CatalogueCacheEviction(client));
    int reportedPages = CatalogPages.MAX_CATALOG_PAGES + 1;
    PageResponse<SpecialCommandDto> endlessPage =
        new PageResponse<>(List.of(named("SK")), 0, 1000, reportedPages, reportedPages, List.of());
    when(client.get(anyString(), anyTypeRef(), any(Object[].class))).thenReturn(endlessPage);
    Model model = new ConcurrentModel();

    controller.listSpecialCommands(false, null, model);

    assertEquals(Boolean.TRUE, model.getAttribute("catalogTruncated"));
  }

  private static SpecialCommandDto named(String name) {
    return new SpecialCommandDto(null, name, null, null, null, null, null);
  }
}
