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

package de.greluc.krt.profit.basetool.frontend.catalogue.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.catalogue.client.CatalogueBackendClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CatalogPages;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CatalogueCacheEviction;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.SquadronDto;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

@SuppressWarnings("unchecked")
class AdminSquadronsPageControllerTest {

  private static final String TEMPLATE =
      "/api/v1/squadrons?size=1000&sort=name,asc&includeInactive={includeInactive}&page={page}";

  private final BackendApiClient backendApiClient = mock(BackendApiClient.class);

  private final AdminSquadronsPageController controller =
      new AdminSquadronsPageController(
          new CatalogueBackendClient(backendApiClient),
          new CatalogueCacheEviction(backendApiClient));

  @Test
  void listSquadrons_sortsByNameAndKeepsTheSwitchStates() {
    List<SquadronDto> squadrons = new ArrayList<>();
    squadrons.add(squadron("Zulu", true, false));
    squadrons.add(squadron("X-Ray", false, true));
    squadrons.add(squadron("Yankee", true, true));
    when(backendApiClient.get(eq(TEMPLATE), anyTypeRef(), eq(false), eq(0)))
        .thenReturn(new PageResponse<>(squadrons, 0, 1000, 3, 1, List.of("name,asc")));
    Model model = new ConcurrentModel();

    String view = controller.listSquadrons(false, null, model);

    List<SquadronDto> sorted = (List<SquadronDto>) model.getAttribute("squadrons");
    assertEquals("catalogue/admin/squadrons", view);
    assertEquals("X-Ray", sorted.get(0).name());
    assertEquals("Yankee", sorted.get(1).name());
    assertEquals("Zulu", sorted.get(2).name());
    assertEquals(Boolean.TRUE, sorted.get(1).isPromotionEnabled());
    assertEquals(Boolean.TRUE, sorted.get(1).isProfitEligible());
    assertEquals(Boolean.FALSE, sorted.get(2).isProfitEligible());
    assertEquals(Boolean.FALSE, model.getAttribute("squadronsTruncated"));
  }

  @Test
  void listSquadrons_fragmentRequestRendersOnlyTheTable() {
    when(backendApiClient.get(eq(TEMPLATE), anyTypeRef(), eq(true), eq(0)))
        .thenReturn(new PageResponse<>(List.of(), 0, 1000, 0, 0, List.of()));

    String view = controller.listSquadrons(true, "squadrons-results", new ConcurrentModel());

    assertEquals("catalogue/admin/squadrons :: squadrons-results", view);
  }

  @Test
  void listSquadrons_capHitSetsTheTruncatedFlag() {
    int pages = CatalogPages.MAX_CATALOG_PAGES + 1;
    PageResponse<Object> endless =
        new PageResponse<>(List.of(squadron("Row", true, true)), 0, 1000, pages, pages, List.of());
    when(backendApiClient.get(
            org.mockito.ArgumentMatchers.startsWith("/api/v1/squadrons"),
            anyTypeRef(),
            org.mockito.ArgumentMatchers.any(Object[].class)))
        .thenReturn(endless);
    Model model = new ConcurrentModel();

    controller.listSquadrons(false, null, model);

    assertEquals(Boolean.TRUE, model.getAttribute("squadronsTruncated"));
  }

  @Test
  void listSquadrons_backendFailureRendersAnErrorInsteadOfFailing() {
    when(backendApiClient.get(
            org.mockito.ArgumentMatchers.startsWith("/api/v1/squadrons"),
            anyTypeRef(),
            org.mockito.ArgumentMatchers.any(Object[].class)))
        .thenThrow(new BackendServiceException("backend down", null, 503));
    Model model = new ConcurrentModel();

    controller.listSquadrons(false, null, model);

    assertEquals("error.admin.mission.data.load", model.getAttribute("error"));
  }

  private static SquadronDto squadron(String name, boolean promotion, boolean profit) {
    return new SquadronDto(UUID.randomUUID(), name, null, null, true, promotion, profit, 0L);
  }
}
